(ns payroll.kotobase.fake
  "A transport and an envelope that exist only for the suite.

  ## Why they are here and not in `src/`

  `payroll.kotobase.envelope` ships no cipher on purpose, and a `plaintext
  provider` sitting in `src/` would be exactly the thing a deployment in a
  hurry reaches for. So the reversible one lives in the test tree, where
  nothing on the `src` classpath can require it.

  The transport is here for the same reason in reverse: it is not a stub for
  something missing, it is the instrument that makes the durable properties
  MEASURABLE. A restart, a lost acknowledgement and two writers racing for
  one head are all things a real node does rarely and unrepeatably; here they
  are arguments.

  ## What the fake guarantees, so that a test over it means something

  - blocks are keyed by `[tenant cid]`, so a tenant cannot read another's
    block **even given the CID** — and a content address is derivable from
    the content, so an unscoped block plane leaks whatever can be guessed
  - `cas-head!` is a real compare-and-set inside a `swap!`, not a write
  - `put-block!` of an existing CID succeeds without changing anything"
  (:require [payroll.digest :as digest]
            [payroll.kotobase.blind-index :as blind]
            [payroll.kotobase.envelope :as envelope]
            [payroll.kotobase.transport :as transport]))

;; ---------------------------------------------------------------------------
;; The envelope
;; ---------------------------------------------------------------------------

(def test-scheme
  "Named so it is impossible to mistake for a cipher in a test failure."
  :test-only-reversible-not-encryption)

(defn reversible-envelope
  "A byte-rotating envelope. **This is not encryption and does not claim to
  be.** It exists to prove the seam: that the store seals before writing,
  that a block is unreadable without going back through `open`, that a
  provider can refuse, and that a key id travels with the ciphertext.

  `key-id` is carried so the rotation test can seal under one and refuse
  under another."
  ([] (reversible-envelope "test-key-1"))
  ([key-id]
   (reify envelope/Envelope
     (seal [_ tenant plaintext]
       {:envelope/status :ok
        :envelope/scheme test-scheme
        :envelope/key-id key-id
        ;; the tenant is mixed in, so a block resealed under another tenant
        ;; does not open — the associated-data property a real provider would
        ;; give, in the cheapest form that demonstrates the seam exists.
        :envelope/ciphertext
        (mapv #(mod (+ % 7 (count tenant)) 256)
              (digest/utf8-bytes plaintext))})
     (open [_ tenant e]
       (if (and (= test-scheme (:envelope/scheme e))
                (= key-id (:envelope/key-id e)))
         (if-let [s (digest/utf8-string
                     (mapv #(mod (- % 7 (count tenant)) 256)
                           (:envelope/ciphertext e)))]
           {:envelope/status :ok :envelope/plaintext s}
           {:envelope/status :refused
            :envelope/why "この tenant の鍵では復号できない"})
         {:envelope/status :refused
          :envelope/why (str "鍵 " (pr-str (:envelope/key-id e))
                             " で封緘された block は開けない")}))
     (describe [_]
       {:envelope/scheme test-scheme
        :envelope/configured? true
        :envelope/key-id key-id
        :envelope/why "試験用。暗号ではない"}))))

;; ---------------------------------------------------------------------------
;; The blind index
;; ---------------------------------------------------------------------------

(defn keyed-blind-index
  "A prefix-keyed SHA-256. **This is not an HMAC** and does not claim to be —
  `H(key || message)` is length-extendable, which a real provider must not
  be. It exists to prove the seam: that the store takes its tags from an
  injected provider, that the provider can refuse, that the tag is stable
  across two independently constructed stores, and that a store keyed by one
  secret does not recognise writes tagged under another.

  `key-id` is published so `payroll.kotobase.blind-index/distinct-from-envelope?`
  has something to compare — the test that a shared secret is REFUSED sets it
  equal to the envelope's."
  ([] (keyed-blind-index "test-index-key-1" "index-secret"))
  ([key-id secret]
   (reify blind/BlindIndex
     (tag [_ tenant stream k]
       {:index/status :ok
        :index/tag (digest/sha256-hex
                    (str secret "|" tenant "|" (name stream) "|" (pr-str k)))})
     (describe [_]
       {:index/scheme :test-only-prefix-keyed-sha256
        :index/configured? true
        :index/key-id key-id
        :index/why "試験用。HMAC ではない"}))))

(def unstable-blind-index
  "A provider whose tag changes per call. Refused at construction, because a
  tag that is never equal to itself makes every redelivered write a first
  write — on the ledger, a second payment."
  (let [n (atom 0)]
    (reify blind/BlindIndex
      (tag [_ _ _ _]
        {:index/status :ok :index/tag (str "tag-" (swap! n inc))})
      (describe [_] {:index/scheme :test-only-unstable
                     :index/configured? true
                     :index/key-id "unstable"}))))

(def constant-blind-index
  "A provider whose tag ignores its arguments. Refused at construction: it
  would collapse two different contracts into one registration."
  (reify blind/BlindIndex
    (tag [_ _ _ _] {:index/status :ok :index/tag "always-the-same"})
    (describe [_] {:index/scheme :test-only-constant
                   :index/configured? true
                   :index/key-id "constant"})))

;; ---------------------------------------------------------------------------
;; The transport
;; ---------------------------------------------------------------------------

(defn fake-transport
  "An in-memory kotobase.

  `opts`:
    :lose-ack  an atom holding a count. While positive, `cas-head!` APPLIES
               the change and then answers `:conflict` with the value it just
               installed — the lost acknowledgement, which is the case where
               a naive retry writes a payroll record twice.
    :fail-cas  an atom holding a count. While positive, `cas-head!` answers
               `:conflict` WITHOUT applying, which is what a real second
               writer looks like."
  ([] (fake-transport {}))
  ([{:keys [lose-ack fail-cas] :as opts}]
   (let [state (atom {})]
     (with-meta
       (reify transport/Transport
         (put-block! [_ tenant cid block-bytes]
           (swap! state assoc-in [tenant :blocks cid] (vec block-bytes))
           {:block/status :ok :block/cid cid})
         (get-block [_ tenant cid]
           (if-let [b (get-in @state [tenant :blocks cid])]
             {:block/status :ok :block/bytes b}
             {:block/status :missing}))
         (read-head [_ tenant ref]
           {:head/status :ok :head/cid (get-in @state [tenant :heads ref])})
         (cas-head! [_ tenant ref expected proposed]
           ;; `fail-cas` is checked BEFORE the swap and refuses without
           ;; applying — which is what another writer having moved the head
           ;; actually looks like. Checking it after would only fire when a
           ;; race happened to occur, and a test whose injected fault depends
           ;; on a race is a test that measures the race.
           (if (and fail-cas (pos? @fail-cas))
             (do (swap! fail-cas dec)
                 {:cas/status :conflict
                  :cas/actual (get-in @state [tenant :heads ref])})
             (let [applied (atom nil)]
               (swap! state
                      (fn [st]
                        (if (= expected (get-in st [tenant :heads ref]))
                          (do (reset! applied true)
                              (assoc-in st [tenant :heads ref] proposed))
                          (do (reset! applied false) st))))
               (cond
                 (and @applied lose-ack (pos? @lose-ack))
                 (do (swap! lose-ack dec)
                     {:cas/status :conflict :cas/actual proposed})

                 @applied {:cas/status :ok}

                 :else {:cas/status :conflict
                        :cas/actual (get-in @state [tenant :heads ref])}))))
         (describe [_]
           ;; `:transport/durable? false`, and that is not a shortcoming of
           ;; the fake — it is the truth about it, and it is what makes a
           ;; host started over this fake report `残らない` on the console.
           ;; The reconstruction property is measured separately, by building
           ;; a second store over the same transport.
           {:transport/kind :fake-in-memory
            :transport/durable? false
            :transport/endpoint "memory://payroll-test"
            :transport/why "試験専用。network に到達せず、プロセスより長生きしない"}))
       {::state state ::opts opts}))))

(defn declaring-durable
  "The same fake, wrapping `t`, whose `describe` says `:transport/durable?
  true`.

  Every operation delegates, so nothing about the storage changes — what
  changes is the CLAIM. It exists because two different things read that
  claim and neither can be exercised without a transport that makes it:
  `payroll.host.config/durability` reports it, and `payroll.cutover/evaluate`
  refuses to pass the gate without it.

  It is a separate constructor rather than an option on `fake-transport`
  precisely so that the plain fake keeps telling the truth about itself. A
  flag that made the default fake claim durability would make every other
  test in this suite run against a transport that lies."
  [t]
  (with-meta
    (reify transport/Transport
      (put-block! [_ tenant cid bs] (transport/put-block! t tenant cid bs))
      (get-block [_ tenant cid] (transport/get-block t tenant cid))
      (read-head [_ tenant ref] (transport/read-head t tenant ref))
      (cas-head! [_ tenant ref expected proposed]
        (transport/cas-head! t tenant ref expected proposed))
      (describe [_]
        (assoc (transport/describe t)
               :transport/durable? true
               :transport/why (str "試験専用。耐久であると宣言する transport を"
                                   "読む側を測るためのものであって、"
                                   "実際に耐久なわけではない"))))
    (meta t)))

(defn state-of [t] (::state (meta t)))

(defn corrupt-block!
  "Delete one block, so a chain can be walked into a hole. The instrument for
  `an unreadable chain is not an empty one`."
  [t tenant cid]
  (swap! (state-of t) update-in [tenant :blocks] dissoc cid))

(defn tamper-block!
  "Replace one block's BYTES while leaving it stored under its original CID —
  what a node serving altered content looks like from the client side.

  Distinct from `corrupt-block!` on purpose: a deleted block is a
  replication problem and an altered one is a trust problem, and a store
  that reported them identically would have nothing to say to the operator
  about which one happened."
  [t tenant cid new-bytes]
  (swap! (state-of t) assoc-in [tenant :blocks cid] (vec new-bytes)))

(defn blocks-of [t tenant] (get-in @(state-of t) [tenant :blocks]))

(defn config
  "A complete durable configuration over one fake transport."
  ([t] (config t {}))
  ([t overrides]
   (merge {:transport t
           :tenant "tenant-test"
           :envelope (reversible-envelope)
           :blind-index (keyed-blind-index)
           :auth (fn [] :a-credential-this-repository-never-reads)
           :cas? true}
          overrides)))

(defn store!
  "A built `KotobaseStore`, or a throw naming what was missing. Tests that
  want the refusal call `payroll.store.kotobase/store` directly."
  ([t] (store! t {}))
  ([t overrides]
   (let [r ((requiring-resolve 'payroll.store.kotobase/store) (config t overrides))]
     (if (= :ok (:store/status r))
       (:store/store r)
       (throw (ex-info (:store/why r) {:missing (mapv :required/key
                                                      (:store/missing r))}))))))
