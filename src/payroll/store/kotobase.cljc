(ns payroll.store.kotobase
  "The durable backend: `payroll.store/Store` over a kotobase.net node.

  This is the backend `docs/maturity.md` has had a row for since the row
  existed — `Durable storage across a restart | implemented ❌ | tested ✅`,
  where the test started a host, committed a run, restarted, and asserted the
  ledger was EMPTY. That test is what this namespace is written against.

  ## What is durable here, and what is still not claimed

  Durable means: a second `KotobaseStore`, constructed independently over the
  same transport and tenant, reconstructs the same records in the same order.
  That is measured — `payroll.store.kotobase-test` builds two stores and two
  hosts and reads back through the second.

  It does NOT mean this has been run against kotobase.net. The transport is
  injected and this repository ships no implementation that reaches a
  network, so `deployed` and `production-verified` stay false. What changed
  is that the *store* is no longer the reason.

  ## The shape: one CAS-guarded chain per stream

  Each stream (`clients`, `contracts`, `timesheets`, `records`, `ledger`,
  `cutover`, `juminzei`) is a singly-linked chain of immutable blocks, newest
  first, with a ref whose head is moved by compare-and-set.

  ```text
    ref payroll/<tenant>/ledger ──▶ node₃ ──prev──▶ node₂ ──prev──▶ node₁ ──▶ nil
                                     │               │               │
                                     ▼               ▼               ▼
                                  block₃          block₂          block₁   (sealed)
  ```

  Seven refs and not one, because a CAS lane is a serialisation point: an
  operator registering a timesheet and a run committing at the same instant
  are not in conflict, and a single ref would make them fight over a head
  neither of them read.

  ## Why the payload is sealed and the node is not

  A node carries the stream, a sequence number, the previous node's CID, the
  payload block's CID and an idempotency digest. None of that is payroll
  data, and all of it has to be readable to walk the chain at all — a
  reconstruction that had to decrypt in order to find the next link would be
  a reconstruction that fails closed on a key rotation and takes the history
  with it.

  The payload — the contract, the timesheet, the committed run, the ledger
  entry with its verdict — goes through `payroll.kotobase.envelope` and is
  never written in the clear. There is no provider in this repository and
  `payroll.host.config` refuses a durable deployment without one.

  **The block address is the CID of the SEALED bytes, not of the plaintext.**
  Addressing by plaintext hash would have been convenient — identical
  payloads would deduplicate — and would have made every block a confirmation
  oracle: anybody holding the ciphertext and a guess at the wage could
  confirm the guess by hashing it. Deduplication of payroll records is worth
  nothing; that oracle costs everything.

  ## Idempotency has two halves and needs both

  1. **A retried write is not a second write.** `put-block!` is idempotent by
     construction (the bytes are the address), and a lost CAS acknowledgement
     is recognised: after a conflict the store re-reads the head, and if the
     head is the node it was trying to install, the write already happened.
     A transport that applies a CAS and then fails to answer is the case
     `payroll.store.kotobase-test/a-lost-acknowledgement-does-not-write-twice`
     builds, because on a payroll ledger the second write of a `:commit` is
     a second payment.
  2. **Re-registering identical content is a no-op**, for the streams where
     that is what it means: a client, a contract, a cutover cycle and a
     住民税 notice are KEYED records, and writing the same map twice is one
     registration. Timesheets, records and ledger entries are NOT
     deduplicated by content — two identical holds in the same minute are two
     holds, and a store that collapsed them would be editing an audit trail.

  ## Tenancy

  `tenant` is the node-side identity and is part of every ref and every block
  operation, so two deployments on one node cannot see each other. Inside a
  tenant, `:employers` may additionally declare which employer ids this store
  is allowed to write — a single-employer deployment sets it, and a write for
  anybody else is REFUSED rather than filed. Absent, nothing is restricted,
  which is what the shared contract test runs under."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [payroll.digest :as digest]
            [payroll.kotobase.blind-index :as blind]
            [payroll.kotobase.envelope :as envelope]
            [payroll.kotobase.transport :as transport]
            [payroll.store :as store]))

(def streams
  "Every chain, and what a record on it is keyed by.

  `:stream/key-fn` nil means the stream is a log and is never deduplicated by
  content — see the namespace docstring.

  `:stream/owner-fn` is how a record names the employer it belongs to, and is
  what `:employers` is checked against. A stream whose records name no
  employer (timesheets name a worker) has nil here and is not checked, which
  is stated rather than left to be inferred: `payroll.touroku/admit-timesheet`
  is where a timesheet's employer is established, and it is established by
  the worker being on one of that employer's contracts."
  [{:stream/id :clients :stream/key-fn :client-id :stream/owner-fn :client-id}
   {:stream/id :contracts :stream/key-fn :contract/id
    :stream/owner-fn :contract/employer}
   {:stream/id :timesheets :stream/key-fn nil :stream/owner-fn nil}
   {:stream/id :records :stream/key-fn nil :stream/owner-fn :client-id}
   {:stream/id :ledger :stream/key-fn nil :stream/owner-fn :client-id}
   {:stream/id :cutover :stream/key-fn :cycle/id :stream/owner-fn :cycle/employer}
   ;; `:stream/key-fn` is the KEYWORD `:notice/id` and not a function that
   ;; computes an id out of a notice. The id is computed exactly once, at
   ;; admission (`payroll.juminzei/notice-id`), and travels with the record —
   ;; so this layer looks it up and gains no dependency on 住民税 at all. A
   ;; key-fn that rebuilt the id here would be a second copy of that rule
   ;; living in the storage layer, and the copy that drifts is the one a
   ;; retried registration is deduplicated against.
   {:stream/id :juminzei :stream/key-fn :notice/id
    :stream/owner-fn :notice/employer}])

(def stream-by-id (into {} (map (juxt :stream/id identity)) streams))

;; `durability-evidence` is on the record and `health` is the function that
;; measures it, and the record has to come first so that `store` can build one.
(declare health)

;; ---------------------------------------------------------------------------
;; Blocks
;; ---------------------------------------------------------------------------

(defn- ->bytes [v] (digest/utf8-bytes (pr-str v)))

(defn- put!
  "Write one EDN value as an immutable block. Returns its CID, or throws with
  a message that carries no payload — the CID and the refusal reason are
  safe, the value is not."
  [transport* tenant v]
  (let [bs (->bytes v)
        cid (digest/cid bs)
        r (transport/put-block! transport* tenant cid bs)]
    (if (= :ok (:block/status r))
      cid
      (throw (ex-info "kotobase block write refused"
                      {:block/cid cid :block/why (:block/why r)})))))

(defn fetch-block
  "Read one block and say what happened, in three distinguishable states.

    {:read/status :ok      :read/value v}
    {:read/status :missing :read/why …}   the node does not have it
    {:read/status :tampered :read/why …}  it has bytes that are not this CID

  **The CID is re-derived from the returned bytes and compared.** Without
  that comparison a content-addressed store is content-addressed only on the
  way in: a node that serves altered bytes under the address it was asked
  for gets its alteration parsed as EDN and walked as a chain node, and the
  read reports success. That is the failure content addressing exists to make
  detectable, and detecting it costs one hash.

  A byte sequence that is not valid UTF-8, or that is not readable EDN, is
  `:tampered` and not `:missing`: the node answered, and what it answered
  with is wrong. Those are different operator actions — replicate the block,
  versus stop trusting this node.

  Three states rather than nil, because `reconstruct` has to be able to tell
  a partially replicated node (a real, survivable state) from one that is
  serving corrupted bytes, and a nil renders them identically."
  [transport* tenant cid]
  (let [r (transport/get-block transport* tenant cid)]
    (if-not (= :ok (:block/status r))
      {:read/status :missing
       :read/why (str "block " cid " が node に無い")}
      (let [bs (:block/bytes r)
            actual (digest/cid bs)]
        (if (not= cid actual)
          {:read/status :tampered
           :read/why (str "block " cid " として返された bytes の"
                          "内容アドレスは " actual " である。"
                          "改竄されたか、node が別の block を返している")}
          (let [s (digest/utf8-string bs)
                v (when s (try (edn/read-string s)
                               (catch #?(:clj Exception :cljs :default) _ ::unreadable)))]
            (cond
              (nil? s)
              {:read/status :tampered
               :read/why (str "block " cid " の bytes が UTF-8 として読めない")}
              (= ::unreadable v)
              {:read/status :tampered
               :read/why (str "block " cid " の中身が EDN として読めない")}
              :else {:read/status :ok :read/value v})))))))

;; ---------------------------------------------------------------------------
;; The chain
;; ---------------------------------------------------------------------------

(defn- idempotency-tag
  "A stable, unlinkable tag for `what this write is`, from the injected
  `payroll.kotobase.blind-index/BlindIndex`.

  Returns `{:index/status :ok :index/tag …}` or the provider's refusal,
  UNCHANGED. There is no fallback to an unkeyed hash: a fallback would fire
  exactly when the provider is missing, which is the configuration this
  refuses to run under, and would restore the confirmation oracle silently.
  See `payroll.kotobase.blind-index` for what that oracle is."
  [index tenant stream k]
  (blind/tag index tenant stream k))

(defn- node
  [{:keys [stream seq* prev block idem]}]
  (cond-> {:node/stream stream
           :node/seq seq*
           :node/prev prev
           :node/block block}
    idem (assoc :node/idempotency idem)))

(defn reconstruct
  "Walk one stream's chain from its head and return

    {:chain/entries  [payload …]     oldest first
     :chain/head     cid or nil
     :chain/nodes    [node …]        oldest first
     :chain/idem     #{digest …}
     :chain/complete? bool
     :chain/why      when incomplete}

  **`:chain/complete?` is the evidence floor.** A chain whose tail block is
  missing from the node, or whose payload will not decrypt, produces FEWER
  entries — and fewer entries is exactly what an empty store looks like. A
  reader handed `[]` cannot tell `this employer has filed nothing` from `this
  node has lost the log`, and on a payroll ledger those are opposite
  answers. So the walk reports how far it got and why it stopped, and
  `payroll.store.kotobase/health` refuses to call an incomplete chain
  healthy.

  The walk is also bounded. A chain whose `:node/prev` pointed at itself —
  which a corrupted or hostile node can produce — would otherwise be an
  infinite loop inside a payroll read.

  `:chain/broken` carries one entry per block that could not be read, each
  with `:broken/kind` of `:missing` or `:tampered`. Kept structured rather
  than folded into the prose reason, because `payroll.store.kotobase/health`
  and `payroll.edge.endpoints` both have to act on the DISTINCTION — a
  missing block is a replication problem and a tampered one is a trust
  problem, and they are the same sentence only if nobody separated them."
  [transport* tenant envelope* stream]
  (let [head-r (transport/read-head transport* tenant
                                    (transport/ref-for tenant stream))]
    (if-not (= :ok (:head/status head-r))
      {:chain/entries [] :chain/head nil :chain/nodes [] :chain/idem #{}
       :chain/broken [{:broken/kind :head
                       :broken/why (:head/why head-r)}]
       :chain/complete? false
       :chain/why (str (name stream) " の head を読めなかった。"
                       "これは「記録が無い」ではなく「読めなかった」である")}
      (loop [cid (:head/cid head-r)
             seen #{}
             nodes ()
             node-break nil
             why nil]
        (cond
          (nil? cid)
          (let [ns* (vec nodes)
                decoded (mapv (fn [n]
                                (let [blk (fetch-block transport* tenant
                                                       (:node/block n))
                                      opened (when (= :ok (:read/status blk))
                                               (envelope/open envelope* tenant
                                                              (:read/value blk)))
                                      plain (when (= :ok (:envelope/status opened))
                                              (try (edn/read-string
                                                    (:envelope/plaintext opened))
                                                   (catch #?(:clj Exception
                                                             :cljs :default) _
                                                     ::unreadable)))]
                                  (cond
                                    (not= :ok (:read/status blk))
                                    {::broken {:broken/cid (:node/block n)
                                               :broken/kind (:read/status blk)
                                               :broken/why (:read/why blk)}}

                                    (not= :ok (:envelope/status opened))
                                    {::broken {:broken/cid (:node/block n)
                                               :broken/kind :envelope
                                               :broken/why
                                               (or (:envelope/why opened)
                                                   "payload を復号できない")}}

                                    (= ::unreadable plain)
                                    {::broken {:broken/cid (:node/block n)
                                               :broken/kind :envelope
                                               :broken/why
                                               (str "復号できたが EDN として"
                                                    "読めない。封緘の中身が"
                                                    "この store の書いたもので"
                                                    "はない")}}

                                    :else {::payload plain})))
                              ns*)
                broken (mapv ::broken (filterv ::broken decoded))
                node-broken (when node-break [node-break])
                all-broken (vec (concat (or node-broken []) broken))]
            {:chain/entries (vec (keep ::payload decoded))
             :chain/head (:head/cid head-r)
             :chain/nodes ns*
             :chain/idem (into #{} (keep :node/idempotency) ns*)
             :chain/broken all-broken
             :chain/complete? (and (nil? why) (empty? all-broken))
             :chain/why (or why
                            (when (seq broken)
                              (str (count broken) " 件の payload を読めなかった: "
                                   (:broken/why (first broken))
                                   "。読めない記録は「無い記録」ではない")))})

          (contains? seen cid)
          (recur nil seen nodes nil
                 (str "chain が自分自身を指している（" cid "）。"
                      "そこで打ち切った"))

          :else
          (let [r (fetch-block transport* tenant cid)]
            (if (= :ok (:read/status r))
              (recur (:node/prev (:read/value r)) (conj seen cid)
                     (conj nodes (:read/value r)) node-break why)
              (recur nil seen nodes
                     {:broken/cid cid :broken/kind (:read/status r)
                      :broken/why (:read/why r)}
                     (str (:read/why r)
                          "。chain はここで切れており、これより古い記録は"
                          "この応答に入っていない")))))))))

;; ---------------------------------------------------------------------------
;; Appending
;; ---------------------------------------------------------------------------

(defn- owner-of [stream record]
  (when-let [f (:stream/owner-fn (stream-by-id stream))] (f record)))

(defn- employer-refusal
  "Is this write for an employer this store may not write for?"
  [employers stream record]
  (when-let [owner (and (seq employers) (owner-of stream record))]
    (when-not (contains? employers owner)
      (str "この store は事業主 " (pr-str (vec (sort employers)))
           " の記録だけを書ける。"
           (pr-str owner) " の記録は拒否する"
           "（テナント内の事業主分離は store で強制される）"))))

(defn- append!
  "Append one payload to a stream. Returns `{:append/status :ok …}` or
  `{:append/status :refused …}` / `{:append/status :conflict …}`.

  The retry loop is bounded by `payroll.kotobase.transport/max-cas-attempts`
  and every attempt re-reads the chain, because a lost CAS means somebody
  else moved the head and the sequence number this write claimed is no longer
  the next one."
  [{:keys [transport tenant envelope employers blind-index]} stream payload]
  (if-let [why (employer-refusal employers stream payload)]
    {:append/status :refused :append/why why}
    (let [key-fn (:stream/key-fn (stream-by-id stream))
          idem-r (when key-fn
                   (idempotency-tag blind-index tenant stream
                                    [(key-fn payload)
                                     (digest/hex
                                      (digest/sha256 (->bytes payload)))]))
          idem (:index/tag idem-r)
          sealed (envelope/seal envelope tenant (pr-str payload))]
      (cond
        (and key-fn (not= :ok (:index/status idem-r)))
        {:append/status :refused
         :append/why (or (:index/why idem-r)
                         (str "blind index が tag を返さなかった。"
                              "鍵付きの tag が無ければ、"
                              "再送された書き込みを見分けられない"))}

        (not= :ok (:envelope/status sealed))
        {:append/status :refused
         :append/why (or (:envelope/why sealed)
                         "payload を封緘できなかった。平文では書かない")}

        :else
        (let [block-cid (put! transport tenant sealed)]
          (loop [attempt 1]
            (let [chain (reconstruct transport tenant envelope stream)]
              (cond
                ;; A chain that could not be fully walked cannot answer
                ;; "has this already been written". Appending against a
                ;; partial read of a payroll ledger writes the record a
                ;; second time whenever the block carrying the first one is
                ;; the block that could not be read.
                (not (:chain/complete? chain))
                {:append/status :refused
                 :append/why (str stream " の chain を末尾まで辿れないので"
                                  "追記しない: " (:chain/why chain)
                                  "。読めない履歴に対する追記は、"
                                  "冪等性を確かめずに書くことである")}

                (and idem (contains? (:chain/idem chain) idem))
                {:append/status :ok :append/deduplicated? true
                 :append/head (:chain/head chain)}

                :else
                (let [n (node {:stream stream
                               :seq* (count (:chain/nodes chain))
                               :prev (:chain/head chain)
                               :block block-cid
                               :idem idem})
                      node-cid (put! transport tenant n)
                      cas (transport/cas-head! transport tenant
                                               (transport/ref-for tenant stream)
                                               (:chain/head chain) node-cid)]
                  (cond
                    (= :ok (:cas/status cas))
                    {:append/status :ok :append/head node-cid
                     :append/seq (:node/seq n)}

                    ;; The lost acknowledgement. The CAS was applied and the
                    ;; answer did not arrive; the head IS this write. Writing
                    ;; again here is how a `:commit` becomes two payments.
                    (= node-cid (:cas/actual cas))
                    {:append/status :ok :append/head node-cid
                     :append/recovered-lost-ack? true}

                    (and (= :conflict (:cas/status cas))
                         (< attempt transport/max-cas-attempts))
                    (recur (inc attempt))

                    (= :conflict (:cas/status cas))
                    {:append/status :conflict
                     :append/attempts attempt
                     :append/why (str stream " の head が " attempt
                                      " 回続けて別の書き手に取られた。"
                                      "この書き込みは行われていない")}

                    :else
                    {:append/status :refused
                     :append/why (or (:cas/why cas)
                                     "head を進められなかった")}))))))))))

(defn- entries-of
  "Every payload on `stream`, oldest first — or a THROW naming why the chain
  could not be read to the end.

  ## Why this fails closed rather than returning what it got

  `Store`'s readers return a value, so an incomplete read has exactly two
  places to go: into the returned collection, or into an exception. Putting
  it into the collection is what this used to do, and it produces the one
  shape this whole repository is organised against — **fewer entries look
  exactly like fewer records.**

  Concretely: `payroll.governor` reads the ledger to decide whether a run is
  a duplicate, `payroll.edge.endpoints/ledger-core` serves it as the audit
  trail, and `payroll.cutover/evaluate` counts reconciled cycles out of it.
  A node that has lost, or is serving altered bytes for, the block holding
  last month's `:commit` would make all three answer: no such run, empty
  ledger, zero cycles. Every one of those answers is ACTIONABLE and every one
  of them is wrong — the second payroll run of the month gets approved, the
  audit trail reads as a clean employer, and the cutover gate resets to 0/3.

  The exception carries the stream, the reason and the structured
  `:chain/broken`, and never a payload — same rule as `append-or-throw!`."
  [{:keys [transport tenant envelope]} stream]
  (let [chain (reconstruct transport tenant envelope stream)]
    (if (:chain/complete? chain)
      (:chain/entries chain)
      (throw (ex-info (str "kotobase read refused: " (name stream))
                      {:read/stream stream
                       :read/why (:chain/why chain)
                       :read/broken (:chain/broken chain)
                       :read/entries-recovered (count (:chain/entries chain))})))))

(defn- append-or-throw!
  "`append!`, with a refusal turned into an exception carrying NO payload.

  The `Store` protocol's writers return the store, so there is no channel for
  a refusal — and swallowing one would mean a payroll run that reported
  itself committed and was not written, which is the worst outcome available
  here. The message names the stream and the reason and never the record."
  [s stream payload]
  (let [r (append! s stream payload)]
    (when-not (= :ok (:append/status r))
      (throw (ex-info (str "kotobase append refused: " (name stream))
                      {:append/status (:append/status r)
                       :append/why (:append/why r)
                       :append/stream stream})))
    s))

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

(defn- latest-by
  "The newest record for each key, in the order the keys were first seen.

  Last-wins, which is what makes re-registering a contract a correction
  rather than a fork — the property `payroll.store-contract-test/
  re-registering-an-employer-id-replaces-rather-than-forks` pins for every
  backend."
  [key-fn entries]
  (reduce (fn [m e] (assoc m (key-fn e) e)) {} entries))

(defrecord KotobaseStore [transport tenant envelope employers blind-index]
  store/Store
  (client [s client-id]
    (get (latest-by :client-id (entries-of s :clients)) client-id))
  (contract-of [s contract-id]
    (get (latest-by :contract/id (entries-of s :contracts)) contract-id))
  (timesheets-of [s worker]
    (filterv #(= worker (:ts/worker %)) (entries-of s :timesheets)))
  (records-of [s client-id]
    (filterv #(= client-id (:client-id %)) (entries-of s :records)))
  (ledger [s] (entries-of s :ledger))
  (register-client! [s c] (append-or-throw! s :clients c))
  (register-contract! [s c] (append-or-throw! s :contracts c))
  (register-timesheet! [s e] (append-or-throw! s :timesheets e))
  (commit-record! [s r] (append-or-throw! s :records r))
  (append-ledger! [s f] (append-or-throw! s :ledger f))
  (commit-cutover-cycle! [s c] (append-or-throw! s :cutover c))
  (cutover-cycles [s client-id]
    (when (some? client-id)
      (filterv #(= client-id (:cycle/employer %)) (entries-of s :cutover))))
  (register-juminzei-notice! [s n] (append-or-throw! s :juminzei n))
  (juminzei-notices [s client-id]
    (when (some? client-id)
      (filterv #(= client-id (:notice/employer %)) (entries-of s :juminzei))))

  store/Durable
  (durability-evidence [s]
    ;; Measured here and now, by walking all seven chains, rather than read off
    ;; a flag. `:evidence/survives-process-restart?` is the TRANSPORT's own
    ;; claim (`payroll.host.config/durability` keeps the same rule) and this
    ;; store does not make it on the transport's behalf.
    (let [h (health s)]
      {:evidence/mode :kotobase
       :evidence/survives-process-restart? (boolean
                                            (:transport/durable?
                                             (transport/describe transport)))
       :evidence/readable? (boolean (:store/readable? h))
       :evidence/streams (:store/streams h)
       :evidence/break-kinds (:store/break-kinds h)
       :evidence/key-separation (:store/key-separation h)
       :evidence/why (:store/why h)})))

(def required
  "What a durable deployment must supply before this store can be built.

  A vector rather than a `cond` in the constructor, because
  `payroll.host.config` needs to report the SAME list before it has any of
  these — an operator told `something is missing` learns nothing, and the
  host refuses at start-up rather than on the first write."
  [{:required/key :transport
    :required/label "kotobase transport"
    :required/why (str "この repository は network に到達する transport を"
                       "同梱していない。durable 配備は実装を注入する"
                       "（payroll.kotobase.transport/Transport）")}
   {:required/key :tenant
    :required/label "テナント識別子"
    :required/why (str "すべての ref と block 操作に載る。"
                       "テナントが無ければ、同じ node 上の別配備と"
                       "同じ鍵空間を共有することになる")}
   {:required/key :envelope
    :required/label "暗号化プロバイダ"
    :required/why (str "給与の台帳を平文で durable store に書かない。"
                       "既定は payroll.kotobase.envelope/refusing-envelope で、"
                       "これは封緘を拒否するので書き込みが起こらない")}
   {:required/key :auth
    :required/label "scope を絞った認証プロバイダ"
    :required/why (str "transport が node に対して名乗る資格。"
                       "この repository は値を読まないし、"
                       "describe にも health にも出さない")}
   {:required/key :cas
    :required/label "head の compare-and-set"
    :required/why (str "last-write-wins の head では、"
                       "同時に走った二つの run のうち一方が"
                       "何の記録も残さずに消える")}
   {:required/key :blind-index
    :required/label "鍵付き blind index プロバイダ"
    :required/why (str "冪等性の tag は node 上に平文で載る。"
                       "鍵の無い SHA-256 では、"
                       "契約 ID を当てられる者が"
                       "封緘を一つも開けずに在籍を確認できる"
                       "（payroll.kotobase.blind-index）。"
                       "封筒の鍵とは別の鍵でなければならない —— "
                       "回転の周期も、晒される度合いも違う")}])

(defn missing
  "Which of `required` this configuration does not have. Empty is the only
  configuration `store` will build.

  The blind index is checked THREE ways and all three must hold: it has to
  satisfy the protocol, it has to tag stably and injectively
  (`blind-index/configured?` probes for both), and it has to be keyed by
  something other than the envelope. A provider that fails only the third is
  the most likely real misconfiguration — one keychain item wired into two
  environment variables — and it is the one that looks correct from every
  other angle."
  [{:keys [transport tenant envelope auth cas? blind-index]}]
  (vec (for [r required
             :let [k (:required/key r)]
             :when (case k
                     :transport (not (satisfies? transport/Transport transport))
                     :tenant (not (and (string? tenant) (seq tenant)))
                     :envelope (not (envelope/configured? envelope))
                     :auth (not (ifn? auth))
                     :cas (not (true? cas?))
                     :blind-index
                     (not (and (blind/configured? blind-index)
                               (blind/distinct-from-envelope? blind-index
                                                              envelope))))]
         r)))

(defn store
  "A `KotobaseStore`, or a refusal naming everything that is missing.

    {:transport  a `payroll.kotobase.transport/Transport`
     :tenant     the node-side identity, a non-blank string
     :envelope   a `payroll.kotobase.envelope/Envelope` that actually seals
     :auth       a zero-arg fn returning the scoped credential. NEVER called
                 here and never stored — it is the transport's, and this
                 checks only that a deployment supplied one
     :cas?       the deployment's assertion that its transport's `cas-head!`
                 is a real compare-and-set and not a write
     :blind-index a `payroll.kotobase.blind-index/BlindIndex` keyed by a
                 secret that is NOT the envelope's
     :employers  optional set of employer ids this store may write for}

  Returns `{:store/status :ok :store/store s}` or
  `{:store/status :refused :store/missing […] :store/why …}`.

  `:auth` is checked for presence and never invoked, which is the same rule
  `payroll.edge.endpoints` keeps about a verified DID: the credential is
  somebody else's to handle, and a repository that read one would be a
  repository that could log one."
  [{:keys [transport tenant envelope employers blind-index] :as config}]
  (let [gaps (missing config)]
    (if (seq gaps)
      {:store/status :refused
       :store/missing gaps
       :store/why (str "durable store を構成できない: "
                       (str/join "、" (map :required/label gaps))
                       "。設定が揃うまで起動しない"
                       "（空の store で起動すると、"
                       "配備の不備が事業主の登録漏れに見える）")}
      {:store/status :ok
       :store/store (->KotobaseStore transport tenant envelope
                                     (when (seq employers) (set employers))
                                     blind-index)})))

(defn health
  "What this store can say about itself, with nothing sensitive in it.

  ## Health is the ONE surface that reads a broken chain and does not throw

  Every other read fails closed (`entries-of`). This one has to keep working
  precisely when they do not: the operator asking `what is wrong with the
  store` is asking during the outage, and a health endpoint that raised the
  same exception would answer the question with the symptom.

  So the walk is done directly, never through `entries-of`, and each chain
  reports head, entry count, completeness and — separately — the KIND of
  every break. `:missing` and `:tampered` are different problems with
  different next actions (replicate the block; stop trusting this node), and
  a health response that printed one sentence for both would erase that.

  `:store/entries-are-a-floor?` is here because the counts below are counts
  of what could be READ. On an incomplete chain that number is a lower bound
  and not a total, and a reader who takes it for a total has been told the
  employer filed less than they did."
  [{:keys [transport tenant envelope blind-index] :as s}]
  (let [chains (for [{:stream/keys [id]} streams
                     :let [c (reconstruct transport tenant envelope id)]]
                 {:stream id
                  :head (:chain/head c)
                  :entries (count (:chain/entries c))
                  :complete? (:chain/complete? c)
                  :broken (mapv #(select-keys % [:broken/kind :broken/why])
                                (:chain/broken c))
                  :why (:chain/why c)})
        chains (vec chains)
        readable? (every? :complete? chains)
        kinds (into #{} (mapcat #(map :broken/kind (:broken %))) chains)]
    {:store/mode :kotobase
     :store/survives-process-restart? true
     :store/tenant tenant
     :store/transport (transport/describe transport)
     :store/envelope (envelope/describe envelope)
     :store/blind-index (if blind-index
                          (blind/describe blind-index)
                          (blind/describe blind/refusing-blind-index))
     :store/key-separation (if blind-index
                             (blind/key-separation blind-index envelope)
                             :unknown)
     :store/employers (when (:employers s) (vec (sort (:employers s))))
     :store/streams chains
     :store/readable? readable?
     :store/entries-are-a-floor? (not readable?)
     :store/break-kinds (vec (sort-by name kinds))
     :store/why (if readable?
                  (str "七つの chain すべてを head から末尾まで辿れた。"
                       "これはこの node が今答えたことであって、"
                       "耐久性の保証ではない")
                  (str "辿れない chain がある: "
                       (str/join "、"
                                 (keep #(when-not (:complete? %)
                                          (str (name (:stream %)) "（"
                                               (:why %) "）"))
                                       chains))
                       "。ここに出ている件数は読めた分の下限であって、"
                       "この事業主が届け出た件数ではない"))}))
