(ns payroll.phase2-test
  "The slice added in phase 2, measured.

  One file rather than ten, deliberately: every assertion here is about a
  capability `docs/maturity.md` had a row for and could not tick, so keeping
  them together makes the table and the suite readable against each other.

  Each `deftest` name is the claim. Where a claim is about an ABSENCE — the
  withholding table is not transcribed, no input produces a year-end amount —
  the test walks a range rather than checking one input, because a refusal
  that only fires for the value somebody typed is not a refusal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.gensen :as gensen]
            [payroll.artifact.zengin :as zengin]
            [payroll.cutover :as cutover]
            [payroll.digest :as digest]
            [payroll.fixtures :as f]
            [payroll.host.config :as config]
            [payroll.host.jvm :as host]
            [payroll.juminzei :as juminzei]
            [payroll.kotobase.blind-index :as blind]
            [payroll.kotobase.envelope :as envelope]
            [payroll.kotobase.http :as http]
            [payroll.kotobase.fake :as fake]
            [payroll.kotobase.transport :as transport]
            [payroll.edge.endpoints :as api]
            [payroll.mf.import :as mf]
            [payroll.operations :as ops]
            [payroll.rates.monthly-2026 :as nta]
            [payroll.mf.reconcile :as recon]
            [payroll.projection.catalog :as catalog]
            [payroll.projection.r2 :as r2]
            [payroll.projection.schema :as pschema]
            [payroll.rates :as rates]
            [payroll.sensitive :as sensitive]
            [payroll.store :as store]
            [payroll.store.kotobase :as kotobase]
            [payroll.touroku :as touroku]
            [payroll.warimashi :as warimashi])
  (:import (java.nio.charset Charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Content addressing — against the published vectors, never against itself
;; ---------------------------------------------------------------------------

(deftest sha256-agrees-with-the-published-vectors
  (testing "a hash that agrees with nothing but its own previous output is a
            hash that will disagree with the node"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (digest/sha256-hex "")))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (digest/sha256-hex "abc")))
    (is (= "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
           (digest/sha256-hex (str "abcdbcdecdefdefgefghfghighijhijkijkljklmklmn"
                                   "lmnomnopnopq"))))
    (is (= "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
           (digest/sha256-hex (apply str (repeat 1000000 "a")))))))

(deftest utf8-round-trips-including-outside-the-basic-plane
  (doseq [s ["" "abc" "日本語" "ｶﾜｻｷ ｼﾞﾛｳ" "𝄞 surrogate pair"]]
    (is (= s (digest/utf8-string (digest/utf8-bytes s))) s)))

(deftest a-cid-decodes-back-to-the-documented-prefix-and-digest
  (testing "checked by decoding rather than by comparing to a remembered
            base32 string — the assembly is what could be wrong"
    (let [bs (digest/utf8-bytes "hello world")
          c (digest/cid bs)
          decoded (digest/base32-decode (subs c 1))]
      (is (str/starts-with? c "b"))
      (is (= digest/cid-prefix (vec (take 4 decoded))))
      (is (= (digest/sha256 bs) (vec (drop 4 decoded))))
      (is (digest/cid? c))
      (is (not (digest/cid? "bafkrei-not-a-cid")))
      (testing "and the same bytes have the same address"
        (is (= c (digest/cid (digest/utf8-bytes "hello world"))))
        (is (not= c (digest/cid (digest/utf8-bytes "hello worlds"))))))))

;; ---------------------------------------------------------------------------
;; The durable store
;; ---------------------------------------------------------------------------

(deftest a-second-store-reconstructs-what-the-first-wrote
  (testing "durable means: an independently constructed store over the same
            transport reads back the same records IN ORDER"
    (let [t (fake/fake-transport)
          a (fake/store! t)]
      (store/register-client! a (f/employer))
      (store/register-contract! a (f/contract))
      (store/append-ledger! a {:client-id f/employer-id :disposition :hold})
      (store/append-ledger! a {:client-id f/employer-id :disposition :commit})
      (store/commit-record! a {:client-id f/employer-id :op :draft-payroll-run})
      (let [b (fake/store! t)]
        (is (= (f/employer) (store/client b f/employer-id)))
        (is (= (f/contract) (store/contract-of b f/contract-id)))
        (is (= [:hold :commit] (mapv :disposition (store/ledger b))))
        (is (= 1 (count (store/records-of b f/employer-id))))
        (is (true? (:store/readable? (kotobase/health b))))))))

(deftest a-payload-is-sealed-and-the-block-is-not-readable-without-the-envelope
  (testing "the ledger is not written in the clear"
    (let [t (fake/fake-transport)
          s (fake/store! t)]
      (store/append-ledger! s {:client-id f/employer-id :worker "従業員甲"
                               :disposition :commit})
      (let [blocks (vals (get-in @(fake/state-of t) ["tenant-test" :blocks]))
            texts (keep digest/utf8-string blocks)]
        (is (seq texts))
        (is (not-any? #(str/includes? % "従業員甲") texts)
            "a payload block that can be read as text is a plaintext ledger")
        (testing "and a store with a different key REFUSES rather than
                  reporting an empty ledger"
          (let [other (fake/store! t {:envelope (fake/reversible-envelope "k2")})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"kotobase read refused"
                                  (store/ledger other)))
            (is (false? (:store/readable? (kotobase/health other))))))))))

(deftest one-tenant-cannot-read-another-even-given-the-cid
  (testing "a content address is derivable from the content, so an unscoped
            block plane leaks whatever can be guessed"
    (let [t (fake/fake-transport)
          a (fake/store! t {:tenant "tenant-a"})
          b (fake/store! t {:tenant "tenant-b"})]
      (store/register-client! a (f/employer))
      (is (some? (store/client a f/employer-id)))
      (is (nil? (store/client b f/employer-id)))
      (let [cid (ffirst (get-in @(fake/state-of t) ["tenant-a" :blocks]))]
        (is (= :missing (:block/status (transport/get-block t "tenant-b" cid))))))))

(deftest two-writers-racing-for-one-head-both-land
  (testing "a lost compare-and-set means somebody else moved the head, so the
            sequence number this write claimed is no longer the next one"
    (let [fail (atom 1)
          t (fake/fake-transport {:fail-cas fail})
          a (fake/store! t)
          b (fake/store! t)]
      (store/append-ledger! a {:client-id f/employer-id :n 1})
      (store/append-ledger! b {:client-id f/employer-id :n 2})
      (is (zero? @fail) "the injected conflict was actually reached")
      (is (= [1 2] (mapv :n (store/ledger a)))))))

(deftest a-lost-acknowledgement-does-not-write-twice
  (testing "the CAS was applied and the answer did not arrive. Writing again
            is how a :commit becomes a second payment"
    (let [lost (atom 1)
          t (fake/fake-transport {:lose-ack lost})
          s (fake/store! t)]
      (store/append-ledger! s {:client-id f/employer-id :disposition :commit})
      (is (zero? @lost))
      (is (= 1 (count (store/ledger s)))))))

(deftest re-registering-identical-content-is-one-registration
  (testing "and a CHANGED contract is a correction rather than a fork"
    (let [t (fake/fake-transport)
          s (fake/store! t)]
      (dotimes [_ 3] (store/register-contract! s (f/contract)))
      (is (= 1 (count (:chain/entries
                       (kotobase/reconstruct t "tenant-test"
                                             (fake/reversible-envelope)
                                             :contracts)))))
      (store/register-contract! s (f/contract {:contract/role "経理"}))
      (is (= "経理" (:contract/role (store/contract-of s f/contract-id)))))))

(deftest an-unreadable-chain-is-not-an-empty-one
  (testing "fewer entries is exactly what an empty store looks like, and on a
            payroll ledger those are opposite answers"
    (let [t (fake/fake-transport)
          s (fake/store! t)]
      (store/append-ledger! s {:client-id f/employer-id :n 1})
      (store/append-ledger! s {:client-id f/employer-id :n 2})
      (let [cid (ffirst (get-in @(fake/state-of t) ["tenant-test" :blocks]))]
        (fake/corrupt-block! t "tenant-test" cid))
      (let [c (kotobase/reconstruct t "tenant-test" (fake/reversible-envelope)
                                    :ledger)]
        (is (false? (:chain/complete? c)))
        (is (seq (:chain/why c)))
        (is (false? (:store/readable? (kotobase/health s))))))))

(deftest a-complete-chain-reads-and-an-incomplete-one-refuses
  (testing "the four states a reconstruction can be in, and that the reader
            distinguishes them — a control that has to answer differently in
            each is the only kind that shows the check is live"
    (let [t (fake/fake-transport)
          s (fake/store! t)
          env (fake/reversible-envelope)]
      (store/append-ledger! s {:client-id f/employer-id :n 1})
      (store/append-ledger! s {:client-id f/employer-id :n 2})

      (testing "COMPLETE — every block present and openable, so the read
                returns and health says readable"
        (let [c (kotobase/reconstruct t "tenant-test" env :ledger)]
          (is (true? (:chain/complete? c)))
          (is (empty? (:chain/broken c)))
          (is (= [1 2] (mapv :n (:chain/entries c))))
          (is (= [1 2] (mapv :n (store/ledger s))))
          (is (true? (:store/readable? (kotobase/health s))))
          (is (false? (:store/entries-are-a-floor? (kotobase/health s))))))

      (testing "MISSING BLOCK — the node no longer has one block. The read
                REFUSES; returning the survivors would render a lost payroll
                record as a record that was never filed"
        (let [t2 (fake/fake-transport)
              s2 (fake/store! t2)]
          (store/append-ledger! s2 {:client-id f/employer-id :n 1})
          (store/append-ledger! s2 {:client-id f/employer-id :n 2})
          (let [victim (ffirst (fake/blocks-of t2 "tenant-test"))]
            (fake/corrupt-block! t2 "tenant-test" victim))
          (let [c (kotobase/reconstruct t2 "tenant-test" env :ledger)
                h (kotobase/health s2)]
            (is (false? (:chain/complete? c)))
            (is (seq (:chain/broken c)))
            (is (contains? #{:missing :tampered}
                           (:broken/kind (first (:chain/broken c)))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"kotobase read refused: ledger"
                                  (store/ledger s2)))
            (testing "and the refusal carries the structured break, never a payload"
              (let [d (try (store/ledger s2) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                (is (= :ledger (:read/stream d)))
                (is (seq (:read/broken d)))
                (is (not (str/includes? (pr-str d) f/employer-id)))))
            (testing "but health still ANSWERS — the operator asking what is
                      wrong is asking during the outage"
              (is (false? (:store/readable? h)))
              (is (true? (:store/entries-are-a-floor? h)))
              (is (seq (:store/break-kinds h)))))))

      (testing "TAMPERED BLOCK — the node serves bytes that are not the CID it
                was asked for. Without re-deriving the address this parses as
                EDN and walks as a chain node, and the read reports success"
        (let [t3 (fake/fake-transport)
              s3 (fake/store! t3)]
          (store/append-ledger! s3 {:client-id f/employer-id :n 1})
          (let [victim (ffirst (fake/blocks-of t3 "tenant-test"))]
            (fake/tamper-block! t3 "tenant-test" victim
                                (digest/utf8-bytes (pr-str {:not :the-block}))))
          (let [c (kotobase/reconstruct t3 "tenant-test" env :ledger)]
            (is (false? (:chain/complete? c)))
            (is (= [:tampered] (mapv :broken/kind (:chain/broken c))))
            (is (str/includes? (:chain/why c) "内容アドレス"))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"kotobase read refused"
                                  (store/ledger s3)))
            (is (= [:tampered] (:store/break-kinds (kotobase/health s3)))))))

      (testing "BAD ENVELOPE — every block is present and addresses correctly,
                and the payload will not open. A DIFFERENT break from a missing
                block, because the operator's next action is different"
        (let [t4 (fake/fake-transport)
              s4 (fake/store! t4)]
          (store/append-ledger! s4 {:client-id f/employer-id :n 1})
          (let [c (kotobase/reconstruct t4 "tenant-test"
                                        (fake/reversible-envelope "other-key")
                                        :ledger)]
            (is (false? (:chain/complete? c)))
            (is (= [:envelope] (mapv :broken/kind (:chain/broken c))))
            (is (empty? (:chain/entries c))))))

      (testing "and an APPEND against a chain that cannot be walked is refused
                rather than performed — appending without being able to check
                idempotency writes the second payment"
        (let [t5 (fake/fake-transport)
              s5 (fake/store! t5)]
          (store/append-ledger! s5 {:client-id f/employer-id :n 1})
          (let [victim (ffirst (fake/blocks-of t5 "tenant-test"))]
            (fake/tamper-block! t5 "tenant-test" victim [0 1 2 3]))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"kotobase append refused"
                                (store/append-ledger!
                                 s5 {:client-id f/employer-id :n 2}))))))))

(deftest the-idempotency-tag-is-keyed-and-is-not-a-confirmation-oracle
  (testing "the tags travel in the CLEAR on every node. An unkeyed hash over a
            guessable key (emp-1, c-tanaka) lets anybody who can read the chain
            confirm that an employee is on this payroll, without opening one
            sealed payload"
    (let [t (fake/fake-transport)
          s (fake/store! t)]
      (store/register-contract! s (f/contract))
      (let [tags (into #{} (keep :node/idempotency)
                       (:chain/nodes (kotobase/reconstruct
                                      t "tenant-test"
                                      (fake/reversible-envelope) :contracts)))
            unkeyed (digest/sha256-hex
                     (str "tenant-test|contracts|"
                          (pr-str [f/contract-id])))]
        (is (= 1 (count tags)))
        (is (not (contains? tags unkeyed))
            "the tag must not be reproducible without the provider's key")
        (testing "and no tag on the node is the plain hash of anything a
                  guesser can construct from the contract id alone"
          (is (not-any? #(str/includes? % f/contract-id) tags))))))

  (testing "a provider keyed differently produces different tags, so a store
            cannot recognise another deployment's writes"
    (let [a (blind/tag (fake/keyed-blind-index "k1" "s1") "t" :contracts ["c-1"])
          b (blind/tag (fake/keyed-blind-index "k2" "s2") "t" :contracts ["c-1"])]
      (is (= :ok (:index/status a)))
      (is (not= (:index/tag a) (:index/tag b)))))

  (testing "and the store REFUSES to build on a provider that is unstable,
            constant, absent, or keyed by the envelope's own secret"
    (let [t (fake/fake-transport)]
      (doseq [[label bi]
              [["absent" nil]
               ["refusing" blind/refusing-blind-index]
               ["unstable — every redelivery would be a first write"
                fake/unstable-blind-index]
               ["constant — two contracts would be one registration"
                fake/constant-blind-index]
               ["keyed by the envelope's own key id"
                (fake/keyed-blind-index "test-key-1" "s")]]]
        (let [r (kotobase/store (assoc (fake/config t) :blind-index bi))]
          (is (= :refused (:store/status r)) label)
          (is (some #(= :blind-index (:required/key %)) (:store/missing r))
              label)))
      (testing "and one object serving as both envelope and blind index is one
                secret wearing two names"
        (is (false? (blind/distinct-from-envelope?
                     (fake/keyed-blind-index "test-key-1" "s")
                     (fake/reversible-envelope "test-key-1"))))
        (is (true? (blind/distinct-from-envelope?
                    (fake/keyed-blind-index) (fake/reversible-envelope))))
        (is (= :separate (blind/key-separation (fake/keyed-blind-index)
                                               (fake/reversible-envelope))))
        (is (= :same (blind/key-separation
                      (fake/keyed-blind-index "test-key-1" "s")
                      (fake/reversible-envelope "test-key-1"))))
        (testing "a provider publishing no key id is UNKNOWN and never :separate"
          (let [quiet (reify blind/BlindIndex
                        (tag [_ _ _ _] {:index/status :ok :index/tag "t"})
                        (describe [_] {:index/scheme :quiet}))]
            (is (= :unknown (blind/key-separation quiet
                                                  (fake/reversible-envelope))))))))))

(deftest health-stays-safe-and-answers-while-every-read-refuses
  (testing "health is the one surface that must keep working during the outage
            it is describing, and it must still carry nothing sensitive"
    (let [t (fake/fake-transport)
          s (fake/store! t)]
      ;; Not digits: `payroll.fixtures-test` refuses a plausible account
      ;; number anywhere under `test/`, and this needs a value that is
      ;; unmistakable in a `str/includes?` rather than one that is realistic.
      (store/append-ledger! s {:client-id f/employer-id
                               :bank/account-number "ACCT-MUST-NOT-APPEAR"
                               :n 1})
      (let [victim (ffirst (fake/blocks-of t "tenant-test"))]
        (fake/tamper-block! t "tenant-test" victim [255 254 253]))
      (let [h (kotobase/health s)]
        (is (map? h))
        (is (false? (:store/readable? h)))
        (is (empty? (sensitive/log-violations h)))
        (is (not (str/includes? (pr-str h) "ACCT-MUST-NOT-APPEAR")))
        (is (= :separate (:store/key-separation h)))
        (is (blind/describes-safely? (fake/keyed-blind-index)))
        (is (blind/describes-safely? blind/refusing-blind-index))))))

(deftest a-store-scoped-to-one-employer-refuses-anothers-record
  (let [t (fake/fake-transport)
        s (fake/store! t {:employers #{f/employer-id}})]
    (is (some? (store/register-client! s (f/employer))))
    (is (thrown? Exception
                 (store/register-client! s {:client-id "emp-other" :name "X"})))))

(deftest a-durable-store-refuses-to-be-built-without-any-of-the-six
  (let [t (fake/fake-transport)]
    (is (= :ok (:store/status (kotobase/store (fake/config t)))))
    (doseq [[k v] {:transport nil :tenant "" :envelope envelope/refusing-envelope
                   :auth nil :cas? false
                   :blind-index blind/refusing-blind-index}]
      (let [r (kotobase/store (assoc (fake/config t) k v))]
        (is (= :refused (:store/status r)) k)
        (is (seq (:store/missing r)) k)))
    (testing "and the refusal names every gap, not the first one"
      (is (= 6 (count (:store/missing (kotobase/store {})))))
      (is (= (mapv :required/key kotobase/required)
             (mapv :required/key (:store/missing (kotobase/store {}))))))))

(deftest nothing-a-transport-or-an-envelope-describes-may-be-logged
  (let [t (fake/fake-transport)]
    (is (transport/describes-safely? t))
    (is (envelope/describes-safely? (fake/reversible-envelope)))
    (is (envelope/describes-safely? envelope/refusing-envelope))
    (is (empty? (sensitive/log-violations (kotobase/health (fake/store! t)))))))

;; ---------------------------------------------------------------------------
;; Host configuration — fail-closed, and never carrying a secret
;; ---------------------------------------------------------------------------

(def ^:private durable-env
  {"PAYROLL_STORE" "kotobase" "PAYROLL_ALLOWLIST" f/allowlist-string
   "PAYROLL_AUTH" "trusted-header" "PAYROLL_DID_HEADER" "X-Verified-DID"
   "PAYROLL_PORT" "0" "PAYROLL_BIND" "127.0.0.1"
   "PAYROLL_KOTOBASE_ENDPOINT" "https://kotobase.example/"
   "PAYROLL_KOTOBASE_TENANT" "tenant-test"
   "PAYROLL_KOTOBASE_AUTH" "keychain:payroll-kotobase"
   "PAYROLL_ENCRYPTION" "provider:age-x25519"
   "PAYROLL_BLIND_INDEX" "keychain:payroll-blind-index"
   "PAYROLL_KOTOBASE_CAS" "yes"})

(deftest durable-mode-refuses-on-each-of-the-six-separately
  (is (= :ok (:config/status (config/read-config durable-env))))
  (doseq [{:requirement/keys [env]} config/durable-requirements]
    (let [r (config/read-config (dissoc durable-env env))]
      (is (= :refused (:config/status r)) env)
      (is (str/includes? (:config/why r) env) env)))
  (testing "the CAS variable is an ACKNOWLEDGEMENT and not a value"
    (doseq [bad ["true" "1" "YES" "cas"]]
      (is (= :refused (:config/status
                       (config/read-config (assoc durable-env
                                                  "PAYROLL_KOTOBASE_CAS" bad))))
          bad)))
  (testing "and naming ONE provider for both the envelope and the blind index
            is refused — the two secrets have different lifetimes and
            different exposure, so they cannot be one secret"
    (let [r (config/read-config (assoc durable-env
                                       "PAYROLL_BLIND_INDEX" "provider:age-x25519"))]
      (is (= :refused (:config/status r)))
      (is (str/includes? (:config/why r) "PAYROLL_BLIND_INDEX"))
      (is (str/includes? (:config/hint r) "回転")))
    (testing "while two different names are accepted and reported as separate"
      (let [c (config/read-config durable-env)]
        (is (= :ok (:config/status c)))
        (is (true? (get-in c [:config/kotobase :kotobase/keys-are-separate?])))
        (is (= "keychain:payroll-blind-index"
               (get-in c [:config/kotobase :kotobase/blind-index-provider])))))))

(deftest the-configuration-never-carries-a-token
  (let [secret "tok-DO-NOT-LOG-9f3a"
        c (config/read-config (assoc durable-env "PAYROLL_KOTOBASE_TOKEN" secret
                                     "R2_CATALOG_TOKEN" secret))]
    (is (not (str/includes? (pr-str c) secret)))
    (is (not (str/includes? (pr-str (config/health c)) secret)))
    (is (not (str/includes? (pr-str (r2/read-config {"R2_CATALOG_URI" "https://x"
                                                     "R2_WAREHOUSE" "acc_b"
                                                     "R2_CATALOG_TOKEN" secret}))
                            secret)))))

(deftest the-durable-host-refuses-to-start-without-an-injected-store
  (testing "a durable store needs a transport, an encryption provider and a
            scoped credential, none of which is a string an environment
            variable carries"
    (let [r (host/start! durable-env)]
      (is (= :refused (:host/status r)))
      (is (str/includes? (:host/why r) "kotobase")))))

(deftest the-kotobase-host-reports-the-transports-durability-and-not-its-own
  (let [t (fake/fake-transport)
        srv (host/start! durable-env {:store (fake/store! t)})]
    (try
      (is (= :started (:host/status srv)))
      (testing "the fake transport is in memory and says so, so the host says
                the ledger does NOT survive — the reconstruction is a
                different claim and is measured separately"
        (is (false? (get-in srv [:host/durability :store/survives-process-restart?])))
        (is (true? (get-in srv [:host/durability :store/reconstructs?]))))
      (finally ((:host/stop! srv)))))
  (testing "and a transport that declares durability moves the claim"
    (is (true? (:store/survives-process-restart?
                (config/durability :kotobase {:transport-durable? true}))))
    (is (false? (:store/survives-process-restart?
                 (config/durability :kotobase {}))))))

;; ---------------------------------------------------------------------------
;; 全銀 — the bytes
;; ---------------------------------------------------------------------------

(def ^:private origin
  {:zengin/origin-name-kana "ｶｸｳｼﾖｳｼﾞ(ｶ"
   :zengin/origin-branch-code "001"
   :zengin/origin-account-number "1234567"
   :zengin/transfer-date-mmdd "0825"})

(defn- prepared [& [{:keys [employer contract]}]]
  (zengin/prepare {:employer (merge (f/employer) origin employer)
                   :period f/period
                   :runs [{:contract (f/contract (or contract {}))
                           :meisai (f/lines {:verdict (f/verdict-for)})}]}))

(deftest every-zengin-record-is-120-bytes-in-shift-jis
  (testing "every permitted character is one byte in Shift_JIS, so a
            120-CHARACTER record is a 120-BYTE record — measured by encoding
            rather than asserted"
    (let [sjis (Charset/forName "Shift_JIS")
          text (zengin/->fixed-width (prepared))]
      (is (some? text))
      (is (str/ends-with? text "\r\n"))
      (let [records (remove str/blank? (str/split text #"\r\n"))]
        (is (= 4 (count records)))
        (doseq [r records]
          (is (= 120 (count r)) r)
          (is (= 120 (alength (.getBytes ^String r sjis))) r))
        (testing "and the fixed values are where the specification puts them"
          (is (str/starts-with? (nth records 0) "1210"))
          (is (str/starts-with? (nth records 1) "2"))
          (is (str/includes? (nth records 0) zengin/paypay-bank-code))
          (is (str/starts-with? (nth records 2) "8"))
          (is (str/starts-with? (nth records 3) "9")))))))

(deftest the-shift-jis-encoder-agrees-with-the-platform-on-every-permitted-character
  (testing "the encoder is two comparisons and an offset, and every one of its
            answers is cross-checked against the JVM's own Charset. A
            transcription that is not measured against the platform is a
            transcription"
    (let [sjis (Charset/forName "Shift_JIS")]
      (is (seq zengin/permitted-characters))
      (is (<= 70 (count zengin/permitted-characters))
          "the evidence floor: an empty permitted set would pass this loop")
      (doseq [ch zengin/permitted-characters]
        (let [ours (zengin/shift-jis-byte ch)
              theirs (.getBytes (str ch) sjis)]
          (is (some? ours) (str ch " has no single-byte encoding here"))
          (is (= 1 (alength theirs)) (str ch " is not one byte on the platform"))
          (is (= ours (bit-and (aget theirs 0) 0xFF))
              (str ch ": " ours " vs " (bit-and (aget theirs 0) 0xFF))))))
    (testing "and a character outside the two ranges has no answer here"
      (doseq [ch [\あ \漢 \Ａ \１ \ー \￥ \¥]]
        (is (nil? (zengin/shift-jis-byte ch)) (str ch))))))

(deftest a-character-with-no-single-byte-encoding-refuses-and-is-never-substituted
  (testing "String.getBytes answers `?` for a character the charset cannot
            represent, silently, and the record stays the right length — so
            the one failure this namespace exists to prevent would pass every
            length check"
    (let [r (zengin/->shift-jis-bytes "ﾀﾅｶ漢ﾀﾛｳ")]
      (is (= :refused (:bytes/status r)))
      (is (nil? (:bytes/bytes r)))
      (is (= 1 (count (:bytes/problems r))))
      (is (= 3 (:position (first (:bytes/problems r))))
          "the position is reported, because a 40-character name with one wrong
           kana in the middle is otherwise a hunt")
      (is (= \漢 (:char (first (:bytes/problems r))))))
    (testing "and the platform would NOT have refused — it fails in two
              different silent ways, and this encoder refuses in both"
      (let [sjis (Charset/forName "Shift_JIS")]
        (testing "a fullwidth character Shift_JIS CAN represent becomes TWO
                  bytes, so a 120-character record silently becomes 121"
          (is (= 8 (alength (.getBytes "ﾀﾅｶ漢ﾀﾛｳ" sjis))))
          (is (= 7 (count "ﾀﾅｶ漢ﾀﾛｳ"))
              "the character count still says 7 — which is why a check on
               characters cannot catch this"))
        (testing "and one it CANNOT represent is substituted with 0x3F `?`,
                  keeping the length right and changing the name"
          (let [subbed (.getBytes "ﾀﾅｶ€ﾀﾛｳ" sjis)]
            (is (= 7 (alength subbed)))
            (is (= 0x3F (bit-and (aget subbed 3) 0xFF)))))))
    (testing "both are refused here, and each names its own character"
      (doseq [[s* pos] {"ﾀﾅｶ漢ﾀﾛｳ" 3 "ﾀﾅｶ€ﾀﾛｳ" 3}]
        (let [r (zengin/->shift-jis-bytes s*)]
          (is (= :refused (:bytes/status r)) s*)
          (is (= pos (:position (first (:bytes/problems r)))) s*))))
  (testing "ENCODABLE and PERMITTED are two different questions, and this
            encoder answers only the first"
    (testing "a small kana IS one byte in Shift_JIS, so the encoder accepts it"
      (is (= :ok (:bytes/status (zengin/->shift-jis-bytes "ｼﾝｼﾞｭｸ")))))
    (testing "and the LAYOUT refuses it, quoting the bank's own rule — which is
              the check that has to catch it, because a file of the right
              length carrying the wrong name is the failure this namespace is
              organised against"
      (is (false? (zengin/permitted? "ｼﾝｼﾞｭｸ")))
      (is (str/includes? (:why (first (zengin/character-problems "ｼﾝｼﾞｭｸ")))
                         "小文字のカナ"))
      (testing "so a payee whose name has one reaches no bytes at all"
        (let [p (prepared {:contract {:bank/payee-name-kana "ｼﾝｼﾞｭｸ ﾀﾛｳ"}})]
          (is (= :refused (:zengin/status p)))
          (is (= :refused (:file/status (zengin/->fixed-width-bytes p)))))))))
  (testing "a clean string encodes to exactly one byte per character"
    (let [r (zengin/->shift-jis-bytes "ﾀﾅｶ ﾀﾛｳ")]
      (is (= :ok (:bytes/status r)))
      (is (= 7 (:bytes/count r)))
      (is (= [0xC0 0xC5 0xB6 0x20 0xC0 0xDB 0xB3] (:bytes/bytes r))))))

(deftest the-fixed-width-file-is-exactly-120-bytes-per-record-plus-crlf
  (testing "measured as BYTES, per record and in total. Both, because they
            fail differently: one over-long record is a field registered
            wrong, and a total that does not match the sum is a terminator
            that went missing or doubled"
    (let [p (prepared)
          f* (zengin/->fixed-width-bytes p)
          sjis (Charset/forName "Shift_JIS")]
      (is (= :ok (:file/status f*)) (:file/why f*))
      (is (= "Shift_JIS" (:file/encoding f*)))
      (is (= :crlf (:file/line-terminator f*)))
      (is (= 4 (count (:file/records f*))) "header, one data, trailer, end")
      (doseq [r (:file/records f*)]
        (is (= 120 (:record/bytes r)) (:record/label r)))
      (testing "and the whole file is records × (120 + CRLF)"
        (is (= (* 4 122) (count (:file/bytes f*)))))
      (testing "every byte is in range and every record ends with 0x0D 0x0A"
        (is (every? #(and (>= % 0) (<= % 255)) (:file/bytes f*)))
        (doseq [i (range 4)]
          (is (= [0x0D 0x0A]
                 (subvec (:file/bytes f*) (+ (* i 122) 120) (+ (* i 122) 122)))
              (str "record " i))))
      (testing "and the bytes are BYTE-IDENTICAL to encoding the string form
                with the platform's Shift_JIS — the property that makes the
                pure encoder safe to use"
        (is (= (vec (map #(bit-and % 0xFF)
                         (.getBytes ^String (zengin/->fixed-width p) sjis)))
               (:file/bytes f*))))
      (testing "and re-encoding those same bytes as UTF-8 would NOT be 120 per
                record — the mistake the host is wired against"
        (is (< (* 4 122)
               (alength (.getBytes ^String (zengin/->fixed-width p)
                                   StandardCharsets/UTF_8))))))))

(deftest the-download-carries-the-charset-and-a-filename
  (let [d (zengin/download (prepared))]
    (is (= :ok (:download/status d)))
    (is (= "text/plain; charset=Shift_JIS" (:download/content-type d)))
    (is (= "furikomi-zengin.txt" (:download/filename d)))
    (is (vector? (:download/bytes d)))
    (is (= (* 4 122) (count (:download/bytes d))))
    (testing "and a refused preparation produces no bytes at all"
      (let [bad (zengin/download (prepared {:employer {:zengin/origin-name-kana nil}}))]
        (is (= :refused (:download/status bad)))
        (is (nil? (:download/bytes bad)))))))

(deftest the-host-writes-the-bytes-verbatim-and-does-not-re-encode-them
  (testing "handing the bytes back as a string and letting the host
            getBytes(UTF_8) turns every halfwidth katakana into THREE bytes,
            so a 120-byte record becomes anything up to 360 — and the file
            still looks right in a terminal"
    (let [d (zengin/download (prepared))
          written (vec (map #(bit-and % 0xFF) (host/->response-bytes
                                               (:download/bytes d))))]
      (is (= (:download/bytes d) written))
      (is (= (* 4 122) (count written))))
    (testing "while a String body is still UTF-8, which is what every other
              route on this host wants"
      (is (= [0xE6 0x97 0xA5]
             (vec (map #(bit-and % 0xFF) (host/->response-bytes "日")))))
      (is (= 0 (alength (host/->response-bytes nil)))))
    (testing "and a byte value above 127 survives the round trip rather than
              throwing or wrapping to a negative"
      (is (= [0xC0 0xDF]
             (vec (map #(bit-and % 0xFF)
                       (host/->response-bytes [0xC0 0xDF]))))))))

(deftest the-transcribed-widths-sum-to-the-record-length
  (doseq [[k r] zengin/layout]
    (is (= (:record/length r) (reduce + (map :f/width (:record/fields r)))) k))
  (is (= 118 (reduce + (map :f/width (:record/fields zengin/csv-trailer))))))

(deftest a-small-kana-a-long-vowel-and-a-middle-dot-are-refused-not-substituted
  (testing "the specification gives the substitutions; making them would be a
            decision about somebody's account name"
    (doseq [[name* fragment] {"ｼﾝｼﾞｭｸ" "小文字のカナ"
                              "ﾈﾂﾄｰ" "長音"
                              "ﾈﾂﾄ･ｾﾝﾀｰ" "中黒点"
                              "カワサキ" "半角ではない"}]
      (let [p (prepared {:contract {:bank/payee-name-kana name*}})]
        (is (= :refused (:zengin/status p)) name*)
        (is (str/includes? (pr-str p) fragment) name*)
        (is (nil? (zengin/->fixed-width p)) "no partial file is emitted")))))

(deftest a-missing-origin-field-refuses-the-whole-file
  (doseq [k [:zengin/origin-name-kana :zengin/origin-branch-code
             :zengin/origin-account-number :zengin/transfer-date-mmdd]]
    (let [p (zengin/prepare {:employer (dissoc (merge (f/employer) origin) k)
                             :period f/period
                             :runs [{:contract (f/contract)
                                     :meisai (f/lines {:verdict (f/verdict-for)})}]})]
      (is (= :refused (:zengin/status p)) k)
      (is (some #(= k (:missing/key %)) (:zengin/origin-missing p)) k))))

(deftest a-zero-amount-line-is-a-record-and-is-counted
  (testing "「振込金額を0円で入力している箇所の振り込みは実行されません。
            合計件数は0円のデータを含めた件数を入力してください」— so a zero
            amount renders as a record (ten zeroes, right-justified) and the
            count includes it while the total does not.

            Asserted on the RECORD rather than end-to-end, because a payable
            run whose net is zero would need a zero-rate contract and
            `kotoba.labor/validate-contract` is what decides whether that is
            a contract at all — a question this test is not about."
    (let [line (zengin/render-record
                (:data zengin/layout)
                (zengin/payee-values {:bank-code "0033" :branch-code "001"
                                      :account-type-key :ordinary
                                      :account-number "1234567"
                                      :payee-name-kana "ｱｲｳ" :amount 0}))
          trailer (zengin/render-record (:trailer zengin/layout)
                                        {:total/count 1 :total/amount 0})]
      (is (= :ok (:record/status line)))
      (is (= 120 (count (:record/text line))))
      (is (str/includes? (:record/text line) "0000000000")
          "振込金額 is ten zeroes rather than an omitted record")
      (is (= :ok (:record/status trailer)))
      (is (str/starts-with? (:record/text trailer) "8000001000000000000")
          "合計件数 1 と 合計金額 0 —— 件数には含め、金額には含めない"))))

(deftest the-csv-variant-refuses-a-comma-the-fixed-width-one-accepts
  (testing "a comma is a permitted 全銀 character and a CSV field separator"
    (is (nil? (:field/problem (zengin/render-field
                               {:f/name "受取人名" :f/width 30 :f/kind :text
                                :f/source :payee/name-kana}
                               {:payee/name-kana "ｱ,ｲ"}))))
    (is (some? (:record/problems
                (zengin/render-csv-record
                 {:record/label "t" :record/length 30
                  :record/fields [{:f/name "受取人名" :f/width 30 :f/kind :text
                                   :f/source :payee/name-kana}]}
                 {:payee/name-kana "ｱ,ｲ"}))))))

(deftest the-csv-variant-emits-the-documented-field-counts
  (let [p (prepared)
        rows (str/split-lines (zengin/->csv p))]
    (is (= 4 (count rows)))
    (is (= 13 (count (str/split (nth rows 0) #"," -1))))
    (is (= 16 (count (str/split (nth rows 1) #"," -1))))
    (is (= 4 (count (str/split (nth rows 2) #"," -1))))
    (is (= 2 (count (str/split (nth rows 3) #"," -1))))
    (testing "and the sample's extra trailing comma is recorded rather than
              silently matched or silently ignored"
      (is (str/includes? (:discrepancy/what zengin/csv-sample-discrepancy)
                         "読点")))))

(deftest the-zengin-json-carries-what-is-still-unestablished
  (let [j (zengin/->json (prepared))]
    (is (str/includes? j "\"record_length\":120"))
    (is (str/includes? j "Shift_JIS"))
    (is (str/includes? j "paypay-bank.co.jp"))
    (is (str/includes? j "テスト振込は行われていない"))))

(deftest the-jba-source-provenance-matches-what-was-actually-read
  (testing "the URL, hash and byte size of the two pages read, and that
            業務種別「21：総合振込」 survives into the rendered header"
    (is (= "https://www.zenginkyo.or.jp/fileadmin/res/abstract/efforts/system/jba_protocol_pc.pdf"
           (:source/url zengin/jba-source)))
    (is (= "7f6dcca8d291ab7f72dcf7cc56af7efe717246e8c42cebb8789e399287a058bd"
           (:read/sha256 (first (:source/read zengin/jba-source)))))
    (is (= 5421458 (:read/bytes (first (:source/read zengin/jba-source)))))
    (let [header (first (remove str/blank?
                                (str/split (zengin/->fixed-width (prepared)) #"\r\n")))]
      (is (= "21" (subs header 1 3))))))

;; ---------------------------------------------------------------------------
;; 住民税・割増賃金・料率 — arithmetic, and the refusals around it
;; ---------------------------------------------------------------------------

(deftest a-resident-tax-month-comes-from-a-notice-and-never-from-a-rate
  (let [n f/resident-tax-notice]
    (is (= f/resident-tax (:juminzei/amount
                           (juminzei/assess {:period "2026-08" :notices [n]
                                             :obligation :special-collection}))))
    (testing "the collection year runs 6月→翌年5月, so 翌年3月 is the same notice"
      (is (= :notified (:juminzei/answer
                        (juminzei/assess {:period "2027-03" :notices [n]
                                          :obligation :special-collection})))))
    (testing "and every absence is its own refusal, never a zero"
      (doseq [[args expected]
              {{:period "2026-08" :notices [] :obligation :special-collection} :no-notice
               {:period "2027-06" :notices [n] :obligation :special-collection} :notice-year-mismatch
               {:period "2026-08" :notices [n]} :municipality-not-declared
               {:period "2026/08" :notices [n] :obligation :special-collection} :malformed-notice}]
        (let [a (juminzei/assess args)]
          (is (= expected (:juminzei/answer a)) (pr-str args))
          (is (nil? (:juminzei/amount a)))
          (is (contains? juminzei/refusals (:juminzei/answer a))))))))

(deftest the-obligation-is-registerable-and-admits-exactly-three-states
  (testing "the field `payroll.edge.console/run-of` has always read. Until it
            was added to `payroll.touroku/contract-fields`, `unknown-keys`
            refused every body carrying it — so `:obligation` was nil for
            every contract that had ever been registered, `assess` answered
            `:municipality-not-declared`, and the 住民税 line was held even
            for an employer whose twelve months were fully registered"
    (let [admit (fn [v] (touroku/admit-contract
                         f/employer-id
                         (cond-> {:contract/id "c-1" :contract/worker "甲"
                                  :contract/wage-type :monthly
                                  :contract/rate 280000}
                           (not= ::absent v)
                           (assoc :employment/resident-tax-obligation v))))]
      (testing "the two registered answers, and absence"
        (doseq [v [:special-collection :not-special-collection ::absent nil]]
          (is (= :ok (:touroku/status (admit v))) (pr-str v))))
      (testing "and nothing else — not the strings the browser posts, not a
                boolean, not a third classification somebody invented"
        (doseq [v ["special-collection" "not-special-collection" :tokubetsu
                   true false 1 "" :special_collection]]
          (is (= :refused (:touroku/status (admit v))) (pr-str v))))
      (testing "absence writes no key. A registered nil and an absent key
                answer the same today, and only the absent one is honest
                about nobody having classified this employee"
        (is (not (contains? (:touroku/record (admit ::absent))
                            :employment/resident-tax-obligation)))
        (is (= :special-collection
               (:employment/resident-tax-obligation
                (:touroku/record (admit :special-collection)))))))))

(deftest a-decision-notice-missing-one-month-is-refused
  (let [r (juminzei/admit-notice
           f/employer-id
           (assoc f/resident-tax-notice-as-transcribed
                  :notice/months (into {} (for [k (butlast juminzei/month-keys)]
                                            [k 8200]))))]
    (is (= :refused (:notice/status r)))
    (is (str/includes? (:notice/why r) "未登録は零ではない")))
  (testing "and a revision needs the month it starts from"
    (is (= :refused (:notice/status
                     (juminzei/admit-notice
                      f/employer-id
                      (assoc f/resident-tax-notice-revised-as-transcribed
                             :notice/effective-from nil
                             :notice/months {:juminzei/m10 9000})))))))

(deftest the-annual-total-refuses-over-an-incomplete-year
  (let [full (juminzei/employee-summary {:contract-id f/contract-id
                                         :notices [f/resident-tax-notice]
                                         :obligation :special-collection
                                         :tax-year "2026"})]
    (is (true? (:summary/complete? full)))
    (is (= (* 12 f/resident-tax) (:summary/annual-total full))))
  (let [none (juminzei/employee-summary {:contract-id f/contract-id :notices []
                                         :obligation :special-collection
                                         :tax-year "2026"})]
    (is (false? (:summary/complete? none)))
    (is (nil? (:summary/annual-total none)))))

(deftest overtime-is-priced-from-read-rates-and-never-inferred-from-a-total
  (let [c {:contract/wage-type :monthly :contract/rate 280000
           :employment/monthly-scheduled-hours 160
           :allowance/commuting 10000}
        p (warimashi/price {:contract c :rounding :round/half-up
                            :hours {:hours/statutory-overtime 10
                                    :hours/statutory-holiday 8
                                    :hours/over-60-late-night 2}})
        line (fn [k] (first (filter #(= k (:line/category %)) (:warimashi/lines p))))]
    (is (= :priced (:warimashi/answer p)))
    ;; (280000 - 10000) / 160 = 1687.5/h
    (is (= 5/4 (:line/rate (line :overtime))))
    (is (= 21094 (:line/yen (line :overtime))))       ; 1687.5 × 1.25 × 10
    (is (= 27/20 (:line/rate (line :statutory-holiday))))
    (is (= 18225 (:line/yen (line :statutory-holiday))))
    (is (= 7/4 (:line/rate (line :over-60-late-night))))
    (is (= (+ 21094 18225 5906) (:warimashi/total-yen p)))
    (testing "the one combination no read source quotes says so"
      (is (= :addition-of-two-read-rates (:line/derived-by (line :over-60-late-night))))
      (is (nil? (:line/derived-by (line :overtime)))))
    (testing "and a total refuses rather than being split into categories"
      (is (= :uncategorised-hours
             (:warimashi/answer (warimashi/price {:contract c :rounding :round/half-up
                                                  :hours {:hours/total 48}})))))))

(deftest every-overtime-refusal-is-its-own-and-none-of-them-defaults
  (let [c {:contract/wage-type :monthly :contract/rate 280000
           :employment/monthly-scheduled-hours 160}
        h {:hours/statutory-overtime 10}]
    (is (= :rounding-policy-not-registered
           (:warimashi/answer (warimashi/price {:contract c :hours h}))))
    (is (= :monthly-hours-not-registered
           (:warimashi/answer (warimashi/price
                               {:contract (dissoc c :employment/monthly-scheduled-hours)
                                :hours h :rounding :round/half-up}))))
    (is (= :rate-below-statutory-minimum
           (:warimashi/answer (warimashi/price {:contract c :hours h
                                                :rounding :round/half-up
                                                :employer-rates {:overtime 1.1}}))))
    (testing "a rate ABOVE the minimum is a lawful 就業規則 and is used"
      (is (= 3/2 (:line/rate (first (:warimashi/lines
                                     (warimashi/price {:contract c :hours h
                                                       :rounding :round/half-up
                                                       :employer-rates {:overtime 3/2}})))))))))

(deftest the-transcribed-rates-compute-an-employee-share-and-nothing-else-does
  (is (= 13790 (:share/yen (rates/employee-share
                            {:jurisdiction [:jp] :scheme :scheme/health-insurance
                             :month "2026-07" :prefecture "東京" :base 280000
                             :rounding :round/floor-at-half}))))
  (is (= 25620 (:share/yen (rates/employee-share
                            {:jurisdiction [:jp] :scheme :scheme/employees-pension
                             :month "2026-07" :base 280000
                             :rounding :round/floor-at-half}))))
  (testing "雇用保険 is NOT half — 徴収法 第三十一条第一項第一号"
    (let [e (rates/employee-share {:jurisdiction [:jp]
                                   :scheme :scheme/employment-insurance
                                   :month "2026-07" :base 280000
                                   :rounding :round/floor-at-half})]
      (is (= 1400 (:share/yen e)))
      (is (= :not-half (:share/split e)))
      (is (not= (:share/rate e) (/ (:share/total-rate e) 2)))))
  (testing "Tokyo is not generalised, and an unregistered 支部 is not Tokyo"
    (is (= :prefecture-not-registered
           (:rate/answer (rates/lookup {:jurisdiction [:jp]
                                        :scheme :scheme/health-insurance
                                        :month "2026-07"}))))
    (is (= :prefecture-not-transcribed
           (:rate/answer (rates/lookup {:jurisdiction [:jp]
                                        :scheme :scheme/health-insurance
                                        :month "2026-07" :prefecture "大阪"})))))
  (testing "and a month outside every transcribed window refuses"
    (is (= :month-not-covered
           (:rate/answer (rates/lookup {:jurisdiction [:jp]
                                        :scheme :scheme/health-insurance
                                        :month "2020-01" :prefecture "東京"}))))))

(deftest the-withholding-table-is-transcribed-whole-and-refuses-only-the-rounding
  (testing "the table is transcribed, and it is transcribed WHOLE — the
            objection that made an empty table honest was that a subset
            answers for the salaries somebody happened to type"
    (is (true? (:table/transcribed? rates/withholding-table)))
    (is (= 231 (count (:table/bands rates/withholding-table))))
    (is (= 9 (count (:table/thresholds rates/withholding-table))))
    (is (= 9 (count (:table/kou-segments rates/withholding-table))))
    (is (= 2 (count (:table/otsu-segments rates/withholding-table))))
    (is (= 1610 (:table/dependants-beyond-7-deduction rates/withholding-table))))

  (testing "the bands leave no gap and no overlap between the sub-minimum
            floor, the 231 rows and the first printed threshold — a table
            with a hole in it answers the salaries either side of the hole"
    (let [bands (:table/bands rates/withholding-table)]
      (is (= (:band/to (:table/sub-minimum rates/withholding-table))
             (:band/from (first bands))))
      (is (= (mapv :band/from (rest bands)) (mapv :band/to (butlast bands))))
      (is (= (:band/to (last bands))
             (:threshold/at (first (:table/thresholds rates/withholding-table)))))
      (is (every? #(= 8 (count (:band/kou %))) bands))))

  (testing "every amount in the banded range is ANSWERED, in both columns,
            and no amount anywhere reaches `:band-not-transcribed`"
    (doseq [amount (range 105000 740000 1013)
            dependants (range 0 5)]
      (let [k (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column :kou :dependants dependants})
            o (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column :otsu})]
        (is (= :ok (:withhold/status k)) (str amount "/" dependants))
        (is (integer? (:withhold/yen k)) (str amount "/" dependants))
        (is (= :ok (:withhold/status o)) (str amount))
        (is (integer? (:withhold/yen o)) (str amount))))
    (doseq [amount (concat (range 0 900000 997) (range 700000 4000000 9973))
            column [:kou :otsu]]
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column column :dependants 0})]
        (is (not= :band-not-transcribed (:withhold/answer r))
            (str amount "/" column)))))

  (testing "the figures are the workbook's, checked against rows read off the
            printed page rather than recomputed from the same data"
    (is (= 170 (:withhold/yen (rates/withhold
                               {:month "2026-08" :taxable-remuneration 105000
                                :column :kou :dependants 0}))))
    (is (= 3800 (:withhold/yen (rates/withhold
                                {:month "2026-08" :taxable-remuneration 105000
                                 :column :otsu}))))
    (is (= 71380 (:withhold/yen (rates/withhold
                                 {:month "2026-08" :taxable-remuneration 739999
                                  :column :kou :dependants 0}))))
    (is (= 71680 (:withhold/yen (rates/withhold
                                 {:month "2026-08" :taxable-remuneration 740000
                                  :column :kou :dependants 0}))))
    (is (= 259200 (:withhold/yen (rates/withhold
                                  {:month "2026-08" :taxable-remuneration 740000
                                   :column :otsu}))))
    (is (= 655400 (:withhold/yen (rates/withhold
                                  {:month "2026-08"
                                   :taxable-remuneration 1710000
                                   :column :otsu})))))

  (testing "740,000円 is the first threshold row and NOT the last band —
            `:band/to` is 未満, and reading it as 以下 would print 71,380
            where the workbook prints 71,680"
    (is (nil? (rates/lookup-band rates/withholding-table 740000)))
    (is (some? (rates/lookup-band rates/withholding-table 739999)))
    (is (= :threshold (get-in (rates/withhold
                               {:month "2026-08" :taxable-remuneration 740000
                                :column :kou :dependants 0})
                              [:withhold/row :row/kind]))))

  (testing "甲欄 below 105,000円 is ZERO, which is an answer and not an absence"
    (doseq [amount (range 0 105000 991) dependants (range 0 8)]
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column :kou :dependants dependants})]
        (is (= :ok (:withhold/status r)) (str amount "/" dependants))
        (is (= 0 (:withhold/yen r)) (str amount "/" dependants)))))

  (testing "扶養親族等 8人以上 subtracts 1,610円 per person beyond seven from
            the 7人 column, and floors at zero rather than going negative"
    (let [at (fn [amount dependants]
               (:withhold/yen (rates/withhold
                               {:month "2026-08" :taxable-remuneration amount
                                :column :kou :dependants dependants})))]
      ;; 700,000〜703,000 の行: 7人 = 19,050
      (is (= 19050 (at 700000 7)))
      (is (= (- 19050 1610) (at 700000 8)))
      (is (= (- 19050 (* 3 1610)) (at 700000 10))))
    (doseq [amount (range 105000 740000 4001) dependants (range 8 40)]
      (let [y (:withhold/yen (rates/withhold
                              {:month "2026-08" :taxable-remuneration amount
                               :column :kou :dependants dependants}))]
        (is (integer? y) (str amount "/" dependants))
        (is (not (neg? y)) (str amount "/" dependants)))))

  (testing "the two segments where the workbook prints a RATE and not an
            amount refuse the ROUNDING and carry the exact rational — and
            they refuse for every amount in the segment, including the ones
            that happen to divide evenly"
    (doseq [amount (range 740001 4000000 7919)]
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column :kou :dependants 0})]
        (when-not (some #(= amount (:threshold/at %))
                        (:table/thresholds rates/withholding-table))
          (is (= :refused (:withhold/status r)) (str amount))
          (is (= :rounding-not-transcribed (:withhold/answer r)) (str amount))
          (is (nil? (:withhold/yen r)) (str amount))
          ;; `number?` and NOT `float?` — the invariant is that the carried
          ;; value is exact, not that it happens to be a Ratio. Asserting
          ;; `ratio?` would pass only for as long as no amount in this sweep
          ;; divides evenly, which is a property of the step size and not of
          ;; the calculator.
          (is (number? (:withhold/exact r)) (str amount))
          (is (not (float? (:withhold/exact r))) (str amount))
          (is (pos? (:withhold/exact r)) (str amount))
          (is (seq (:withhold/basis r)) (str amount)))))
    (doseq [amount (range 0 105000 991)]
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration amount
                               :column :otsu})]
        (is (= :refused (:withhold/status r)) (str amount))
        (is (= :rounding-not-transcribed (:withhold/answer r)) (str amount))
        (is (nil? (:withhold/yen r)) (str amount))
        (is (not (float? (:withhold/exact r))) (str amount))
        (is (= (* amount 3063/100000) (:withhold/exact r)) (str amount))))
    (testing "including 100,000円, where 3.063% is exactly 3,063円 — answering
              the amounts that divide evenly and refusing the rest is the same
              failure as a partially transcribed table"
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration 100000
                               :column :otsu})]
        (is (= :rounding-not-transcribed (:withhold/answer r)))
        (is (= 3063 (:withhold/exact r)))))
    (testing "the exact value is the segment's own arithmetic — base plus the
              printed rate applied to the excess"
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration 740001
                               :column :kou :dependants 0})]
        (is (= (+ 71680 (* 1021/5000 1)) (:withhold/exact r))))
      (let [r (rates/withhold {:month "2026-08" :taxable-remuneration 960000
                               :column :otsu})]
        (is (= (+ 259200 (* 1021/2500 220000)) (:withhold/exact r))))))

  (testing "and each missing input is still its own refusal, checked BEFORE
            the table is reached — transcribing the rows did not weaken any
            of the input gates"
    (is (= :column-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08"
                                              :taxable-remuneration 250000}))))
    (is (= :column-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08" :column :hei
                                              :taxable-remuneration 250000}))))
    (is (= :dependants-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08" :column :kou
                                              :taxable-remuneration 250000}))))
    (is (= :dependants-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08" :column :kou
                                              :dependants -1
                                              :taxable-remuneration 250000}))))
    (is (= :remuneration-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08" :column :otsu}))))
    (is (= :remuneration-not-registered
           (:withhold/answer (rates/withhold {:month "2026-08" :column :otsu
                                              :taxable-remuneration -1}))))
    (is (= :year-not-covered
           (:withhold/answer (rates/withhold {:month "2025-08" :column :otsu
                                              :taxable-remuneration 250000})))))

  (testing "an unsupported year stays a deterministic refusal in both
            directions, and no month outside 2026 is answered from the 2026
            table"
    (doseq [month ["2024-12" "2025-01" "2025-12" "2027-01" "2030-06"
                   "2026-13" "2026" "" "八月"]
            column [:kou :otsu]
            amount [0 105000 300000 740000 2000000]]
      (let [r (rates/withhold {:month month :taxable-remuneration amount
                               :column column :dependants 0})]
        (is (= :refused (:withhold/status r)) (str month "/" amount))
        (is (= :year-not-covered (:withhold/answer r)) (str month "/" amount))
        (is (nil? (:withhold/yen r)) (str month "/" amount)))))

  (testing "every refusal this function can produce is declared in
            `withholding-refusals` — an undeclared refusal is one the console
            renders as an unknown"
    (doseq [q [{:month "2026-08" :taxable-remuneration 250000}
               {:month "2026-08" :column :kou :taxable-remuneration 250000}
               {:month "2025-08" :column :otsu :taxable-remuneration 250000}
               {:month "2026-08" :column :otsu}
               {:month "2026-08" :column :kou :dependants 0
                :taxable-remuneration 900000}
               {:month "2026-08" :column :otsu :taxable-remuneration 50000}]]
      (let [r (rates/withhold q)]
        (is (contains? rates/withholding-refusals (:withhold/answer r))
            (pr-str q)))))

  (testing "日額表 and 賞与の算出率の表 are named as NOT transcribed rather
            than approximated from the 月額表"
    (let [gaps (mapv :gap/what (:table/not-transcribed rates/withholding-table))]
      (is (some #(str/includes? % "日額表") gaps))
      (is (some #(str/includes? % "賞与") gaps))
      (is (some #(str/includes? % "端数処理") gaps))
      (is (every? #(seq (:gap/why %))
                  (:table/not-transcribed rates/withholding-table))))))

;; ---------------------------------------------------------------------------
;; 法定調書 — the artifacts, and the amount that stays refused
;; ---------------------------------------------------------------------------

(def ^:private ytd-records
  [{:op :draft-payroll-run :contract-id f/contract-id
    :payload {:period "2026-08" :gross f/gross :income-tax-withheld f/income-tax
              :health-insurance-withheld f/health-insurance
              :employees-pension-withheld f/employees-pension}}])

(def ^:private declared-contract
  {:employment/year-end-declaration-filed? true
   :employment/dependants 0
   :employment/withholding-column :kou
   :employment/my-number "123456789012"
   :employment/address "東京都架空区1-2-3"})

(deftest no-input-produces-a-year-end-amount
  (testing "the year's correct tax comes from 別表第五 and the 速算表, and
            neither is transcribed — including for the grosses that fall
            squarely inside the 月額表, which IS transcribed and would answer
            if this calculator asked it"
    (doseq [gross (range 100000 1200000 100000)
            dependants (range 0 4)]
      (let [ytd (gensen/year-to-date {:records [{:op :draft-payroll-run
                                                 :contract-id f/contract-id
                                                 :payload {:period "2026-01"
                                                           :gross gross
                                                           :income-tax-withheld 1}}]
                                      :contract-id f/contract-id :year "2026"})
            a (gensen/year-end-amount {:contract (assoc declared-contract
                                                        :employment/dependants dependants)
                                       :ytd ytd :year "2026"})]
        (is (= :refused (:amount/status a)) (str gross "/" dependants))
        (is (= :annual-table-not-transcribed (:amount/answer a))
            (str gross "/" dependants))
        (is (nil? (:amount/annual-tax a)))
        (is (nil? (:amount/over-or-under a))))))

  (testing "the hazard, named: the monthly table DOES answer these same
            amounts, and borrowing it would put a plausible 過不足額 on a
            源泉徴収票 an employer actually settles"
    (doseq [gross [200000 400000 739000]]
      (is (= :ok (:withhold/status
                  (rates/withhold {:month "2026-12" :taxable-remuneration gross
                                   :column :kou :dependants 0})))
          (str gross)))
    (is (every? false? (map :table/read? gensen/annual-tables)))
    (is (= 2 (count gensen/annual-tables)))
    (let [ytd (gensen/year-to-date {:records ytd-records
                                    :contract-id f/contract-id :year "2026"})
          a (gensen/year-end-amount {:contract declared-contract :ytd ytd
                                     :year "2026"})]
      (is (= 2 (count (:amount/unread-tables a))))
      (is (str/includes? (:amount/why a) "別表第五"))
      (is (str/includes? (:amount/why a) "年税額の表ではない")))))

(deftest the-guide-provenance-says-which-pages-were-read
  (testing "「この手引を読んだ」 and 「この手引の第1章を読んだ」 are different
            claims, and the second is the true one — so what was opened is
            listed rather than implied by a bare URL"
    (let [src gensen/source
          read* (:source/read src)]
      (is (= "令和8年分" (:source/edition src)))
      (is (= 2 (count read*)))
      (is (some #(str/includes? (:read/what %) "目次") read*))
      (let [ch1 (first (filter #(str/includes? (:read/what %) "第1章") read*))]
        (is (some? ch1))
        (is (= "45640296d54a1a4d17e6dc7bc4fa09b211e2dc08c30ac4795a5fd7fcbc6b5b4c"
               (:read/sha256 ch1)))
        (is (= 64 (count (:read/sha256 ch1))))
        (is (= 856461 (:read/bytes ch1)))
        (is (str/includes? (:read/url ch1) "tebiki2026/PDF/01.pdf")))
      (testing "the PDF is cited and NOT vendored — the bytes are not in this
                repository, and the digest is what lets a later reader check
                they fetched the same document"
        (is (empty? (filter #(str/ends-with? (.getName ^java.io.File %) ".pdf")
                            (file-seq (io/file ".")))))
        (is (= "2026-08-26" (:source/read-at src))))
      (testing "and every chapter whose 様式・記載要領 was NOT read is named,
                because a `not-read` list that stops at 「様式」 does not say
                which of nine chapters it means"
        (doseq [ch ["第2章" "第3章" "第4章" "第5章" "第6章" "第7章"
                    "第8章" "第9章"]]
          (is (some #(str/includes? % ch) (:source/not-read src)) ch))
        (is (some #(str/includes? % "納付書の様式") (:source/not-read src)))))))

(deftest the-submission-rules-are-recorded-as-printed-and-no-date-is-derived
  (testing "第1章's four facts, each with the chapter it came from"
    (let [by-key (into {} (map (juxt :rule/key identity)) gensen/submission-rules)]
      (is (= 5 (count gensen/submission-rules)))
      (is (every? #(str/starts-with? (:rule/chapter %) "第1章")
                  gensen/submission-rules))
      (testing "令和9年2月1日 is what the guide PRINTS. This actor holds no
                calendar and does not re-derive it from 1月31日"
        (is (str/includes? (:rule/text (:deadline by-key)) "令和９年２月１日"))
        (is (str/includes? (:rule/why (:deadline by-key)) "暦を持たない"))
        (is (not (str/includes? (:rule/text (:deadline by-key)) "1月31日"))))
      (testing "the 給与支払報告書 and the 退職所得の特別徴収票 go to the
                市区町村 and not to the 税務署"
        (is (str/includes? (:rule/text (:deadline by-key)) "各市区町村")))
      (testing "four submission methods, and the optical-disc format down to
                the file extension"
        (doseq [m ["e-Tax" "認定クラウド等" "光ディスク等" "書面"]]
          (is (str/includes? (:rule/text (:methods by-key)) m) m))
        (is (str/includes? (:rule/text (:optical-disc-format by-key)) "CSV"))
        (is (str/includes? (:rule/text (:optical-disc-format by-key)) ".txt"))
        (is (str/includes? (:rule/text (:optical-disc-format by-key)) ".zip")))
      (testing "the e-Tax threshold is 30 sheets and is judged PER KIND — a
                single count over everything answers a different question"
        (is (str/includes? (:rule/text (:e-tax-threshold by-key)) "30枚以上"))
        (is (str/includes? (:rule/text (:e-tax-threshold by-key))
                           "法定調書の種類ごと"))
        (is (str/includes? (:rule/why (:e-tax-threshold by-key)) "100枚以上"))))))

(deftest the-deemed-submission-changes-what-a-complete-submission-is
  (testing "第1章 4(1): once the 給与支払報告書 reaches the 市区町村 the
            税務署提出用 源泉徴収票 is DEEMED submitted. It does not make this
            repository able to submit anything — it changes what a complete
            submission IS"
    (let [by-key (into {} (map (juxt :artifact/key identity)) gensen/artifacts)
          gensen* (:gensen-choshu-hyo by-key)
          hokoku (:kyuyo-shiharai-hokokusho by-key)
          deemed (:artifact/deemed-submission gensen*)]
      (is (= :kyuyo-shiharai-hokokusho (:deemed/by deemed)))
      (is (= "令和9年1月1日" (:deemed/from deemed)))
      (is (= "令和8年分以降" (:deemed/applies-to deemed)))
      (is (str/includes? (:deemed/what deemed) "提出したものとみなされる"))
      (is (str/includes? (:deemed/source deemed) "第1章 4(1)"))
      (is (= :gensen-choshu-hyo (:artifact/deems hokoku)))
      (testing "and it did not turn any of them into a statutory form"
        (is (every? #(false? (:artifact/statutory-form? %)) gensen/artifacts))
        (is (= 3 (count gensen/artifacts))))
      (testing "4(4) — the 様式 changed in 令和8年9月 and this repository has
                not read the new one, which makes the flag MORE true"
        (doseq [a [gensen* hokoku]]
          (is (str/includes? (:artifact/why-not-statutory a) "令和8年9月")
              (str (:artifact/key a))))
        (let [form (first (filter #(= :form-layout-changed (:amendment/key %))
                                  gensen/amendments-2026))]
          (is (str/includes? (:amendment/text form) "国税システムの更改"))
          (is (str/includes? (:amendment/why form) "弱くはならない")))))))

(deftest the-2026-amendments-are-recorded-and-two-of-them-are-not-completeness
  (testing "four amendments from 第1章 4, each said once"
    (let [by-key (into {} (map (juxt :amendment/key identity))
                       gensen/amendments-2026)]
      (is (= 4 (count gensen/amendments-2026)))
      (is (every? :amendment/chapter gensen/amendments-2026))
      (is (true? (:amendment/changes-completeness?
                  (:deemed-submission by-key))))
      (testing "the 生命保険料控除の特例 is 23歳未満 and applies to 令和8年分
                and 令和9年分 — the year-end calculation this actor refuses
                would need it even with the tables in hand"
        (let [a (:life-insurance-deduction-under-23 by-key)]
          (is (str/includes? (:amendment/text a) "23歳未満"))
          (is (str/includes? (:amendment/text a) "令和８年分及び令和９年分"))
          (is (false? (:amendment/changes-completeness? a)))))
      (testing "退職所得の源泉徴収票 is a 法定調書 about 退職手当 and is not
                住民税の特別徴収税額の決定通知書 — same word, different document"
        (let [a (:retirement-income-statement by-key)]
          (is (str/includes? (:amendment/text a) "退職手当等"))
          (is (str/includes? (:amendment/why a) "payroll.juminzei"))))
      (testing "and `payroll.juminzei` says the same thing from its own side,
                so a reader who arrives at either namespace is told"
        (is (str/includes? (:doc (meta (find-ns 'payroll.juminzei)))
                           "退職所得の源泉徴収票・特別徴収票"))
        (is (str/includes? (:doc (meta (find-ns 'payroll.juminzei)))
                           "住民税の特別徴収税額の決定通知書"))))))

(deftest having-read-the-guide-did-not-weaken-the-year-end-refusal
  (testing "the guide is read, and every input still refuses with
            `:annual-table-not-transcribed`. A refusal that softened because
            somebody read an adjacent document would be the exact failure the
            reading was supposed to guard against"
    (doseq [gross [0 500000 3000000 12000000 30000000]
            dependants [0 1 3]
            column [:kou :otsu]]
      (let [ytd (gensen/year-to-date
                 {:records [{:op :draft-payroll-run :contract-id f/contract-id
                             :payload {:period "2026-01" :gross gross
                                       :income-tax-withheld 1}}]
                  :contract-id f/contract-id :year "2026"})
            a (gensen/year-end-amount
               {:contract (assoc declared-contract
                                 :employment/dependants dependants
                                 :employment/withholding-column column)
                :ytd ytd :year "2026"})
            label (str gross "/" dependants "/" column)]
        (is (= :refused (:amount/status a)) label)
        (is (= :annual-table-not-transcribed (:amount/answer a)) label)
        (is (nil? (:amount/annual-tax a)) label)
        (is (nil? (:amount/over-or-under a)) label))))

  (testing "and the two tables carry the two NEW measured reasons: this guide
            is not where they will be found, and even a transcribed table
            would not be enough"
    (is (every? false? (map :table/read? gensen/annual-tables)))
    (doseq [t gensen/annual-tables]
      (is (str/includes? (:table/why t) "目次") (:table/label t))
      (is (str/includes? (:table/why t) "生命保険料控除の特例")
          (:table/label t)))
    (is (str/includes? (:table/why (first gensen/annual-tables))
                       "この手引はそれが見つかる場所ではない"))))

(deftest an-incomplete-declaration-refuses-before-the-table-is-reached
  (doseq [k [:employment/year-end-declaration-filed? :employment/dependants
             :employment/withholding-column]]
    (let [ytd (gensen/year-to-date {:records ytd-records
                                    :contract-id f/contract-id :year "2026"})
          a (gensen/year-end-amount {:contract (dissoc declared-contract k)
                                     :ytd ytd :year "2026"})]
      (is (= :declaration-incomplete (:amount/answer a)) k))))

(deftest a-preview-never-carries-a-my-number-and-a-submission-refuses-without-one
  (let [ytd (gensen/year-to-date {:records ytd-records
                                  :contract-id f/contract-id :year "2026"})
        amount (gensen/year-end-amount {:contract declared-contract :ytd ytd
                                        :year "2026"})
        p (gensen/preview {:kind :gensen-choshu-hyo :employer (f/employer)
                           :contract declared-contract :ytd ytd :amount amount})]
    (is (= :ok (:preview/status p)))
    (is (false? (:preview/statutory-form? p)))
    (is (not (str/includes? (pr-str p) "123456789012")))
    (is (not (str/includes? (pr-str p) "架空区1-2-3")))
    (is (not (str/includes? (pr-str p) f/worker)))
    (testing "the two figures the table would produce are :unknown, never blank"
      (let [by (into {} (:preview/figures p))]
        (is (= :unknown (:figure/provenance (:annual_tax by))))
        (is (= :unknown (:figure/provenance (:over_or_under by))))
        (is (= :declared (:figure/provenance (:gross by))))))
    (testing "and the JSON says both things about itself"
      (is (str/includes? (gensen/->json p) "\"statutory_form\":false"))
      (is (str/includes? (gensen/->json p) "\"carries_my_number\":false")))
    (testing "a submission carries them and refuses without them"
      (is (= :ok (:export/status (gensen/submission-export
                                  {:kind :gensen-choshu-hyo :employer (f/employer)
                                   :contract declared-contract :ytd ytd
                                   :amount amount}))))
      (doseq [k [:employment/my-number :employment/address]]
        (is (= :refused (:export/status
                         (gensen/submission-export
                          {:kind :gensen-choshu-hyo :employer (f/employer)
                           :contract (dissoc declared-contract k)
                           :ytd ytd :amount amount})))
            k))
      (testing "and what a submission produces must never be logged"
        (is (false? (gensen/loggable?
                     (gensen/submission-export
                      {:kind :gensen-choshu-hyo :employer (f/employer)
                       :contract declared-contract :ytd ytd
                       :amount amount}))))))))

(deftest a-statutory-summary-refuses-over-an-incomplete-employee
  (let [ok (gensen/year-to-date {:records ytd-records :contract-id f/contract-id
                                 :year "2026"})
        none (gensen/year-to-date {:records [] :contract-id "c-2" :year "2026"})]
    (is (true? (:summary/complete? (gensen/statutory-summary
                                    {:employer (f/employer) :year "2026"
                                     :employees [{:ytd ok}]}))))
    (let [s (gensen/statutory-summary {:employer (f/employer) :year "2026"
                                       :employees [{:ytd ok} {:ytd none}]})]
      (is (false? (:summary/complete? s)))
      (is (nil? (:summary/total-gross s)))
      (is (= 1 (count (:summary/incomplete s)))))))

;; ---------------------------------------------------------------------------
;; The R2 projection
;; ---------------------------------------------------------------------------

(defn- fake-catalog
  "An in-memory Iceberg catalog. `:no-keys` makes `existing-keys` refuse,
  which is the driver `project!` must not write to."
  [& [{:keys [no-keys conflicts]}]]
  (let [st (atom {:namespaces #{} :tables {} :rows {}})
        left (atom (or conflicts 0))]
    (reify catalog/Catalog
      (create-namespace! [_ ns*]
        (if (contains? (:namespaces @st) ns*)
          {:catalog/status :exists}
          (do (swap! st update :namespaces conj ns*) {:catalog/status :ok})))
      (create-table! [_ ns* t]
        (let [k [ns* (:table/name t)]]
          (if (get-in @st [:tables k])
            {:catalog/status :exists}
            (do (swap! st assoc-in [:tables k] t) {:catalog/status :ok}))))
      ;; `ks` is INTERSECTED rather than ignored. The protocol asks "which of
      ;; `keys*` does the table already carry", and a fake that answered
      ;; "every key in the table" would let `project!` pass while relying on
      ;; a driver behaviour no real catalog has to have — the table is not
      ;; bounded by the caller's request, and a REST catalog answering the
      ;; whole table for a 100k-row projection is a different query.
      (existing-keys [_ ns* tn kc ks]
        (if no-keys
          {:catalog/status :refused :catalog/why "この driver は既存の鍵を答えられない"}
          (let [asked (set ks)]
            {:catalog/status :ok
             :catalog/keys (into #{} (comp (keep #(get % kc)) (filter asked))
                                 (get-in @st [:rows [ns* tn]]))})))
      (append! [_ ns* tn rows _]
        (if (pos? @left)
          (do (swap! left dec) {:catalog/status :conflict})
          (do (swap! st update-in [:rows [ns* tn]] (fnil into []) rows)
              {:catalog/status :ok :catalog/snapshot "snap-1"})))
      (read-rows [_ ns* tn _]
        (if (get-in @st [:tables [ns* tn]])
          {:catalog/status :ok :catalog/rows (get-in @st [:rows [ns* tn]] [])}
          {:catalog/status :missing :catalog/why "表がまだ作られていない"}))
      (describe [_] {:catalog/kind :fake-in-memory
                     :catalog/endpoint "memory://iceberg-test"}))))

(deftest a-projected-row-refuses-rather-than-dropping-an-identifier
  (testing "a silent drop produces a row that looks de-identified and a caller
            who believes the column was never there"
    (doseq [k [:bank/account-number :employment/my-number :employment/address
               :contract/worker]]
      (let [r (pschema/check-row pschema/run-table {:snapshot_id "s" k "x"})]
        (is (some? r) k)
        (is (seq (:row/forbidden r)) k))))
  (testing "and a column the table does not declare is refused too"
    (is (some? (pschema/check-row pschema/run-table {:invented_column 1})))))

(deftest a-projected-run-row-carries-no-name-and-no-account
  (let [row (:row/value (pschema/project-run
                         {:snapshot-id "snap-1"
                          :entry {:client-id f/employer-id
                                  :contract-id f/contract-id
                                  :period f/period :disposition :commit
                                  :verdict {:violations [{:rule :x :detail "秘密"}]}}
                          :meisai (f/lines {:verdict (f/verdict-for)})
                          :ledger-cid "bafkreitest"}))]
    (is (= f/gross (:gross row)))
    (is (= f/resident-tax (:resident_tax_withheld row)))
    (is (empty? (sensitive/violations row)))
    (is (not (str/includes? (pr-str row) f/worker)))
    (is (not (str/includes? (pr-str row) "秘密"))
        "a violation's :detail carries amounts and registered values")))

(deftest the-projection-is-idempotent-and-a-second-run-appends-nothing
  (let [c (fake-catalog)
        rows [{:snapshot_id "s1" :run_id "r1" :employer_id f/employer-id}]
        _ (catalog/ensure-tables! c)
        go #(catalog/project! {:catalog c :namespace pschema/namespace-name
                               :table (:table/name pschema/run-table)
                               :key-column :run_id :rows rows :snapshot-id "s1"})
        a (go) b (go)]
    (is (= 1 (:project/appended a)))
    (is (= 0 (:project/appended b)))
    (is (= ["r1"] (:project/skipped b)))
    (testing "and an empty projection is NOT reported as a clean one"
      (is (= :nothing-to-do
             (:project/status (catalog/project!
                               {:catalog c :namespace pschema/namespace-name
                                :table (:table/name pschema/run-table)
                                :key-column :run_id :rows [] :snapshot-id "s1"})))))
    (testing "the read-back is what says the rows are there"
      (is (= :ok (:verify/status (catalog/verify-read-back
                                  {:catalog c :namespace pschema/namespace-name
                                   :table (:table/name pschema/run-table)
                                   :key-column :run_id :expected-keys ["r1"]}))))
      (is (= :missing (:verify/status (catalog/verify-read-back
                                       {:catalog c :namespace pschema/namespace-name
                                        :table (:table/name pschema/run-table)
                                        :key-column :run_id
                                        :expected-keys ["r1" "r2"]})))))))

(defn- registered-notice-rows
  "The two notices of a real registration + correction, projected.

  Read back out of the STORE rather than built from the fixtures directly,
  so that what is projected is what was actually persisted — and `:status`
  comes from `payroll.juminzei/effective-notices`, which is the one place
  that decides what is current."
  []
  (let [store (fake/store! (fake/fake-transport))
        reg! (fn [n] (juminzei/register-notice!
                      store {:employer f/employer-id :notice n}))
        _ (reg! f/resident-tax-notice-as-transcribed)
        _ (reg! f/resident-tax-notice-revised-as-transcribed)
        all (store/juminzei-notices store f/employer-id)
        live (into #{} (map :notice/id) (juminzei/effective-notices all))]
    (vec (map-indexed
          (fn [i n]
            (pschema/project-notice
             {:snapshot-id "snap-notices" :notice n :seq i
              :status (if (contains? live (:notice/id n)) :effective :superseded)
              :replaces-id (:notice/replaces n)}))
          all))))

(deftest a-projected-notice-row-refuses-an-undeclared-column-by-name
  (testing "the schema IS the de-identification here: 区市町村, 通知書番号 and
            the twelve 月割額 are absent from `notice-table`, so a row that
            carries one is refused rather than trimmed. A silently dropped
            column produces a row that looks de-identified"
    (doseq [k [:municipality :reference :designated_number
               :juminzei/m06 :annual_total]]
      (let [r (pschema/check-row pschema/notice-table
                                 {:snapshot_id "s" :employer_id "e" k "x"})]
        (is (some? r) (pr-str k))
        (is (empty? (:row/forbidden r)) (pr-str k))
        (testing "and the refusal NAMES the column — `it is wrong` is not an
                  answer an operator or a schema author can act on"
          (is (some #{(name k)} (:row/undeclared r)) (pr-str k))
          (is (str/includes? (:row/why r) (name k)) (pr-str k))
          (is (str/includes? (:row/why r) "表が宣言していない列がある")
              (pr-str k)))))))

(deftest a-projected-notice-row-refuses-a-forbidden-key
  (testing "the same `payroll.sensitive` vocabulary the other two tables use.
            A notice is about one employee's tax, so a name reaching this row
            is the same failure as a name reaching a payroll run's"
    (doseq [k [:contract/worker :employment/my-number :employment/address]]
      (let [r (pschema/check-row pschema/notice-table
                                 {:snapshot_id "s" k "架空 太郎"})]
        (is (some? r) (pr-str k))
        (is (seq (:row/forbidden r)) (pr-str k))
        (is (str/includes? (:row/why r) "落とすのではなく拒否する")
            (pr-str k))))))

(deftest a-notice-projection-over-zero-events-is-not-a-successful-projection
  (testing "an employer who has registered nothing, and one whose notices were
            all filtered out upstream, produce the same empty vector — and
            `:ok` over it would report `the notice history is projected` about
            a projection that looked at nothing"
    (let [c (fake-catalog)]
      (catalog/ensure-tables! c)
      (let [r (catalog/project! {:catalog c :namespace pschema/namespace-name
                                 :table (:table/name pschema/notice-table)
                                 :key-column :notice_event_id
                                 :rows [] :snapshot-id "snap-notices"})]
        (is (= :nothing-to-do (:project/status r)))
        (is (not= :ok (:project/status r)))
        (is (= 0 (:project/appended r)))
        (is (str/includes? (:project/why r) "対象が無かった"))))))

(deftest the-notice-projection-is-idempotent-on-the-event-id
  (testing "a re-run over the same registration history appends nothing, and
            says so as a repeat rather than as a projection over nothing"
    (let [c (fake-catalog)
          rows (mapv :row/value (registered-notice-rows))
          _ (catalog/ensure-tables! c)
          go #(catalog/project! {:catalog c :namespace pschema/namespace-name
                                 :table (:table/name pschema/notice-table)
                                 :key-column :notice_event_id
                                 :rows rows :snapshot-id "snap-notices"})
          a (go) b (go)]
      (is (= 2 (:project/appended a)))
      (is (= :ok (:project/status b)))
      (is (= 0 (:project/appended b)))
      (is (= (mapv :notice_event_id rows) (:project/skipped b)))
      (is (str/includes? (:project/why b) "冪等な再実行"))
      (testing "the event id is unique per registration event without carrying
                a municipality or a reference number"
        (is (= 2 (count (distinct (map :notice_event_id rows)))))
        (doseq [id (map :notice_event_id rows)]
          (is (not (str/includes? id f/municipality)) id)
          (is (not (str/includes? id "R8-0000")) id))))))

(deftest the-projected-notice-rows-carry-no-amount-at-all
  (testing "a real registration and its correction. The 年税額 and every
            月割額 are the employee's tax — the projection answers
            「訂正がいつ何度あったか、いま何年度が有効か」 and not 「いくらか」"
    (let [results (registered-notice-rows)
          rows (mapv :row/value results)
          printed (pr-str rows)]
      (is (= [:ok :ok] (mapv :row/status results)))
      (is (= ["decision" "revision"] (mapv :notice_kind rows)))
      (is (= [0 1] (mapv :revision rows)))
      (is (= ["superseded" "effective"] (mapv :status rows))
          "the replaced 決定通知書 is still a row — the correction history is
           the point of keeping it")
      (is (= [12 12] (mapv :months_registered rows)))
      (is (= [nil "m06"] (mapv :effective_from rows))
          "a month name is not an amount")
      (testing "the correction chain is traversable inside the projection
                without any row carrying an identity"
        (is (= (:notice_id_digest (first rows))
               (:replaces_digest (second rows))))
        (is (nil? (:replaces_digest (first rows))))
        (is (every? #(= 64 (count (:notice_id_digest %))) rows)))
      (testing "no amount appears anywhere in the printed rows"
        (doseq [n [f/resident-tax f/resident-tax-revised
                   (* 12 f/resident-tax) (* 12 f/resident-tax-revised)]]
          (is (not (str/includes? printed (str n)))
              (str n " appears in " printed))))
      (testing "nor the municipality, the reference number or the 指定番号"
        (doseq [x [f/municipality "R8-0000-0000" "R8-0000-0001" "0000000"]]
          (is (not (str/includes? printed x))
              (str x " appears in " printed))))
      (testing "and `payroll.sensitive` finds nothing in any of them"
        (doseq [row rows]
          (is (empty? (sensitive/violations row)) (pr-str row))))
      (testing "the digest is over the notice's identity and NOT over the
                record, which is the line: a digest of the whole notice would
                let somebody who guessed a wage confirm it"
        (is (= (digest/sha256-hex f/resident-tax-notice-id)
               (:notice_id_digest (first rows))))))))

(deftest a-driver-that-cannot-answer-existing-keys-is-not-written-to
  (let [c (fake-catalog {:no-keys true})]
    (catalog/ensure-tables! c)
    (let [r (catalog/project! {:catalog c :namespace pschema/namespace-name
                               :table (:table/name pschema/run-table)
                               :key-column :run_id
                               :rows [{:run_id "r1"}] :snapshot-id "s"})]
      (is (= :refused (:project/status r)))
      (is (str/includes? (:project/why r) "二重")))))

(deftest a-conflict-retries-and-a-permission-refusal-does-not
  (let [c (fake-catalog {:conflicts 2})]
    (catalog/ensure-tables! c)
    (let [r (catalog/project! {:catalog c :namespace pschema/namespace-name
                               :table (:table/name pschema/run-table)
                               :key-column :run_id
                               :rows [{:run_id "r1"}] :snapshot-id "s"})]
      (is (= :ok (:project/status r)))
      (is (= 3 (:project/attempts r)))))
  (testing "the live blocker is a 401 and this repository does not retry it"
    (is (= :http-401 (:blocker/create-table r2/observed-blocker)))
    (is (str/includes? (:blocker/until-then r2/observed-blocker) "再試行しない"))
    (is (= 3 catalog/max-attempts))))

(deftest the-r2-preflight-is-not-ready-and-names-the-missing-permission
  (let [p (r2/preflight {"R2_CATALOG_URI" "https://catalog.example"
                         "R2_WAREHOUSE" "acct_bucket"
                         "R2_CATALOG_TOKEN" "irrelevant"})]
    (is (false? (:preflight/ready? p)))
    (is (= :permission-gap-unresolved (:preflight/reason p)))
    (is (= [:granted :missing] (mapv :permission/observed r2/required-permissions)))
    (is (some #(= :http-401 (:request/observed %)) (:preflight/plan p))))
  (is (= :configuration-missing (:preflight/reason (r2/preflight {})))))

;; ---------------------------------------------------------------------------
;; The cutover gate
;; ---------------------------------------------------------------------------

(def ^:private mf-header
  (str/join "," (map :mf/column
                     @(requiring-resolve 'payroll.mf.schema/columns))))

(defn- mf-file [period]
  (str mf-header "\n"
       (str/join "," ["9001" "従業員甲" period f/gross f/health-insurance
                      f/care-insurance f/employees-pension f/employment-insurance
                      f/income-tax f/resident-tax f/deduction-total f/net])))

(defn- report-for [period]
  (recon/reconcile {:import (mf/parse (mf-file period) [(f/contract)])
                    :ours {[f/contract-id period]
                           (f/lines {:verdict (f/verdict-for)
                                     :juminzei (f/juminzei-assessment period)})}
                    :period period}))

(defn- record! [st period kind reason]
  (cutover/record-cycle! st {:employer f/employer-id :period period
                             :report (report-for period)
                             :approved-by "did:key:zFIXTUREapprover"
                             :approved-at "2026-09-01T00:00:00Z"
                             :month-kind kind :month-reason reason
                             :source-snapshots ["mf-export-1" "bafkreiledger"]}))

(deftest a-cycle-cannot-be-synthesised
  (let [st (store/mem-store)]
    (testing "a report over zero compared runs is not a cycle"
      (is (= :refused (:cycle/status
                       (cutover/admit-cycle
                        {:employer f/employer-id :period "2026-08"
                         :report {:reconcile/compared 0 :reconcile/reconciled? true}
                         :approved-by "a" :approved-at "t"
                         :month-kind :ordinary
                         :source-snapshots ["x"]})))))
    (testing "an exceptional month needs a reason"
      (is (= :refused (:cycle/status (record! st "2026-08" :exceptional nil)))))
    (testing "and an approval needs an actor and a time"
      (is (= :refused (:cycle/status
                       (cutover/admit-cycle
                        {:employer f/employer-id :period "2026-08"
                         :report (report-for "2026-08") :month-kind :ordinary
                         :source-snapshots ["x"]})))))
    (testing "agreement is copied from the report and is never an argument"
      (is (true? (:cycle/reconciled?
                  (:cycle/record (record! st "2026-08" :ordinary nil))))))))

(deftest the-gate-holds-at-each-of-the-six-conditions-and-says-which
  (let [t (fake/declaring-durable (fake/fake-transport))
        st (fake/store! t)
        ev #(cutover/evaluate {:store st :employer f/employer-id
                               :projection-verification {:verify/status :ok
                                                         :verify/why "ok"}})]
    (is (= "0/3" (get-in (ev) [:cutover/progress :progress/text])))
    (record! st "2026-08" :ordinary nil)
    (record! st "2026-09" :ordinary nil)
    (is (= "2/3" (get-in (ev) [:cutover/progress :progress/text]))
        "progress counts the trailing run of consecutive reconciled cycles")
    (record! st "2026-10" :ordinary nil)
    (let [r (ev)]
      (is (= "3/3" (get-in r [:cutover/progress :progress/text])))
      (is (false? (:cutover/passed? r)))
      (is (= [:one-exceptional] (:cutover/held-by r)))
      (is (str/includes? (:cutover/why r) "通常でない月が出るまで")))
    (record! st "2026-11" :exceptional "月の途中の入社")
    (let [r (ev)]
      (is (true? (:cutover/passed? r)))
      (is (empty? (:cutover/held-by r)))
      (is (= :kotobase (get-in r [:cutover/durability :evidence/mode]))))
    (testing "and without the projection read-back it holds again"
      (let [r (cutover/evaluate {:store st :employer f/employer-id})]
        (is (false? (:cutover/passed? r)))
        (is (= [:projection-read-back] (:cutover/held-by r)))))
    (testing "a gap in the months RESETS the run rather than being skipped —
              three good months with a broken one between them are not three
              consecutive cycles"
      (record! st "2027-02" :exceptional "退職")
      (is (= 1 (get-in (ev) [:cutover/progress :progress/reconciled])))
      (is (false? (:cutover/passed? (ev))))
      (is (= [:three-consecutive :durable-read-back] (:cutover/held-by (ev)))))))

(deftest the-gate-refuses-a-memstore-holding-a-complete-set-of-cycles
  (testing "four cycles, one of them exceptional, no unknown columns and a
            verified projection — every condition except durability is met,
            and it must still not pass. The whole evidence for switching off
            the incumbent would be inside a process that ends when it ends"
    (doseq [[label st] [["MemStore" (store/mem-store)]
                        ["DatomicStore" (store/datomic-store)]]]
      (record! st "2026-08" :ordinary nil)
      (record! st "2026-09" :ordinary nil)
      (record! st "2026-10" :ordinary nil)
      (record! st "2026-11" :exceptional "月の途中の入社")
      (let [r (cutover/evaluate {:store st :employer f/employer-id
                                 :projection-verification {:verify/status :ok
                                                           :verify/why "ok"}})]
        (is (= "3/3" (get-in r [:cutover/progress :progress/text])) label)
        (is (false? (:cutover/passed? r)) label)
        (is (= [:durable-store :durable-read-back] (:cutover/held-by r)) label)
        (is (nil? (:cutover/durability r)) label)
        (is (str/includes? (:cutover/why r) "最初の再起動で消える証拠") label)
        (testing "and the three content conditions DID pass, so the refusal is
                  about durability and not about the evidence"
          (is (= #{:three-consecutive :one-exceptional :no-unknown-values
                   :projection-read-back}
                 (into #{} (comp (filter :gate/met?) (map :gate/key))
                       (:cutover/conditions r)))
              label))))))

(deftest the-gate-refuses-a-durable-store-whose-transport-does-not-claim-durability
  (testing "the store reconstructs perfectly — that is measured — and the
            transport says the bytes do not outlive the process. The gate
            reads the transport's claim and does not make it on its behalf"
    (let [t (fake/fake-transport)
          st (fake/store! t)]
      (record! st "2026-08" :ordinary nil)
      (record! st "2026-09" :ordinary nil)
      (record! st "2026-10" :exceptional "退職")
      (let [r (cutover/evaluate {:store st :employer f/employer-id
                                 :projection-verification {:verify/status :ok}})]
        (is (false? (:cutover/passed? r)))
        (is (= [:durable-store :durable-read-back] (:cutover/held-by r)))
        (is (str/includes? (:cutover/why r) ":transport/durable? true"))
        (testing "and the evidence still reports that the chains ARE readable
                  — `not durable` and `not readable` are separate facts"
          (is (true? (get-in r [:cutover/durability :evidence/readable?])))
          (is (false? (get-in r [:cutover/durability
                                 :evidence/survives-process-restart?]))))))))

(deftest the-gate-refuses-when-a-chain-cannot-be-read-to-its-end
  (testing "an incomplete read returns FEWER cycles, and fewer cycles is
            exactly what a shorter parallel run looks like. The gate must hold
            rather than counting what came back"
    (let [t (fake/declaring-durable (fake/fake-transport))
          st (fake/store! t)]
      (record! st "2026-08" :ordinary nil)
      (record! st "2026-09" :ordinary nil)
      (record! st "2026-10" :exceptional "退職")
      (let [ok (cutover/evaluate {:store st :employer f/employer-id
                                  :projection-verification {:verify/status :ok}})]
        (is (true? (:cutover/passed? ok))
            "the control: this configuration DOES pass before anything breaks"))
      (testing "a chain OTHER than the cutover one being broken also holds the
                gate. The cycles still read back perfectly — this is the case
                the readability term exists for, and without it the gate would
                pass on a store that has lost its ledger"
        (store/append-ledger! st {:client-id f/employer-id :n 1})
        (let [ledger-head (:chain/head
                           (kotobase/reconstruct t "tenant-test"
                                                 (fake/reversible-envelope)
                                                 :ledger))
              node (:read/value (kotobase/fetch-block t "tenant-test"
                                                      ledger-head))]
          (fake/tamper-block! t "tenant-test" (:node/block node) [9 9 9]))
        (let [r (cutover/evaluate {:store st :employer f/employer-id
                                   :projection-verification {:verify/status :ok}})]
          (is (= 3 (count (store/cutover-cycles st f/employer-id)))
              "the cutover chain itself is intact and reads back")
          (is (false? (:cutover/passed? r)))
          (is (= [:durable-read-back] (:cutover/held-by r)))
          (is (false? (get-in r [:cutover/durability :evidence/readable?])))
          (is (str/includes? (:cutover/why r) "辿れないものがある"))))
      ;; and now break the cutover chain itself
      (let [victim (ffirst (fake/blocks-of t "tenant-test"))]
        (fake/tamper-block! t "tenant-test" victim [7 7 7]))
      (let [r (cutover/evaluate {:store st :employer f/employer-id
                                 :projection-verification {:verify/status :ok}})]
        (is (false? (:cutover/passed? r)))
        (is (contains? (set (:cutover/held-by r)) :durable-read-back))
        (is (false? (get-in r [:cutover/durability :evidence/readable?])))
        (testing "and the gate REPORTS it rather than propagating the throw —
                  the operator asking which condition fails must still get an
                  answer during the outage"
          (is (map? r))
          (is (seq (:cutover/why r))))))))

(deftest cutover-evidence-survives-a-restart-of-the-durable-store
  (let [t (fake/declaring-durable (fake/fake-transport))
        a (fake/store! t)]
    (record! a "2026-08" :ordinary nil)
    (record! a "2026-09" :exceptional "無給休職")
    (let [b (fake/store! t)]
      (is (= ["2026-08" "2026-09"]
             (mapv :cycle/period (store/cutover-cycles b f/employer-id))))
      (is (= 2 (get-in (cutover/evaluate
                        {:store b :employer f/employer-id})
                       [:cutover/progress :progress/reconciled]))
          "the read-back is the store's, not the caller's"))))

;; ---------------------------------------------------------------------------
;; The HTTP transport seam — constructed requests only, never sent
;; ---------------------------------------------------------------------------

(def ^:private http-env
  {"PAYROLL_KOTOBASE_ENDPOINT" "https://kotobase.net/payroll/"
   "PAYROLL_KOTOBASE_TENANT" "tenant-test"})

(defn- recording-send
  "A `send!` that records the request and answers from a canned table.

  **Nothing here reaches a network.** The point of the seam is that the
  request is a VALUE, so what a deployment would put on the wire can be
  asserted without a node — and a suite that opened a socket would be a suite
  that passes or fails on somebody else's uptime."
  [answers]
  (let [seen (atom [])]
    [seen (fn [req] (swap! seen conj req)
            (get answers (:request/op req) {:response/status 200}))]))

(defn- http-config [& [{:keys [answers auth]}]]
  (let [[seen send!] (recording-send (or answers {}))]
    [seen (http/read-config http-env
                            {:auth (or auth (constantly "Bearer FAKE-TOKEN"))
                             :send! send!})]))

(deftest the-http-config-fails-closed-on-every-shape-that-fails-silently
  (let [[_ ok] (http-config)]
    (is (= :ok (:http/status ok)))
    (is (= "https://kotobase.net/payroll" (:http/endpoint ok))
        "the trailing slash is normalised, so path templates do not double it"))
  (testing "each refusal names its own gap, and all of them are reported"
    (doseq [[endpoint fragment]
            {"http://kotobase.net/" "平文で流れる"
             "https://tok@kotobase.net/" "userinfo"
             "https://kotobase.net/?x=1" "query か fragment"
             "kotobase.net" "https の絶対 URL"}]
      (let [r (http/read-config (assoc http-env
                                       "PAYROLL_KOTOBASE_ENDPOINT" endpoint)
                                {:auth (constantly "t") :send! identity})]
        (is (= :refused (:http/status r)) endpoint)
        (is (str/includes? (:http/why r) fragment) endpoint))))
  (testing "and an absent provider or an absent sender is its own gap"
    (let [r (http/read-config {} {})]
      (is (= :refused (:http/status r)))
      (is (= #{:endpoint :tenant :auth :send!}
             (into #{} (map :gap/key) (:http/gaps r)))
          "every gap, not the first — a deployment told `something is wrong`
           fixes one thing and runs again"))))

(deftest a-constructed-request-carries-the-credential-in-a-header-and-nowhere-else
  (let [[_ cfg] (http-config)
        put (http/request-for cfg :put-block {:cid "bafkrei-x" :bytes [1 2 3]})
        get* (http/request-for cfg :get-block {:cid "bafkrei-x"})]
    (is (= :put (:request/method put)))
    (is (= "https://kotobase.net/payroll/ipfs/bafkrei-x" (:request/url put)))
    (is (= [1 2 3] (:request/body-bytes put)))
    (is (= "Bearer FAKE-TOKEN" (get (:request/headers put) "Authorization")))
    (is (= "tenant-test" (get (:request/headers put) "X-Kotobase-Tenant")))
    (testing "the credential is NOT in the URL — a URL reaches access logs,
              proxy logs and error bodies, none of which was chosen by the
              person who chose the secret"
      (is (not (str/includes? (:request/url put) "FAKE-TOKEN")))
      (is (not (str/includes? (:request/url get*) "FAKE-TOKEN"))))
    (testing "and it is not stored on the config either — it is fetched per
              request, so a rotating provider is followed without a restart"
      (is (not (str/includes? (pr-str (dissoc cfg :http/auth :http/send!))
                              "FAKE-TOKEN"))))
    (testing "a provider returning nil produces NO header rather than an empty
              one — the node answers 401 either way, and only one is legible"
      (let [[_ c2] (http-config {:auth (constantly nil)})]
        (is (not (contains? (:request/headers
                             (http/request-for c2 :get-block {:cid "c"}))
                            "Authorization")))))))

(deftest a-compare-and-set-request-always-carries-a-precondition
  (testing "an absent precondition is an unconditional PUT, and an
            unconditional PUT on a head is the last-write-wins that loses one
            of two concurrent runs without a trace"
    (let [[_ cfg] (http-config)
          first* (http/request-for cfg :cas-head {:ref "payroll/t/ledger"
                                                  :expected nil})
          later (http/request-for cfg :cas-head {:ref "payroll/t/ledger"
                                                 :expected "bafkrei-prev"})]
      (is (= "*" (get (:request/headers first*) "If-None-Match"))
          "nil expected means `this ref must not exist yet`")
      (is (not (contains? (:request/headers first*) "If-Match")))
      (is (= "bafkrei-prev" (get (:request/headers later) "If-Match")))
      (is (not (contains? (:request/headers later) "If-None-Match")))
      (testing "and neither carries a body from `request-for` — the proposed
                head is `cas-head!`'s and building an empty one here would
                move a ref to nothing"
        (is (nil? (:request/body-bytes first*)))
        (is (nil? (:request/body-bytes later)))))))

(deftest a-request-is-never-printed-without-being-redacted
  (let [[_ cfg] (http-config)
        req (http/request-for cfg :put-block {:cid "bafkrei-x"
                                              :bytes (digest/utf8-bytes "秘密")})
        r (http/redact req)]
    (is (not (str/includes? (pr-str r) "FAKE-TOKEN")))
    (is (= {:redacted/chars 17} (get (:request/headers r) "Authorization"))
        "the LENGTH survives, because `the provider returned nothing` and `the
         provider returned something` are what an operator debugging a 401
         needs to tell apart")
    (testing "and the body becomes its length and its address, never its bytes
              — a block body is a sealed payroll payload"
      (is (nil? (:request/body-bytes r)))
      (is (= 6 (:request/body-length r)))
      (is (digest/cid? (:request/body-cid r)))
      (is (not (str/includes? (pr-str r) "秘密"))))))

(deftest the-transport-maps-every-status-and-refuses-the-ones-it-cannot-read
  (testing "a deployment writing its own adapter writes `(= 200 status)` and
            treats everything else as missing, which turns a 500 on the ledger
            into an empty ledger"
    (let [[seen cfg] (http-config)
          t (http/transport cfg)]
      (is (= :ok (:block/status (transport/put-block! t "x" "bafk" [1 2]))))
      (is (= 1 (count @seen)) "the injected sender was actually reached")
      (is (transport/describes-safely? t))
      (is (false? (:transport/paths-verified? (transport/describe t))))))

  (testing "a 404 on a head is `no ref yet` and NOT `could not read` —
            collapsing them makes an unreachable node report an employer who
            has filed nothing"
    (let [[_ cfg] (http-config {:answers {:read-head {:response/status 404}}})
          r (transport/read-head (http/transport cfg) "x" "payroll/x/ledger")]
      (is (= :ok (:head/status r)))
      (is (nil? (:head/cid r)))))

  (testing "and every other failing status on a head REFUSES"
    (doseq [st [401 403 500 502 503]]
      (let [[_ cfg] (http-config {:answers {:read-head {:response/status st}}})
            r (transport/read-head (http/transport cfg) "x" "payroll/x/ledger")]
        (is (= :refused (:head/status r)) st)
        (is (str/includes? (:head/why r) (str st)) st))))

  (testing "a 412/409 on a compare-and-set is a CONFLICT carrying the actual
            head, and anything else is a refusal that says the write did not
            happen"
    (doseq [st [409 412]]
      (let [[_ cfg] (http-config
                     {:answers {:cas-head {:response/status st
                                           :response/body-bytes
                                           (digest/utf8-bytes "bafkrei-other")}}})
            r (transport/cas-head! (http/transport cfg) "x" "r" "a" "b")]
        (is (= :conflict (:cas/status r)) st)
        (is (= "bafkrei-other" (:cas/actual r)) st)))
    (let [[_ cfg] (http-config {:answers {:cas-head {:response/status 500}}})
          r (transport/cas-head! (http/transport cfg) "x" "r" "a" "b")]
      (is (= :refused (:cas/status r)))
      (is (str/includes? (:cas/why r) "この書き込みは行われていない"))))

  (testing "a 409 on a BLOCK write is a success — the bytes are the address,
            so the same CID already being there is the same write"
    (let [[_ cfg] (http-config {:answers {:put-block {:response/status 409}}})]
      (is (= :ok (:block/status (transport/put-block!
                                 (http/transport cfg) "x" "bafk" [1]))))))

  (testing "and a sender that could not send at all is a refusal carrying its
            own reason, never a silent miss"
    (let [[_ cfg] (http-config
                   {:answers {:read-head {:response/error "connect timeout"}
                              :put-block {:response/error "connect timeout"}}})
          t (http/transport cfg)]
      (is (= :refused (:head/status (transport/read-head t "x" "r"))))
      (is (= "connect timeout" (:head/why (transport/read-head t "x" "r"))))
      (is (= :refused (:block/status (transport/put-block! t "x" "c" [1])))))))

(deftest the-http-transport-carries-a-real-store-end-to-end-without-a-network
  (testing "the seam is only worth having if a `Store` runs over it. This
            builds one on an in-memory node reached through the HTTP request
            values — every request is CONSTRUCTED and answered locally"
    (let [node (atom {:blocks {} :heads {}})
          send!
          (fn [{:request/keys [op url headers body-bytes]}]
            (let [id (last (str/split url #"/(ipfs|refs)/"))]
              (case op
                :put-block (do (swap! node assoc-in [:blocks id] (vec body-bytes))
                               {:response/status 201})
                :get-block (if-let [b (get-in @node [:blocks id])]
                             {:response/status 200 :response/body-bytes b}
                             {:response/status 404})
                :read-head (if-let [h (get-in @node [:heads id])]
                             {:response/status 200
                              :response/body-bytes (digest/utf8-bytes h)}
                             {:response/status 404})
                :cas-head
                (let [cur (get-in @node [:heads id])
                      want (get headers "If-Match")
                      fresh? (contains? headers "If-None-Match")]
                  (if (or (and fresh? (nil? cur)) (and want (= want cur)))
                    (do (swap! node assoc-in
                               [:heads id] (digest/utf8-string body-bytes))
                        {:response/status 204})
                    {:response/status 412
                     :response/body-bytes (digest/utf8-bytes (str cur))})))))
          cfg (http/read-config http-env {:auth (constantly "Bearer FAKE")
                                          :send! send!})
          t (http/transport cfg)
          s (fake/store! t {:tenant "tenant-test"})]
      (store/register-client! s (f/employer))
      (store/append-ledger! s {:client-id f/employer-id :n 1})
      (store/append-ledger! s {:client-id f/employer-id :n 2})
      (testing "an independently constructed store reads the same records back
                in the same order, through the same request values"
        (let [b (fake/store! t {:tenant "tenant-test"})]
          (is (= f/employer-id (:client-id (store/client b f/employer-id))))
          (is (= [1 2] (mapv :n (store/ledger b))))
          (is (true? (:store/readable? (kotobase/health b))))))
      (testing "and the node holds only ciphertext — no employer id in the clear"
        (is (seq (:blocks @node)))
        (is (not-any? #(str/includes? (str %) f/employer-id)
                      (map digest/utf8-string (vals (:blocks @node))))))
      (testing "the transport's CAS is a real compare-and-set: a stale expected
                head is a conflict and does not move the ref"
        (let [ref* "payroll/tenant-test/ledger"
              before (get-in @node [:heads ref*])]
          (is (= :conflict (:cas/status
                            (transport/cas-head! t "tenant-test" ref*
                                                 "bafkrei-stale" "bafkrei-new"))))
          (is (= before (get-in @node [:heads ref*]))))))))

;; ---------------------------------------------------------------------------
;; The operations surface — one report, two renderings, nothing sensitive
;; ---------------------------------------------------------------------------

(defn- ops-args
  "The report's whole input.

  `:juminzei-notices` is GONE. It was an injected option, nothing outside this
  suite ever supplied it, and so every real deployment rendered
  「決定通知書が一件も登録されていない」 for employers who had registered
  notices — a section whose entire subject is `absence is not zero` reporting
  an absence it had never looked for. The notices are now read off the store
  the report already holds, so a test that wants them registers them."
  [st]
  {:store st :employer f/employer-id
   :reconciliation (report-for "2026-08")
   :store-health (kotobase/health st)
   :projection-health nil :projection-verification nil})

(deftest the-operations-report-answers-every-section-a-non-technical-operator-needs
  (let [t (fake/declaring-durable (fake/fake-transport))
        st (fake/store! t)
        r (ops/report (ops-args st))
        by-key (into {} (map (juxt :section/id identity)) (:report/sections r))]
    (is (= [:resident-tax :overtime :rates :artifacts :moneyforward
            :cutover :store :projection]
           (mapv :section/id (:report/sections r))))

    (testing "住民税 — no notice registered is `not registered` and never zero,
              and the three counts are a REAL zero here because the store
              answered"
      (is (= :not-registered (:section/answer (:resident-tax by-key))))
      (is (= 0 (:section/registered (:resident-tax by-key))))
      (is (= 0 (:section/effective (:resident-tax by-key))))
      (is (= 0 (:section/superseded (:resident-tax by-key))))
      (is (= [] (:section/coverage (:resident-tax by-key))))
      (is (str/includes? (:section/why (:resident-tax by-key))
                         "「住民税ゼロ」ではなく")))

    (testing "割増賃金 — the rates are transcribed and the exclusions are listed"
      (is (seq (:section/categories (:overtime by-key))))
      (is (seq (:section/excluded-allowances (:overtime by-key)))))

    (testing "料率・税額表の版 — each carries the document it came from, the
              report NAMES any source it is displaying without one rather
              than dropping it, and the withholding table reports its band
              count next to the digest of the edition they came from"
      (is (seq (:section/sources (:rates by-key))))
      (is (= [] (:section/sources-without-url (:rates by-key))))
      (is (every? :source/url (:section/sources (:rates by-key))))
      (is (true? (get-in (:rates by-key) [:section/withholding-table
                                          :table/transcribed?])))
      (is (= 231 (get-in (:rates by-key) [:section/withholding-table
                                          :table/bands])))
      (is (= 9 (get-in (:rates by-key) [:section/withholding-table
                                        :table/thresholds])))
      (is (seq (get-in (:rates by-key) [:section/withholding-table
                                        :table/sha256])))
      (is (seq (get-in (:rates by-key) [:section/withholding-table
                                        :table/not-transcribed])))
      (is (contains? (set (:section/refusals (:rates by-key)))
                     :rounding-not-transcribed)))

    (testing "出力できる書類 — none of them claims to be a statutory form"
      (is (false? (:section/any-statutory? (:artifacts by-key)))))

    (testing "MoneyForward — the column count is reported WITH how many are
              verified, because a guess that stops being labelled is a fact"
      (is (pos? (:section/columns (:moneyforward by-key))))
      (is (zero? (:section/columns-verified (:moneyforward by-key))))
      (is (some? (:section/latest (:moneyforward by-key)))))

    (testing "切り替えの条件 — every blocker is named"
      (is (false? (:section/passed? (:cutover by-key))))
      (is (seq (:section/blockers (:cutover by-key))))
      (is (= (count cutover/conditions)
             (count (:section/conditions (:cutover by-key))))))

    (testing "保存先 — readable is reported separately from empty"
      (is (= :readable (:section/answer (:store by-key))))
      (is (false? (:section/entries-are-a-floor? (:store by-key))))
      (is (= :separate (:section/keys-separated (:store by-key)))))

    (testing "投影 — unconfigured is NOT healthy"
      (is (= :not-configured (:section/answer (:projection by-key)))))

    (testing "and the flat blocker list is what an operator works down —
              transcribing the 231 bands did not make the 税額表 row vanish,
              because what landed was its largest part and not all of it"
      (is (seq (ops/blockers r)))
      (is (every? :blocker/section (ops/blockers r)))
      (let [what (into #{} (map :blocker/what) (ops/blockers r))]
        (is (contains? what "源泉徴収税額表"))
        (is (contains? what "所得税額の速算表"))
        (is (some #(str/includes? % "別表第五") what)))
      (let [b (first (filter #(= "源泉徴収税額表" (:blocker/what %))
                             (ops/blockers r)))]
        (is (str/includes? (:blocker/why b) "231"))
        (is (str/includes? (:blocker/why b) "端数処理"))))

    (testing "the plain-text rendering survives being pasted into a ticket"
      (let [txt (ops/->text r)]
        (is (str/includes? txt "運用の現況"))
        (is (str/includes? txt "未了"))
        (is (not (str/includes? txt "<")))))))

(deftest the-operations-report-carries-nothing-that-must-not-be-logged
  (let [t (fake/fake-transport)
        st (fake/store! t)]
    (store/register-contract! st (f/contract))
    (store/append-ledger! st {:client-id f/employer-id
                              :contract-id f/contract-id
                              :period f/period :disposition :commit})
    (juminzei/register-notice! st {:employer f/employer-id
                                   :notice f/resident-tax-notice-as-transcribed})
    (let [r (ops/report (ops-args st))
          printed (pr-str r)]
      (is (empty? (sensitive/log-violations r)))
      (testing "not a worker's name, not an account, not an amount"
        (doseq [leak [(:contract/worker (f/contract))
                      (:bank/payee-name-kana (f/contract))
                      (str f/gross) (str f/net)]]
          (is (not (str/includes? printed leak)) leak)))
      (testing "and not a 住民税 figure either, with a notice REGISTERED. The
                section that carries it is the one an operator screenshots,
                and a 月割額 is one employee's tax however few digits it has —
                so neither the twelve monthly figures nor the 年税額 they sum
                to appears anywhere in the printed report"
        (is (= :registered
               (:section/answer (first (filter #(= :resident-tax (:section/id %))
                                               (:report/sections r))))))
        (doseq [amount [(str f/resident-tax) (str (* 12 f/resident-tax))]]
          (is (not (str/includes? printed amount))
              (str amount " appears in the report"))))
      (testing "and a report handed a sensitive key drops it AND counts it —
                a report that quietly removed a field is one a reader believes
                was complete"
        (let [red (ops/redact {:a 1 :bank/account-number "0000000"
                               :nested {:contract/worker "甲" :b 2}})]
          (is (= 2 (:report/redacted-keys red)))
          (is (not (contains? red :bank/account-number)))
          (is (= {:b 2} (dissoc (:nested red) :report/redacted-keys))))))))

(deftest the-operations-report-reads-the-notices-off-the-store
  (testing "registered, effective and superseded are three different numbers,
            and the difference is what makes a correction visible AS a
            correction. A screen that showed only what is in force would show
            one notice where a municipality sent two"
    (let [t (fake/fake-transport)
          st (fake/store! t)
          reg! (fn [n] (juminzei/register-notice!
                        st {:employer f/employer-id :notice n}))
          section (fn [] (first (filter #(= :resident-tax (:section/id %))
                                        (:report/sections
                                         (ops/report (ops-args st))))))]
      (testing "nothing registered"
        (is (= :not-registered (:section/answer (section))))
        (is (= 0 (:section/registered (section)))))

      (reg! f/resident-tax-notice-as-transcribed)
      (testing "one 決定通知書: one registered, one effective, none superseded,
                and one year fully covered"
        (let [s (section)]
          (is (= :registered (:section/answer s)))
          (is (= 1 (:section/registered s)))
          (is (= 1 (:section/effective s)))
          (is (= 0 (:section/superseded s)))
          (is (= [false] (mapv :notice/superseded? (:section/notices s))))
          (is (= [0] (mapv :notice/revision (:section/notices s))))
          (is (= ["2026"] (mapv :coverage/tax-year (:section/coverage s))))
          (is (= [12] (mapv :coverage/months-covered (:section/coverage s))))
          (is (every? :coverage/complete? (:section/coverage s)))
          (testing "so 住民税 is no longer on the blocker list"
            (is (not-any? #(= :resident-tax (:blocker/section %))
                          (ops/blockers (ops/report (ops-args st))))))))

      (reg! f/resident-tax-notice-revised-as-transcribed)
      (testing "a 変更通知書 that replaces it: TWO registered, one effective,
                one superseded — and the replaced paper is still on the screen,
                because 「なぜ8月と9月で控除額が違うのか」 cannot be answered
                from the correction alone"
        (let [s (section)]
          (is (= 2 (:section/registered s)))
          (is (= 1 (:section/effective s)))
          (is (= 1 (:section/superseded s)))
          (is (= [true false] (mapv :notice/superseded? (:section/notices s))))
          (is (= [0 1] (mapv :notice/revision (:section/notices s))))
          (testing "one coverage row and not two: both papers are the same
                    年度, and the superseded one does not count toward it"
            (is (= ["2026"] (mapv :coverage/tax-year (:section/coverage s))))
            (is (= [12] (mapv :coverage/months-covered (:section/coverage s)))))))

      (testing "a year an effective notice does not fully cover is a blocker,
                and its reason is NOT the one an unregistered year gets — they
                are different operator actions"
        (let [partial* (assoc f/resident-tax-notice-as-transcribed
                              :notice/tax-year "2027"
                              :notice/kind :notice/revision
                              :notice/reference "R9-0000-0000"
                              :notice/annual-total nil
                              :notice/registered-at "2027-09-01"
                              :notice/effective-from :juminzei/m10
                              :notice/months
                              (into {} (for [k (drop-while
                                                #(not= :juminzei/m10 %)
                                                juminzei/month-keys)]
                                         [k f/resident-tax])))]
          (is (= :ok (:registration/status (reg! partial*))))
          (let [s (section)
                cov (into {} (map (juxt :coverage/tax-year identity))
                          (:section/coverage s))
                bs (filterv #(= :resident-tax (:blocker/section %))
                            (ops/blockers (ops/report (ops-args st))))]
            (is (= ["2026" "2027"] (mapv :coverage/tax-year (:section/coverage s)))
                "sorted by year")
            (is (true? (:coverage/complete? (get cov "2026"))))
            (is (= 8 (:coverage/months-covered (get cov "2027"))))
            (is (= 1 (count bs)) "only the uncovered year blocks")
            (is (= "住民税の通知（2027 年度）" (:blocker/what (first bs))))
            (is (str/includes? (:blocker/why (first bs))
                               "12か月のうち 8 か月しか通知に基づいていない"))
            (is (not (str/includes? (:blocker/why (first bs))
                                    "一件も登録されていない")))
            (testing "and the blocker names the months nobody has a paper for,
                      without naming an amount"
              (is (str/includes? (:blocker/why (first bs)) "m06"))
              (is (not (str/includes? (:blocker/why (first bs))
                                      (str f/resident-tax)))))))))))

(deftest an-unreadable-notice-chain-is-not-an-empty-one
  (testing "this is the defect this repository is organised around, at the one
            place it would be invisible: a read that could not run returning
            the value of a read that ran and found nothing. `:unreadable` and
            `:not-registered` are the same SHAPE — no notices came back either
            way — and opposite operator actions. Answering `:not-registered`
            to a broken chain sends somebody to transcribe a notice that is
            already there, and one paper then has two entries under one id"
    (let [t (fake/fake-transport)
          st (fake/store! t)
          _ (juminzei/register-notice!
             st {:employer f/employer-id
                 :notice f/resident-tax-notice-as-transcribed})
          before (count (fake/blocks-of t "tenant-test"))
          victim (first (keys (fake/blocks-of t "tenant-test")))
          _ (fake/corrupt-block! t "tenant-test" victim)
          r (ops/report (ops-args st))
          s (first (filter #(= :resident-tax (:section/id %))
                           (:report/sections r)))]
      (is (= :unreadable (:section/answer s)))
      (is (not= :not-registered (:section/answer s)))

      (testing "and the three counts are nil rather than 0 — a count is the
                result of counting, and this read counted nothing"
        (is (nil? (:section/registered s)))
        (is (nil? (:section/effective s)))
        (is (nil? (:section/superseded s)))
        (is (= [] (:section/coverage s)))
        (is (= [] (:section/notices s))))

      (is (str/includes? (:section/why s) "読めない履歴は空の履歴ではない"))

      (testing "the rest of the report still answers. One unreadable chain
                must not take the whole surface down — the operator whose
                store is damaged is exactly the one who needs the store panel
                and the cutover blockers"
        (is (= [:resident-tax :overtime :rates :artifacts :moneyforward
                :cutover :store :projection]
               (mapv :section/id (:report/sections r))))
        (is (seq (:section/categories
                  (first (filter #(= :overtime (:section/id %))
                                 (:report/sections r)))))))

      (testing "it is its own blocker, with its own reason"
        (let [bs (filterv #(= :resident-tax (:blocker/section %)) (ops/blockers r))]
          (is (= 1 (count bs)))
          (is (= "住民税の通知の読み出し" (:blocker/what (first bs))))
          (is (str/includes? (:blocker/why (first bs))
                             "読めない履歴は空の履歴ではない"))))

      (testing "and reading it changed nothing on the chain"
        (is (= (dec before) (count (fake/blocks-of t "tenant-test"))))))))

(deftest the-operations-report-is-deterministic
  (testing "it holds no clock, no randomness and no hash-dependent ordering —
            two calls over the same inputs are byte-identical, which is what
            makes it usable as evidence in a ticket"
    (let [t (fake/fake-transport)
          st (fake/store! t)]
      (store/register-contract! st (f/contract))
      (let [a (ops/report (ops-args st))
            b (ops/report (ops-args st))]
        (is (= (pr-str a) (pr-str b)))
        (is (= (ops/->text a) (ops/->text b)))))))

(deftest the-operations-route-is-reachable-and-refuses-an-unlisted-caller
  (let [t (fake/fake-transport)
        st (fake/store! t)
        allow (api/parse-allowlist f/allowlist-string)]
    (let [r (api/route st :kotobase allow f/caller-did
                       {:method :get :path "/api/operations"})]
      (is (= 200 (:status r)))
      (is (true? (:ok (:body r))))
      (is (seq (:report/sections (:body r)))))
    (testing "a caller nobody listed sees nothing"
      (is (= 403 (:status (api/route st :kotobase allow "did:key:zSTRANGER"
                                     {:method :get :path "/api/operations"})))))
    (testing "and the wrong verb is 405 rather than 404 — a caller using POST
              on a real route is not a caller inventing one"
      (is (= 405 (:status (api/route st :kotobase allow f/caller-did
                                     {:method :post :path "/api/operations"})))))
    (testing "the host passes the store's measured health in; without it the
              report says `not reported` and never `healthy`"
      (let [bare (api/route st :kotobase allow f/caller-did
                            {:method :get :path "/api/operations"})
            section (first (filter #(= :store (:section/id %))
                                   (:report/sections (:body bare))))]
        (is (= :not-reported (:section/answer section)))
        (is (str/includes? (:section/why section) "「健全である」ではない"))))))

;; ---------------------------------------------------------------------------
;; 10. Statutory refusals stay refusals, and an unsupported prefecture or year
;;     fails closed
;; ---------------------------------------------------------------------------

(deftest an-unsupported-prefecture-or-year-fails-closed-everywhere
  (testing "a rate that is not transcribed is refused and never approximated
            from a neighbouring one — a plausible payslip that is slightly
            wrong every month is worse than one that refuses"
    (doseq [pref ["沖縄" "北海道" "京都" "" nil]]
      (let [r (rates/lookup {:jurisdiction [:jp]
                             :scheme :scheme/health-insurance
                             :month "2026-08" :prefecture pref})]
        (is (= :refused (:rate/status r)) (pr-str pref))
        (is (contains? rates/rate-refusals (:rate/answer r)) (pr-str pref))
        (is (nil? (:rate/row r)) (pr-str pref)))))

  (testing "and so is a month outside every transcribed window, in both
            directions and in every malformed shape"
    (doseq [month ["2019-01" "2020-12" "2099-01" "2026-13" "2026" "" nil "八月"]]
      (let [r (rates/lookup {:jurisdiction [:jp]
                             :scheme :scheme/health-insurance
                             :month month :prefecture "東京"})]
        (is (= :refused (:rate/status r)) (pr-str month))
        (is (contains? #{:month-not-covered :month-malformed} (:rate/answer r))
            (pr-str month)))))

  (testing "an employee share cannot be produced from a refused rate"
    (is (= :refused (:rate/status
                     (rates/employee-share {:jurisdiction [:jp]
                                            :scheme :scheme/health-insurance
                                            :month "2026-08" :prefecture "沖縄"
                                            :base 300000
                                            :rounding :round/floor}))))))

(deftest the-generated-nta-table-is-the-one-payroll-rates-reads
  (testing "`provenance` is DATA and not a function — it is a def in a
            generated file, and a caller that invoked it would be asking a
            map to be a table rather than reading one"
    (is (map? nta/provenance))
    (is (seq (:source/sha256 nta/provenance)))
    (is (= 64 (count (:source/sha256 nta/provenance))))
    (is (pos? (:source/bytes nta/provenance)))
    (is (= "2026-01" (get-in nta/provenance
                             [:source/applicability :applicability/from])))
    (is (= "2026-12" (get-in nta/provenance
                             [:source/applicability :applicability/to])))
    (is (= 231 (:transform/bands nta/provenance)))
    (is (= 231 (count nta/bands))))

  (testing "`payroll.rates` reads the generated file rather than restating it
            — the same vectors, identical, so a regeneration cannot leave the
            two disagreeing"
    (is (identical? nta/bands (:table/bands rates/withholding-table)))
    (is (identical? nta/thresholds (:table/thresholds rates/withholding-table)))
    (is (identical? nta/kou-segments
                    (:table/kou-segments rates/withholding-table)))
    (is (identical? nta/otsu-segments
                    (:table/otsu-segments rates/withholding-table)))
    (is (identical? nta/sub-minimum
                    (:table/sub-minimum rates/withholding-table)))
    (is (identical? nta/provenance
                    (:table/provenance rates/withholding-table)))
    (is (= (:transform/bands nta/provenance)
           (count (:table/bands rates/withholding-table)))))

  (testing "the source row is read out of the pin rather than typed beside it
            — a second copy of a digest is a copy that goes stale silently"
    (let [src (:nta/withholding-2026 rates/sources)]
      (is (= (:source/url nta/provenance) (:source/url src)))
      (is (= (:source/page nta/provenance) (:source/page src)))
      (is (= (:source/sha256 nta/provenance) (:source/sha256 src)))
      (is (= (:source/retrieved-at nta/provenance) (:source/read-at src)))
      (is (str/includes? (:source/url src) "nta.go.jp"))
      (is (str/includes? (:source/scope src) "月額表のみ"))))

  (testing "and a month the table does not cover is still refused rather than
            answered from the nearest year"
    (doseq [month ["2025-12" "2027-01"]
            amount [0 104999 105000 300000 739999 740000 2000000]]
      (let [r (rates/withhold {:month month :taxable-remuneration amount
                               :column :kou :dependants 0})]
        (is (= :refused (:withhold/status r)) (str month "/" amount))
        (is (nil? (:withhold/yen r)) (str month "/" amount))
        (is (contains? rates/withholding-refusals (:withhold/answer r))
            (str month "/" amount))))))

(deftest year-end-and-statutory-artifacts-stay-explicit-refusals
  (testing "the year's correct tax comes from 別表, 別表 is not transcribed,
            and no input produces an amount"
    (doseq [gross [0 500000 3000000 12000000]
            dependants [0 1 3]]
      (let [ytd (gensen/year-to-date
                 {:records [{:op :draft-payroll-run :contract-id f/contract-id
                             :payload {:period "2026-01" :gross gross
                                       :income-tax-withheld 1}}]
                  :contract-id f/contract-id :year "2026"})
            a (gensen/year-end-amount
               {:contract (assoc declared-contract
                                 :employment/dependants dependants)
                :ytd ytd :year "2026"})]
        (is (= :refused (:amount/status a)) (str gross "/" dependants))
        (is (nil? (:amount/annual-tax a)))
        (is (nil? (:amount/over-or-under a)))
        (is (seq (:amount/why a))))))
  (testing "and a statutory summary over an incomplete year refuses rather
            than totalling what it has"
    (let [ytd (gensen/year-to-date {:records ytd-records
                                    :contract-id f/contract-id :year "2026"})]
      (is (not= :ok (:summary/status
                     (gensen/statutory-summary
                      {:employer (f/employer) :year "2026"
                       :employees [{:contract declared-contract :ytd ytd}]})))))))

;; ---------------------------------------------------------------------------
;; 住民税の通知の永続化 — the seventh chain, and the admission in front of it
;;
;; Every claim here is about the WRITE boundary rather than about the
;; arithmetic. 住民税 is the one figure this actor never computes, so the only
;; way it can be wrong is by being transcribed twice, transcribed differently,
;; corrected without saying what was corrected, or written against a history
;; nobody could read to the end.
;; ---------------------------------------------------------------------------

(defn- juminzei-store
  "A durable store over a fresh fake transport, and the transport."
  []
  (let [t (fake/fake-transport)]
    {:transport t :store (fake/store! t)}))

(defn- notice-entries
  "Every notice on the chain, read past the employer scoping so that a test
  asserting `nothing was written` is asserting it about the CHAIN and not
  about one employer's view of it."
  [t]
  (:chain/entries (kotobase/reconstruct t "tenant-test"
                                        (fake/reversible-envelope) :juminzei)))

(deftest a-retried-transcription-of-the-same-notice-is-one-registration
  (testing "an operator who clicks twice, or a form that is resubmitted, has
            transcribed one piece of paper once. `:duplicate` says so without
            calling it a refusal — the operator did nothing wrong — and the
            chain must actually hold ONE entry, because on this stream a
            second copy is a second answer to `which paper is current`"
    (let [{:keys [transport store]} (juminzei-store)
          args {:employer f/employer-id
                :notice f/resident-tax-notice-as-transcribed}
          first* (juminzei/register-notice! store args)
          again (juminzei/register-notice! store args)]
      (is (= :ok (:registration/status first*)))
      (is (= f/resident-tax-notice-id
             (get-in first* [:registration/record :notice/id])))
      (is (= :duplicate (:registration/status again)))
      (is (= f/resident-tax-notice-id (:registration/notice-id again)))
      (is (str/includes? (:registration/why again) "再送は二度目の登録ではない"))
      (is (= 1 (count (notice-entries transport))))
      (is (= 1 (count (store/juminzei-notices store f/employer-id)))))))

(deftest a-correction-is-registered-and-the-notice-it-replaces-is-kept
  (testing "nothing is overwritten. Both papers stay on the chain and what is
            current is DERIVED, which is what lets an employer be shown what
            the municipality corrected rather than only what it last said"
    (let [{:keys [store]} (juminzei-store)
          _ (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-as-transcribed})
          r (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-revised-as-transcribed})
          all (store/juminzei-notices store f/employer-id)]
      (is (= :ok (:registration/status r)))
      (is (= 2 (count all)) "the superseded notice is STILL in the store")
      (is (= [f/resident-tax-notice-id
              (juminzei/notice-id f/resident-tax-notice-revised)]
             (mapv :notice/id all))
          "registration order, oldest first — the correction history")
      (testing "and the effective set is the correction alone"
        (let [live (juminzei/effective-notices all)]
          (is (= 1 (count live)))
          (is (= (juminzei/notice-id f/resident-tax-notice-revised)
                 (:notice/id (first live))))
          (is (= :notice/revision (:notice/kind (first live))))))
      (testing "so the month is assessed off the paper that is in force"
        (is (= f/resident-tax-revised
               (:juminzei/amount
                (juminzei/assess {:period "2026-08" :notices all
                                  :obligation :special-collection})))
            "the replaced 決定通知書 does not decide a month any more")))))

(deftest a-correction-that-names-nothing-is-refused
  (testing "「訂正した」とだけ言う通知は、同じ年度について二つの通知を並べる
            だけで、どちらの月割額を控除すべきか誰にも答えられない"
    (let [{:keys [transport store]} (juminzei-store)
          _ (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-as-transcribed})
          r (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice (assoc f/resident-tax-notice-revised-as-transcribed
                                   :notice/replaces nil)})]
      (is (= :refused (:registration/status r)))
      (is (= :revision-without-replacement (:registration/reason r)))
      (is (= 1 (count (notice-entries transport))) "nothing was written"))))

(deftest a-correction-of-something-unregistered-is-refused
  (testing "登録されていない紙を訂正することはできない — and the refusal is its
            own, because `you have not registered the original` and `you did
            not say what you are correcting` are different operator actions"
    (let [{:keys [transport store]} (juminzei-store)
          r (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-revised-as-transcribed})]
      (is (= :refused (:registration/status r)))
      (is (= :replacement-not-registered (:registration/reason r)))
      (is (empty? (notice-entries transport))))))

(deftest a-second-correction-of-an-already-replaced-notice-is-refused
  (testing "two notices replacing the same third one is a silent fork in the
            correction history: both are effective, both are in force, and
            nothing anywhere reports that the employer now holds two current
            answers for the same twelve months"
    (let [{:keys [transport store]} (juminzei-store)
          reg! (fn [n] (juminzei/register-notice!
                        store {:employer f/employer-id :notice n}))
          _ (reg! f/resident-tax-notice-as-transcribed)
          _ (reg! f/resident-tax-notice-revised-as-transcribed)
          ;; a different paper (its own 通知書番号) claiming to replace the
          ;; same 決定通知書 the first correction already replaced
          r (reg! (assoc f/resident-tax-notice-revised-as-transcribed
                         :notice/reference "R8-0000-0002"
                         :notice/registered-at "2026-08-20"))]
      (is (= :refused (:registration/status r)))
      (is (= :replacement-already-replaced (:registration/reason r)))
      (is (str/includes? (:registration/why r) "訂正の訂正は、直前の通知を名指しする"))
      (is (= 2 (count (notice-entries transport))) "nothing was written")
      (testing "and the correction that DID land is still the only effective one"
        (is (= 1 (count (juminzei/effective-notices
                         (store/juminzei-notices store f/employer-id)))))))))

(deftest the-same-notice-id-with-different-content-is-refused
  (testing "the same six identity components with a different figure on them.
            Admitting it would put two disagreeing answers under one id, and
            every later read of that id would return whichever the backend
            happened to hand back last"
    (let [{:keys [transport store]} (juminzei-store)
          reg! (fn [n] (juminzei/register-notice!
                        store {:employer f/employer-id :notice n}))
          _ (reg! f/resident-tax-notice-as-transcribed)
          bumped (into {} (for [k juminzei/month-keys] [k 8300]))
          r (reg! (assoc f/resident-tax-notice-as-transcribed
                         :notice/months bumped
                         :notice/annual-total (* 12 8300)))]
      (is (= :refused (:registration/status r)))
      (is (= :conflicting-content (:registration/reason r)))
      (is (str/includes? (:registration/why r)
                         "訂正は改訂番号を上げ、差し替える通知を名指しする"))
      (is (= 1 (count (notice-entries transport))) "nothing was written")
      (is (= f/resident-tax
             (get-in (first (store/juminzei-notices store f/employer-id))
                     [:notice/months :juminzei/m06]))
          "the registered figure is untouched"))))

(deftest a-notice-cannot-name-the-employer-it-belongs-to
  (testing "the owner of a registration comes from the verified caller. A body
            that names an employer is refused whatever it names — including
            the RIGHT employer, because a surface that accepts the right answer
            from the body accepts the wrong one by the same path"
    (let [{:keys [transport store]} (juminzei-store)]
      (doseq [k juminzei/employer-naming-keys]
        (let [r (juminzei/register-notice!
                 store {:employer f/employer-id
                        :notice (assoc f/resident-tax-notice-as-transcribed
                                       k f/employer-id)})]
          (is (= :refused (:registration/status r)) (pr-str k))
          (is (= :employer-named (:registration/reason r)) (pr-str k))))
      (is (empty? (notice-entries transport)) "nothing was written"))))

(deftest a-store-scoped-to-one-employer-refuses-another-employers-notice
  (testing "the second route to the same property, and it is a different one:
            above, the BODY named an employer; here the verified caller is a
            different employer and the STORE refuses the write. One employer's
            notices are another employer's tax bill"
    (let [t (fake/fake-transport)
          s (fake/store! t {:employers #{f/employer-id}})]
      (is (= :ok (:registration/status
                  (juminzei/register-notice!
                   s {:employer f/employer-id
                      :notice f/resident-tax-notice-as-transcribed}))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"kotobase append refused"
           (juminzei/register-notice!
            s {:employer "emp-other"
               :notice f/resident-tax-notice-as-transcribed})))
      (is (= 1 (count (notice-entries t)))
          "the refused write left nothing on the chain")
      (is (empty? (store/juminzei-notices s "emp-other"))))))

(deftest a-decision-notice-whose-months-do-not-sum-is-refused-before-any-append
  (testing "both figures are printed on the same piece of paper, so a
            disagreement between them is a transcription error and never a
            smaller tax — a dropped digit would otherwise be deducted every
            month for a year and look entirely lawful"
    (let [{:keys [transport store]} (juminzei-store)
          months (assoc (into {} (for [k juminzei/month-keys] [k f/resident-tax]))
                        :juminzei/m11 820)
          r (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice (assoc f/resident-tax-notice-as-transcribed
                                   :notice/months months)})]
      (is (= :refused (:registration/status r)))
      (is (= :notice-refused (:registration/reason r)))
      (is (str/includes? (:registration/why r) "書き写しの誤り"))
      (is (empty? (notice-entries transport)) "nothing was written")
      (testing "and the refusal names BOTH numbers, so the operator can see
                which line to re-read rather than being told `it is wrong`"
        (let [d (juminzei/admit-notice
                 f/employer-id (assoc f/resident-tax-notice-as-transcribed
                                      :notice/months months))]
          (is (= (* 12 f/resident-tax) (:notice/annual-total-declared d)))
          (is (= (+ (* 11 f/resident-tax) 820) (:notice/annual-total-summed d)))))
      (testing "a 変更通知書 is NOT checked this way: it carries only the
                months from 適用開始月 onward, so summing what it carries
                answers a question about part of a year"
        (is (= :ok (:notice/status
                    (juminzei/admit-notice
                     f/employer-id
                     (assoc f/resident-tax-notice-revised-as-transcribed
                            :notice/effective-from :juminzei/m10
                            :notice/months
                            (into {} (for [k (drop-while
                                              #(not= :juminzei/m10 %)
                                              juminzei/month-keys)]
                                       [k f/resident-tax-revised]))))))))) ))

(deftest a-notice-with-no-municipality-is-refused-before-any-append
  (testing "納入先は区市町村で決まる（「区市町村ごとにとりまとめ、区市町村から
            送付される納入書で納入します」）, so a notice with no municipality
            names no 納入書 and cannot be remitted against anything"
    (let [{:keys [transport store]} (juminzei-store)]
      (doseq [bad [nil "" "   " "架空区/2"]]
        (let [r (juminzei/register-notice!
                 store {:employer f/employer-id
                        :notice (assoc f/resident-tax-notice-as-transcribed
                                       :notice/municipality bad)})]
          (is (= :refused (:registration/status r)) (pr-str bad))
          (is (= :notice-refused (:registration/reason r)) (pr-str bad))
          (is (str/includes? (:registration/why r) "区市町村") (pr-str bad))))
      (is (empty? (notice-entries transport)) "nothing was written"))))

(deftest an-incomplete-chain-refuses-the-registration-rather-than-appending
  (testing "a history that cannot be read to its end cannot answer 「これは既に
            登録されているか」, and appending against a partial read is
            registering without checking idempotency — which on this stream
            produces two notices under one id"
    (let [{:keys [transport store]} (juminzei-store)
          _ (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-as-transcribed})
          before (count (fake/blocks-of transport "tenant-test"))
          victim (ffirst (fake/blocks-of transport "tenant-test"))
          _ (fake/corrupt-block! transport "tenant-test" victim)
          r (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-revised-as-transcribed})]
      (is (= :refused (:registration/status r)))
      (is (= :history-unreadable (:registration/reason r)))
      (is (str/includes? (:registration/why r) "読めない履歴は空の履歴ではない"))
      (testing "and nothing was written — not the payload block, not a node"
        (is (= (dec before) (count (fake/blocks-of transport "tenant-test")))
            "one block fewer than before, because one was deleted; none added"))
      (is (false? (:store/readable? (kotobase/health store)))))))

(deftest a-second-store-reconstructs-the-notices-the-first-registered
  (testing "durable means: an independently constructed store over the same
            transport reads the notices back IN ORDER, superseded ones
            included. The correction history is the point of keeping them"
    (let [t (fake/fake-transport)
          a (fake/store! t)]
      (juminzei/register-notice!
       a {:employer f/employer-id
          :notice f/resident-tax-notice-as-transcribed})
      (juminzei/register-notice!
       a {:employer f/employer-id
          :notice f/resident-tax-notice-revised-as-transcribed})
      (let [b (fake/store! t)
            back (store/juminzei-notices b f/employer-id)]
        (is (= [f/resident-tax-notice-id
                (juminzei/notice-id f/resident-tax-notice-revised)]
               (mapv :notice/id back)))
        (is (= [:notice/decision :notice/revision] (mapv :notice/kind back)))
        (testing "and the twelve months survive intact — a chain that dropped
                  one would silently reduce somebody's deduction"
          (is (= 12 (count (:notice/months (first back)))))
          (is (= (repeat 12 f/resident-tax)
                 (mapv (:notice/months (first back)) juminzei/month-keys))))
        (is (true? (:store/readable? (kotobase/health b)))
            "all seven chains walked, the seventh included")))))

(deftest coverage-answers-in-months-and-never-in-yen
  (testing "this is what the operations screen reads, and that screen may not
            carry payroll amounts: it is rendered for whoever is running the
            actor and quoted into a report that is asserted to carry nothing
            loggable. A count of months is not one employee's tax"
    (let [{:keys [store]} (juminzei-store)
          _ (juminzei/register-notice!
             store {:employer f/employer-id
                    :notice f/resident-tax-notice-as-transcribed})
          notices (store/juminzei-notices store f/employer-id)
          c (juminzei/coverage {:tax-year "2026" :notices notices})
          printed (pr-str c)]
      (is (= 12 (:coverage/months-covered c)))
      (is (= 12 (:coverage/months-required c)))
      (is (true? (:coverage/complete? c)))
      (is (empty? (:coverage/uncovered-months c)))
      (testing "no figure from the notice appears anywhere in the result"
        (doseq [n [f/resident-tax (* 12 f/resident-tax) f/resident-tax-revised]]
          (is (not (str/includes? printed (str n)))
              (str n " appears in " printed))))
      (is (empty? (sensitive/log-violations c)))
      (testing "an unregistered year is 0/12 and names the months, still
                without an amount — and 0 covered months is not 零円"
        (let [none (juminzei/coverage {:tax-year "2027" :notices notices})]
          (is (= 0 (:coverage/months-covered none)))
          (is (false? (:coverage/complete? none)))
          (is (= juminzei/month-keys (:coverage/uncovered-months none)))
          (is (str/includes? (:coverage/why none) "未登録は零ではない"))))
      (testing "and a partial year names exactly the months nobody has a paper
                for, which is what an operator acts on"
        (let [partial* (juminzei/coverage
                        {:tax-year "2026"
                         :notices [(:notice/record
                                    (juminzei/admit-notice
                                     f/employer-id
                                     (assoc
                                      f/resident-tax-notice-revised-as-transcribed
                                      :notice/replaces nil
                                      :notice/revision 0
                                      :notice/effective-from :juminzei/m10
                                      :notice/months
                                      (into {} (for [k (drop-while
                                                        #(not= :juminzei/m10 %)
                                                        juminzei/month-keys)]
                                                 [k f/resident-tax-revised])))))]})]
          (is (= 8 (:coverage/months-covered partial*)))
          (is (= [:juminzei/m06 :juminzei/m07 :juminzei/m08 :juminzei/m09]
                 (:coverage/uncovered-months partial*))))))))
