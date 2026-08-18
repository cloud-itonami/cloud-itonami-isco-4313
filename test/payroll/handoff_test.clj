(ns payroll.handoff-test
  "Converted is not posted.

  Every assertion here exists because, before `payroll.handoff`, the five
  things the ledger actor can do with an entry were indistinguishable from
  this side — there was no trace at all, and a handoff with no trace reads
  as a success by having nothing to contradict it."
  (:require [clojure.test :refer [deftest is testing]]
            [payroll.handoff :as handoff]
            [payroll.shiwake :as shiwake]
            [payroll.store :as store]))

;; ---------------------------------------------------------------------------
;; fixtures — submissions built the way the actor really builds them
;; ---------------------------------------------------------------------------

(def ^:private mapping
  {:wages "給料手当" :withholding "預り金" :payable "未払金"})

(defn- committed [& {:keys [contract-id period]
                     :or {contract-id "c-1" period "2026-07"}}]
  {:disposition :commit
   :run {:contract-id contract-id :period period :gross 300000 :net 270000
         :income-tax-withheld 30000 :currency "JPY"}})

(defn- submission
  "What the actor hands over: a `shiwake/entry-requests` :ok entry with the
  employer assoc'd on. Built through `shiwake` rather than hand-written, so
  a change to the request shape is felt here too."
  [& {:keys [client-id contract-id period]
      :or {client-id "emp-1" contract-id "c-1" period "2026-07"}}]
  (-> (shiwake/entry-request (committed :contract-id contract-id :period period)
                             mapping)
      (assoc :shiwake/run (committed :contract-id contract-id :period period)
             :client-id client-id)))

(def ^:private posted
  {:status 200 :body {:ok true :ephemeral false :client "c-1" :duplicate? false
                      :posting "post-abc" :posting-count 4 :balanced? true}})

(def ^:private duplicate
  (assoc-in posted [:body :duplicate?] true))

(def ^:private held
  {:status 409 :body {:ok false :disposition :hold
                      :violations [{:rule :unknown-source-doc
                                    :detail "no such document"}]}})

(def ^:private awaiting
  {:status 202 :body {:ok false :disposition :request-approval
                      :reason :external-send}})

(def ^:private rejected
  {:status 400 :body {:ok false :error "invalid request body"}})

;; ---------------------------------------------------------------------------
;; 1. every outcome is recorded, including the good one
;; ---------------------------------------------------------------------------

(deftest every-outcome-the-ledger-actor-can-return-becomes-a-fact
  (testing "a ledger that recorded only refusals could not answer `was this
            run posted?`, which is the question the loop exists to close"
    (doseq [[response expected] [[posted :posted]
                                 [duplicate :duplicate]
                                 [held :held]
                                 [awaiting :awaiting-approval]
                                 [rejected :rejected]]]
      (let [f (handoff/handoff-fact (submission) response)]
        (is (= expected (:handoff/outcome f))
            (str "response " (:status response) " should record " expected))
        (is (contains? handoff/outcomes (:handoff/outcome f)))
        (is (= (:status response) (:handoff/status f)))))))

(deftest a-successful-handoff-is-a-fact-and-not-a-silence
  (testing "the good outcome is the one a silent design would have left out,
            and it is the one somebody reconciling asks for first"
    (let [f (handoff/handoff-fact (submission) posted)]
      (is (= :posted (:handoff/outcome f)))
      (is (= "post-abc" (:handoff/posting f))))))

;; ---------------------------------------------------------------------------
;; 2. :duplicate is its own outcome
;; ---------------------------------------------------------------------------

(deftest duplicate-is-not-folded-into-posted
  (testing "one wrote, one confirmed something already there — two :posted
            facts for one run is a double payment, :posted then :duplicate
            is a safe retry, and folding them loses exactly that"
    (let [a (handoff/handoff-fact (submission) posted)
          b (handoff/handoff-fact (submission) duplicate)]
      (is (= :posted (:handoff/outcome a)))
      (is (= :duplicate (:handoff/outcome b)))
      (is (not= (:handoff/outcome a) (:handoff/outcome b)))
      (testing "and both still carry the posting id they refer to"
        (is (= "post-abc" (:handoff/posting a) (:handoff/posting b)))))))

;; ---------------------------------------------------------------------------
;; 3. an unrecognised response never becomes a success
;; ---------------------------------------------------------------------------

(deftest an-unreadable-response-is-never-recorded-as-posted
  (testing "defaulting the unknown to the good outcome is how a ledger fills
            with postings that were never made"
    (doseq [r [nil
               {}
               "500 Internal Server Error"
               {:status 200 :body nil}
               {:status 200 :body "{\"ok\":true}"}
               {:status "200" :body {:ok true :duplicate? false}}
               {:status 200 :body {:ok false :duplicate? false}}
               {:status 200 :body {:ok true}}
               {:status 200 :body {:ok true :duplicate? "no"}}
               {:status 418 :body {:ok false}}
               {:status 302 :body {:location "/elsewhere"}}]]
      (let [f (handoff/handoff-fact (submission) r)]
        (is (= :unknown-response (:handoff/outcome f))
            (str "must not understand " (pr-str r)))
        (is (not (contains? #{:posted :duplicate} (:handoff/outcome f))))
        (is (string? (:handoff/response f))
            "and must carry enough of the response to diagnose it")))))

(deftest a-200-that-cannot-say-whether-it-wrote-is-unknown-not-posted
  (testing "without a boolean :duplicate? the two 200 outcomes cannot be told
            apart, and guessing either way fabricates the distinction"
    (let [f (handoff/handoff-fact (submission) {:status 200 :body {:ok true}})]
      (is (= :unknown-response (:handoff/outcome f)))
      (is (re-find #"duplicate\?" (:handoff/why f))))))

(deftest an-excerpt-is-bounded-because-a-ledger-is-append-only
  (let [f (handoff/handoff-fact (submission)
                                {:status 599 :body {:blah (apply str (repeat 5000 "x"))}})]
    (is (= :unknown-response (:handoff/outcome f)))
    (is (true? (:handoff/response-truncated? f)))
    (is (< (count (:handoff/response f)) 600))))

;; ---------------------------------------------------------------------------
;; 4/5. the fact identifies what it is about
;; ---------------------------------------------------------------------------

(deftest a-fact-carries-the-stamp-the-ledger-is-read-back-by
  (testing "a reconciliation record that cannot be joined back to the thing
            it reconciles is not one"
    (let [f (handoff/handoff-fact (submission :client-id "emp-7" :contract-id "c-9"
                                              :period "2026-08")
                                  posted)]
      (is (= "emp-7" (:client-id f)))
      (is (= "c-9" (:contract-id f)))
      (is (= "2026-08" (:period f)))
      (is (= "c-9" (:handoff/source-doc f)))
      (is (= "post-abc" (:handoff/posting f)))
      (is (not (contains? f :handoff/unidentified?))))))

(deftest a-handoff-fact-is-findable-through-the-stores-own-reads
  (testing "the whole point of the stamp: `payroll.store/run-history` and
            `ledger-of` must return it, or the fact is unjoinable in practice
            however well-formed it looks"
    (let [st (store/mem-store)
          f (handoff/handoff-fact (submission :client-id "emp-7" :contract-id "c-9")
                                  duplicate)]
      (store/append-ledger! st f)
      (is (= [f] (store/run-history st "c-9")))
      (is (= [f] (store/ledger-of st "emp-7")))
      (is (= :duplicate (:handoff/outcome (first (store/run-history st "c-9"))))))))

(deftest a-handoff-is-not-counted-as-one-of-the-actors-own-dispositions
  (testing ":disposition :handoff, so a reader counting commits does not
            count handoffs — the ledger already uses :commit/:hold/
            :request-approval for what THIS actor decided"
    (let [f (handoff/handoff-fact (submission) posted)]
      (is (= :handoff (:disposition f)))
      (is (not (contains? #{:commit :hold :request-approval} (:disposition f)))))))

(deftest a-submission-identifying-nothing-still-records-its-outcome
  (testing "losing the outcome would be worse than recording an unjoinable
            fact — but the fact says so rather than leaving nils to notice"
    (let [f (handoff/handoff-fact {} posted)]
      (is (= :posted (:handoff/outcome f)))
      (is (true? (:handoff/unidentified? f))))
    (testing "an employer with no contract is unidentified too"
      (is (true? (:handoff/unidentified?
                  (handoff/handoff-fact {:client-id "emp-1"} posted)))))))

;; ---------------------------------------------------------------------------
;; the detail each refusal carries
;; ---------------------------------------------------------------------------

(deftest a-held-entry-carries-the-rules-it-was-held-against
  (let [f (handoff/handoff-fact (submission) held)]
    (is (= :held (:handoff/outcome f)))
    (is (= [{:rule :unknown-source-doc :detail "no such document"}]
           (:handoff/violations f))))
  (testing "a 409 naming no rule is still a hold, with an empty vector —
            `it refused and said nothing` is not `it did not refuse`"
    (let [f (handoff/handoff-fact (submission) {:status 409 :body {:ok false}})]
      (is (= :held (:handoff/outcome f)))
      (is (= [] (:handoff/violations f))))))

(deftest an-escalated-entry-carries-what-it-awaits
  (let [f (handoff/handoff-fact (submission) awaiting)]
    (is (= :awaiting-approval (:handoff/outcome f)))
    (is (= :external-send (:handoff/reason f)))))

(deftest a-refused-request-carries-the-error-it-was-refused-with
  (doseq [[r err] [[rejected "invalid request body"]
                   [{:status 403 :body {:ok false :error "caller not permitted"}}
                    "caller not permitted"]
                   [{:status 503 :body {:ok false :error "no allow-list configured"}}
                    "no allow-list configured"]]]
    (let [f (handoff/handoff-fact (submission) r)]
      (is (= :rejected (:handoff/outcome f)))
      (is (= err (:handoff/error f))))))

;; ---------------------------------------------------------------------------
;; 6. the batch pairs, and refuses rather than guessing
;; ---------------------------------------------------------------------------

(defn- batch-response [results]
  {:status 207
   :body {:client "c-1" :submitted (count results)
          :summary (frequencies (map :outcome results))
          :results (vec results)}})

(defn- result [source-doc outcome & {:keys [status posting violations error]}]
  {:status (or status (case outcome
                        (:posted :duplicate) 200
                        :awaiting-approval 202
                        :held 409
                        400))
   :outcome outcome :source-doc source-doc
   :posting posting :violations violations :error error})

(deftest a-batch-pairs-every-outcome-with-the-run-it-answers
  (let [subs [(submission :contract-id "c-1" :client-id "emp-1")
              (submission :contract-id "c-2" :client-id "emp-2")
              (submission :contract-id "c-3" :client-id "emp-3")]
        out (handoff/handoff-facts
             subs
             (batch-response [(result "c-1" :posted :posting "p-1")
                              (result "c-2" :held
                                      :violations [{:rule :unbalanced :detail "no"}])
                              (result "c-3" :duplicate :posting "p-3")]))
        facts (:handoff/facts out)]
    (is (= :ok (:handoff/status out)))
    (is (= 3 (count facts)))
    (is (= [:posted :held :duplicate] (mapv :handoff/outcome facts)))
    (is (= ["c-1" "c-2" "c-3"] (mapv :contract-id facts)))
    (is (= ["emp-1" "emp-2" "emp-3"] (mapv :client-id facts)))
    (is (= "p-1" (:handoff/posting (first facts))))
    (is (= [{:rule :unbalanced :detail "no"}] (:handoff/violations (second facts))))))

(deftest a-batch-refuses-a-length-mismatch-rather-than-zipping
  (testing "results come back in submission order, so a gap misattributes
            every outcome after it — one employer's hold recorded against
            another employer's run"
    (let [out (handoff/handoff-facts
               [(submission :contract-id "c-1") (submission :contract-id "c-2")
                (submission :contract-id "c-3")]
               (batch-response [(result "c-1" :posted) (result "c-2" :held)]))]
      (is (= :length-mismatch (:handoff/status out)))
      (is (= 3 (:handoff/submitted out)))
      (is (= 2 (:handoff/answered out)))
      (testing "and produces no facts: a fact against the wrong run is worse
                than an absent one, because it reads as settled"
        (is (nil? (:handoff/facts out)))))))

(deftest a-batch-refuses-answers-that-cite-the-wrong-source-document
  (testing "counts can agree while the order does not, so the source-doc is
            the stronger evidence"
    (let [out (handoff/handoff-facts
               [(submission :contract-id "c-1") (submission :contract-id "c-2")]
               (batch-response [(result "c-2" :posted) (result "c-1" :held)]))]
      (is (= :source-doc-mismatch (:handoff/status out)))
      (is (= {:index 0 :submitted "c-1" :answered "c-2"} (:handoff/mismatch out)))
      (is (nil? (:handoff/facts out))))))

(deftest a-batch-refused-before-it-was-read-still-records-every-run
  (testing "no entry was looked at, so every run would otherwise be invisible
            again — which is the defect this namespace exists to close"
    (doseq [[status err] [[400 "batch too large"]
                          [403 "caller not permitted"]
                          [503 "no allow-list configured"]]]
      (let [out (handoff/handoff-facts
                 [(submission :contract-id "c-1") (submission :contract-id "c-2")]
                 {:status status :body {:ok false :error err}})]
        (is (= :batch-refused (:handoff/status out)))
        (is (= 2 (count (:handoff/facts out))))
        (is (every? #(= :rejected (:handoff/outcome %)) (:handoff/facts out)))
        (is (every? #(= err (:handoff/error %)) (:handoff/facts out)))
        (testing "and says the whole batch was refused, not this entry —
                  `it refused this` and `it never saw this` are different"
          (is (every? #(= :batch (:handoff/scope %)) (:handoff/facts out))))
        (is (= ["c-1" "c-2"] (mapv :contract-id (:handoff/facts out))))))))

(deftest an-unreadable-batch-response-records-every-run-as-unknown
  (doseq [r [nil {} {:status 207 :body {:results "not a vector"}} "gateway timeout"]]
    (let [out (handoff/handoff-facts [(submission :contract-id "c-1")] r)]
      (is (= :unknown-response (:handoff/status out)) (str "for " (pr-str r)))
      (is (= 1 (count (:handoff/facts out))))
      (is (= :unknown-response (:handoff/outcome (first (:handoff/facts out)))))
      (is (string? (:handoff/response out))))))

(deftest a-batch-result-whose-outcome-and-status-disagree-is-not-believed
  (testing "the ledger actor derives one from the other so they always agree
            there; a disagreement means something between the two actors
            mangled or invented the answer"
    (let [out (handoff/handoff-facts
               [(submission :contract-id "c-1")]
               (batch-response [(result "c-1" :posted :status 409)]))
          f (first (:handoff/facts out))]
      (is (= :ok (:handoff/status out)))
      (is (= :unknown-response (:handoff/outcome f)))
      (is (re-find #"do not agree" (:handoff/why f))))))

(deftest a-batch-outcome-this-actor-does-not-know-is-not-quietly-recorded
  (doseq [bad [:committed :ok nil "posted"]]
    (let [out (handoff/handoff-facts
               [(submission :contract-id "c-1")]
               (batch-response [(result "c-1" bad :status 200)]))
          f (first (:handoff/facts out))]
      (is (= :unknown-response (:handoff/outcome f)) (str "for " (pr-str bad)))
      (is (contains? handoff/outcomes (:handoff/outcome f))))))

(deftest an-empty-batch-is-neither-a-mismatch-nor-a-refusal
  (let [out (handoff/handoff-facts [] (batch-response []))]
    (is (= :ok (:handoff/status out)))
    (is (= [] (:handoff/facts out)))))

;; ---------------------------------------------------------------------------
;; it makes no call
;; ---------------------------------------------------------------------------

(deftest this-namespace-reaches-nothing-and-writes-nothing
  (testing "it interprets a response something else obtained: a namespace
            that fetched would make this actor a reader of another actor's
            ledger, and one that appended would decide when to write to its
            own — the caller does both"
    (let [src (slurp "src/payroll/handoff.cljc")]
      (doseq [tok ["http" "fetch" "slurp" "js/" "swap!" "reset!" "atom"
                   "append-ledger!" "store/" "spit"]]
        (is (not (re-find (re-pattern (str "\\(" (java.util.regex.Pattern/quote tok))) src))
            (str "must not call out or mutate: found (" tok)))
      (is (not (re-find #"\[payroll\.store" src))
          "must not depend on the store it produces facts for"))))
