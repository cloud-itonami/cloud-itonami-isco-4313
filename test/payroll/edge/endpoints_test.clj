(ns payroll.edge.endpoints-test
  "The HTTP surface, as data in and data out.

  Two things are being tested and they are not the same thing. That the
  governor refuses the right payroll runs is `payroll.governor-test`'s job.
  What has to live HERE is that the edge does not answer a question the
  governor was supposed to answer, does not let the body choose whose payroll
  is being filed or what operation is being performed, does not put the
  money-moving op behind a socket, and does not report `nobody read the
  withholding law` and `we read it and this run satisfies it` with the same
  body."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.advisor :as advisor]
            [payroll.edge.endpoints :as edge]
            [payroll.store :as store]))

(def ^:private allowlist
  {"did:key:zAlice" "emp-1" "did:key:zBob" "emp-2"})

(defn- seeded
  "Two employers, each with its own contract and its own worker's timesheets.
  emp-1 declares [:jp]; emp-2's jurisdiction is a parameter so a test can make
  it uncatalogued."
  ([] (seeded store/mem-store [:jp]))
  ([make emp-2-jurisdiction]
   (doto (make)
     (store/register-client! {:client-id "emp-1" :name "Hanako's Bakery"
                              :jurisdiction [:jp]})
     (store/register-client! {:client-id "emp-2" :name "Studio Kotoba"
                              :jurisdiction emp-2-jurisdiction})
     (store/register-contract!
      (merge (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000)
             {:employment/recipient-residency :resident
              :employment/paid-in :domestic}))
     (store/register-contract!
      (merge (labor/contract "c-2" "worker-2" "emp-2" "engraver" :hourly 3000)
             {:employment/recipient-residency :resident
              :employment/paid-in :domestic}))
     (store/register-timesheet! (labor/timesheet "worker-1" "2026-07-01" 8))
     (store/register-timesheet! (labor/timesheet "worker-1" "2026-07-02" 6))
     (store/register-timesheet! (labor/timesheet "worker-2" "2026-07-01" 10)))))

(defn- body [& {:as overrides}]
  (pr-str (merge {:contract-id "c-1" :period "2026-07" :deductions 3000
                  :income-tax-withheld 8420}
                 overrides)))

(defn- submit
  ([st did b] (edge/submit-payroll-run-core! st :ephemeral allowlist did b))
  ([st did b adv] (edge/submit-payroll-run-core! st :ephemeral allowlist did b adv)))

;; ---------------------------------------------------------------------------
;; The two gates
;; ---------------------------------------------------------------------------

(deftest an-absent-allowlist-serves-503-on-every-route
  (testing "`nobody is allowed` and `nothing was configured` are different
            deployment states; an open payroll endpoint is an open write path
            into somebody's wage record"
    (let [st (seeded)]
      (is (= 503 (:status (edge/submit-payroll-run-core!
                           st :ephemeral nil "did:key:zAlice" (body)))))
      (is (= 503 (:status (edge/run-verdict-core st nil "did:key:zAlice" "c-1"))))
      (is (= 503 (:status (edge/ledger-core st nil "did:key:zAlice")))))))

(deftest parse-allowlist-distinguishes-empty-from-absent
  (is (nil? (edge/parse-allowlist nil)))
  (is (nil? (edge/parse-allowlist "  ")))
  (is (nil? (edge/parse-allowlist "no-equals-sign")))
  (is (= {"did:key:zAlice" "emp-1"} (edge/parse-allowlist "did:key:zAlice=emp-1")))
  (is (= 2 (count (edge/parse-allowlist "did:key:zAlice=emp-1, did:key:zBob=emp-2")))))

(deftest an-unlisted-caller-is-refused-on-every-route
  (let [st (seeded)]
    (is (= 403 (:status (submit st "did:key:zMallory" (body)))))
    (is (= 403 (:status (edge/run-verdict-core st allowlist "did:key:zMallory" "c-1"))))
    (is (= 403 (:status (edge/ledger-core st allowlist "did:key:zMallory"))))))

;; ---------------------------------------------------------------------------
;; What the body may not decide
;; ---------------------------------------------------------------------------

(deftest a-body-that-names-an-employer-is-refused-and-not-quietly-dropped
  (testing "silently dropping it lets a caller believe they filed a payroll run
            against an employer they did not — a wage record filed against the
            wrong company, reported as 200"
    (doseq [k edge/employer-naming-keys]
      (let [st (seeded)
            r (submit st "did:key:zBob" (body k "emp-1"))]
        (testing (str k)
          (is (= 400 (:status r)))
          (is (= (str "the employer is taken from the verified caller,"
                      " not the request body")
                 (get-in r [:body :error])))
          (testing "and nothing landed anywhere"
            (is (empty? (store/records-of st "emp-1")))
            (is (empty? (store/records-of st "emp-2")))
            (is (empty? (store/ledger st)))))))))

(deftest the-employer-comes-from-the-did
  (let [st (seeded)
        r (submit st "did:key:zBob" (body :contract-id "c-2" :deductions 0
                                          :income-tax-withheld 4000))]
    (is (= 200 (:status r)))
    (is (= "emp-2" (get-in r [:body :employer])))
    (is (= 30000 (get-in r [:body :gross])))
    (testing "and emp-1 has nothing"
      (is (empty? (store/records-of st "emp-1"))))))

(deftest the-body-cannot-choose-the-op-and-so-cannot-reach-the-money
  (testing ":disburse-wages ALWAYS escalates and moves real money; a body
            asking for it drafts a run instead, and the committed record says
            so"
    (let [st (seeded)
          r (submit st "did:key:zAlice" (body :op :disburse-wages))]
      (is (= 200 (:status r)))
      (is (= :commit (get-in r [:body :disposition])))
      (is (= [:draft-payroll-run] (mapv :op (store/records-of st "emp-1")))))))

(deftest the-escalating-and-unchecked-ops-have-no-http-representation
  (testing ":disburse-wages moves real money and always escalates;
            :reconcile-timesheets is a write the governor recomputes nothing
            for. Neither gets a socket"
    (let [publics (set (keys (ns-publics 'payroll.edge.endpoints)))]
      (is (contains? publics 'submit-payroll-run-core!))
      (doseq [absent '[disburse-wages-core! disburse-core!
                       reconcile-timesheets-core! approve-core!]]
        (is (not (contains? publics absent)) (str absent " must not exist"))))))

;; ---------------------------------------------------------------------------
;; Structure is the edge's question; admissibility is the governor's
;; ---------------------------------------------------------------------------

(deftest structurally-broken-bodies-are-400
  (doseq [bad ["" "((" "[:not :a :map]"
               (pr-str {:contract-id "c-1"})                    ; no period
               (pr-str {:contract-id "c-1" :period "   "})
               (pr-str {:contract-id "c-1" :period :2026-07})   ; not a string
               (pr-str {:contract-id "c-1" :period "2026-07" :deductions "3000"})
               (pr-str {:contract-id "c-1" :period "2026-07" :deductions -1})
               (pr-str {:contract-id "c-1" :period "2026-07" :stake :critical})]]
    (is (= 400 (:status (submit (seeded) "did:key:zAlice" bad)))
        (str "should reject " (pr-str bad)))))

(deftest an-unrecognised-stake-would-have-thrown-inside-the-graph
  (testing "payroll.advisor/infer maps stake to confidence with a `case` that
            has no default, so this is structure, not policy"
    (is (= "invalid request body"
           (get-in (submit (seeded) "did:key:zAlice" (body :stake :critical))
                   [:body :error])))
    (testing "and the three it does recognise all get through"
      (doseq [s [:low :medium :high]]
        (is (= 200 (:status (submit (seeded) "did:key:zAlice" (body :stake s))))
            (str s))))))

(deftest a-run-citing-no-contract-is-the-governors-refusal-not-a-400
  (testing "flattening it to 400 would replace 雇用の捏造禁止 with an uncited
            invariant, and the caller would not learn which of three different
            contract failures they hit"
    (let [r (submit (seeded) "did:key:zAlice" (body :contract-id nil))]
      (is (= 409 (:status r)))
      (is (= :hold (get-in r [:body :disposition])))
      (is (= [:no-contract] (mapv :rule (get-in r [:body :violations]))))
      (is (seq (:detail (first (get-in r [:body :violations]))))
          "the refusal says why, not merely that"))))

(deftest a-malformed-withheld-amount-is-the-statutes-refusal-not-a-400
  (testing "所得税法 第百八十三条第一項 is what says a negative or non-numeric
            amount is not an accounting. A 400 here would lose the article"
    (doseq [bad [-1 "8420"]]
      (let [r (submit (seeded) "did:key:zAlice" (body :income-tax-withheld bad))]
        (is (= 409 (:status r)) (str bad))
        (is (= [:income-tax-not-withheld] (mapv :rule (get-in r [:body :violations]))))
        (is (re-find #"所得税法 第百八十三条第一項"
                     (:detail (first (get-in r [:body :violations])))))))))

(deftest another-employers-contract-is-the-governors-refusal-and-writes-nothing
  (let [st (seeded)
        r (submit st "did:key:zAlice" (body :contract-id "c-2"))]
    (is (= 409 (:status r)))
    (is (contains? (set (mapv :rule (get-in r [:body :violations])))
                   :contract-wrong-employer))
    (is (empty? (store/records-of st "emp-1")))
    (is (empty? (store/records-of st "emp-2")))))

;; ---------------------------------------------------------------------------
;; Three outcomes, three statuses
;; ---------------------------------------------------------------------------

(deftest a-clean-run-commits
  (let [st (seeded)
        r (submit st "did:key:zAlice" (body))]
    (is (= 200 (:status r)))
    (is (= :commit (get-in r [:body :disposition])))
    (is (= 28000 (get-in r [:body :gross])))
    (is (= 25000 (get-in r [:body :net])))
    (is (= 8420 (get-in r [:body :income-tax-withheld])))
    (is (= 1 (count (store/records-of st "emp-1"))))))

(deftest a-run-with-no-withholding-accounted-for-is-409-and-pays-nothing
  (let [st (seeded)
        r (submit st "did:key:zAlice" (body :income-tax-withheld nil))]
    (is (= 409 (:status r)))
    (is (= [:income-tax-not-withheld] (mapv :rule (get-in r [:body :violations]))))
    (is (empty? (store/records-of st "emp-1")))
    (testing "and the refusal is in the ledger, which is where it is auditable"
      (is (= [:hold] (mapv :disposition (store/ledger-of st "emp-1")))))))

(def ^:private unsure-advisor
  "A stand-in for a swapped-in `llm-advisor` that answers honestly and without
  confidence. Everything it proposes is arithmetically correct — the governor
  has nothing HARD to say — so the only thing left is the confidence floor."
  (reify advisor/Advisor
    (-advise [_ store request]
      (let [c (store/contract-of store (:contract-id request))
            gross (labor/wages-for c (store/timesheets-of store (:contract/worker c)))
            ded (or (:deductions request) 0)]
        {:op :draft-payroll-run :effect :propose
         :contract-id (:contract-id request) :period (:period request)
         :gross gross :deductions ded :net (- gross ded)
         :income-tax-withheld (:income-tax-withheld request)
         :stake :high :confidence 0.3
         :rationale "not sure"}))))

(deftest an-escalated-run-is-202-and-pays-nothing
  (testing "202 rather than 200 because the outcome is still open, and rather
            than 409 because nothing was refused. `awaiting a human signature`
            is neither done nor refused"
    (let [st (seeded)
          r (submit st "did:key:zAlice" (body) unsure-advisor)]
      (is (= 202 (:status r)))
      (is (= :request-approval (get-in r [:body :disposition])))
      (is (= :human-approval (get-in r [:body :awaiting])))
      (is (some? (get-in r [:body :reason])))
      (testing "nothing is written while the approval is pending, but the
                ledger has heard of the run"
        (is (empty? (store/records-of st "emp-1")))
        (is (= [:request-approval]
               (mapv :disposition (store/run-history st "c-1"))))))))

(deftest no-response-carries-an-ok-boolean-for-the-run-itself
  (testing "a payroll run has three outcomes and a boolean has two"
    (doseq [[label r] {:commit (submit (seeded) "did:key:zAlice" (body))
                       :hold (submit (seeded) "did:key:zAlice"
                                     (body :income-tax-withheld nil))
                       :escalated (submit (seeded) "did:key:zAlice" (body)
                                          unsure-advisor)}]
      (testing (str label)
        (is (not (contains? (:body r) :ok)))
        (is (contains? (:body r) :disposition))))))

;; ---------------------------------------------------------------------------
;; Unknown is never a pass
;; ---------------------------------------------------------------------------

(deftest every-run-response-says-what-the-statute-could-not-say
  (doseq [[label r] {:commit (submit (seeded) "did:key:zAlice" (body))
                     :hold (submit (seeded) "did:key:zAlice"
                                   (body :income-tax-withheld nil))
                     :escalated (submit (seeded) "did:key:zAlice" (body)
                                        unsure-advisor)}]
    (testing (str label)
      (is (map? (get-in r [:body :withholding]))
          "a 200 with no coverage report is a green tick over an unread statute")
      (is (= :not-evaluated (get-in r [:body :withholding :year-end-adjustment]))
          "所得税法 第百九十条 is catalogued upstream and never called here"))))

(deftest the-four-withholding-answers-are-four-different-bodies
  (testing "collapsing any pair of them is the distinction taxlaw exists to
            preserve"
    (let [checked (get-in (submit (seeded) "did:key:zAlice" (body))
                          [:body :withholding])
          none (get-in (submit (seeded store/mem-store [:atlantis]) "did:key:zBob"
                               (body :contract-id "c-2" :deductions 0
                                     :income-tax-withheld 4000))
                       [:body :withholding])
          not-declared (get-in (submit (seeded store/mem-store nil) "did:key:zBob"
                                       (body :contract-id "c-2" :deductions 0
                                             :income-tax-withheld 4000))
                               [:body :withholding])
          out-of-scope (let [st (seeded)]
                         (store/register-contract!
                          st (merge (labor/contract "c-1" "worker-1" "emp-1"
                                                    "baker" :hourly 2000)
                                    {:employment/recipient-residency :resident
                                     :employment/paid-in :overseas}))
                         (get-in (submit st "did:key:zAlice" (body))
                                 [:body :withholding]))]
      (is (= :checked (:withholding checked)))
      (is (= :none (:withholding none))
          "an uncatalogued jurisdiction: the law was NOT read, and that is a
           HARD hold rather than a pass")
      (is (= :not-declared (:withholding not-declared))
          "nobody asserted where these wages are paid, so no withholding law
           was consulted — not a finding that nothing is owed")
      (is (= :out-of-scope (:withholding out-of-scope))
          "outside the one article that was read; the provisions governing
           those payments were never read either")
      (is (= 4 (count (distinct (map :withholding
                                     [checked none not-declared out-of-scope])))))
      (testing "and both branches that DID reach the article name it, even
                though taxlaw returns it under two different keys"
        (is (= "所得税法 第百八十三条第一項" (:read-provision checked)))
        (is (= "所得税法 第百八十三条第一項" (:read-provision out-of-scope)))
        (is (nil? (:read-provision none)) "nothing was read")
        (is (nil? (:read-provision not-declared)) "nothing was read"))
      (testing "and the one that says :none is refused, not served as green"
        (is (= 409 (:status (submit (seeded store/mem-store [:atlantis])
                                    "did:key:zBob"
                                    (body :contract-id "c-2" :deductions 0
                                          :income-tax-withheld 4000)))))))))

(deftest the-amount-is-never-reported-as-checked
  (testing "taxlaw read 第百八十三条第一項 but not 別表第二 / 別表第五, so 1 yen
            on 28,000 of wages satisfies this gate. A response that did not say
            so would let a reader take the 200 as `the tax was right`"
    (let [r (submit (seeded) "did:key:zAlice" (body :income-tax-withheld 1))]
      (is (= 200 (:status r)))
      (is (= :checked (get-in r [:body :withholding :withholding])))
      (is (= :never-certified (get-in r [:body :withholding :amount])))
      (is (= "所得税法 第百八十三条第一項"
             (get-in r [:body :withholding :read-provision]))
          "the response names the article that WAS read, so a reader can see
           the limit of the check"))))

;; ---------------------------------------------------------------------------
;; GET /api/payroll-run/:contract-id
;; ---------------------------------------------------------------------------

(deftest an-unknown-contract-is-404-and-never-an-empty-200
  (let [r (edge/run-verdict-core (seeded) allowlist "did:key:zAlice" "c-never")]
    (is (= 404 (:status r)))
    (is (= "no such payroll run for this caller" (get-in r [:body :error])))))

(deftest a-blank-contract-id-is-400
  (is (= 400 (:status (edge/run-verdict-core (seeded) allowlist "did:key:zAlice" ""))))
  (is (= 400 (:status (edge/run-verdict-core (seeded) allowlist "did:key:zAlice" nil)))))

(deftest another-employers-contract-is-indistinguishable-from-one-that-never-existed
  (testing "a 403 here would confirm the contract exists and make a contract id
            something a competitor can probe for"
    (let [st (seeded)
          _ (submit st "did:key:zAlice" (body))
          mine (edge/run-verdict-core st allowlist "did:key:zAlice" "c-1")
          theirs (edge/run-verdict-core st allowlist "did:key:zBob" "c-1")
          absent (edge/run-verdict-core st allowlist "did:key:zBob" "c-never")]
      (is (= 200 (:status mine)))
      (is (= 404 (:status theirs)))
      (is (= theirs absent) "byte-identical, deliberately"))))

(deftest a-contracts-runs-read-back-in-order-with-their-verdict
  (let [st (seeded)
        _ (submit st "did:key:zAlice" (body :income-tax-withheld nil))
        _ (submit st "did:key:zAlice" (body))
        r (edge/run-verdict-core st allowlist "did:key:zAlice" "c-1")]
    (is (= 200 (:status r)))
    (is (= :commit (get-in r [:body :disposition])) "the LATEST, not the first")
    (is (= [:hold :commit] (mapv :disposition (get-in r [:body :history])))
        "the refused attempt stays in the history; a record of only what
         succeeded is a record of the wrong thing")
    (is (= [nil 28000] (mapv :gross (get-in r [:body :history]))))
    (is (= :checked (get-in r [:body :withholding :withholding]))
        "the coverage travels with the stored verdict, not recomputed here")))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(deftest the-ledger-route-serves-the-callers-own-slice-only
  (let [st (seeded)
        _ (submit st "did:key:zAlice" (body))
        _ (submit st "did:key:zAlice" (body :period "2026-08"
                                            :income-tax-withheld nil))
        _ (submit st "did:key:zBob" (body :contract-id "c-2" :deductions 0
                                          :income-tax-withheld 4000))
        mine (edge/ledger-core st allowlist "did:key:zAlice")]
    (is (= 200 (:status mine)))
    (is (= :caller-only (get-in mine [:body :scope])))
    (is (= 2 (get-in mine [:body :count])))
    (is (= ["2026-07" "2026-08"] (mapv :period (get-in mine [:body :entries]))))
    (is (= [:commit :hold] (mapv :disposition (get-in mine [:body :entries])))
        "a hold is in the caller's ledger too — an audit trail of only the
         runs that were paid is an audit trail of the wrong thing")
    (testing "and emp-2's payroll is not in it"
      (is (not (contains? (set (mapv :contract-id (get-in mine [:body :entries])))
                          "c-2"))))))

(deftest an-employer-with-no-entries-gets-a-real-200
  (testing "unlike an unknown contract id, `you have filed nothing` is
            something this actor knows"
    (let [r (edge/ledger-core (seeded) allowlist "did:key:zBob")]
      (is (= 200 (:status r)))
      (is (= 0 (get-in r [:body :count])))
      (is (= [] (get-in r [:body :entries]))))))

;; ---------------------------------------------------------------------------
;; The surface is exactly three routes
;; ---------------------------------------------------------------------------

(deftest store-mode-reads-only-values-it-recognises
  (is (nil? (edge/store-mode {})))
  (is (nil? (edge/store-mode {"PAYROLL_STORE" ""})))
  (is (= :ephemeral (edge/store-mode {"PAYROLL_STORE" "ephemeral"})))
  (is (= :datomic (edge/store-mode {"PAYROLL_STORE" "  datomic  "})))
  (testing "a typo in a deployment variable must not silently select a mode"
    (is (nil? (edge/store-mode {"PAYROLL_STORE" "ephemral"})))
    (is (nil? (edge/store-mode {"PAYROLL_STORE" "durable"}))))
  (testing "and each recognised mode really builds its backend"
    (is (nil? (edge/store-for nil)))
    (is (= (type (store/mem-store)) (type (edge/store-for :ephemeral))))
    (is (= (type (store/datomic-store)) (type (edge/store-for :datomic))))))

(deftest an-unconfigured-store-serves-503-and-says-whose-fault-it-is
  (let [direct (edge/store-unconfigured-response)
        routed (edge/route nil nil allowlist "did:key:zAlice"
                           {:method :get :path "/api/ledger"})]
    (is (= 503 (:status direct)))
    (testing "not a 409 :no-client — an empty in-process store would fail the
              governor's provenance check and blame the CALLER"
      (is (= "no store configured" (get-in direct [:body :error])))
      (is (re-find #"ephemeral" (get-in direct [:body :hint]))))
    (is (= direct routed) "the router reaches the same refusal")
    (testing "and a store present with no mode declared is still 503"
      (is (= direct (edge/route (seeded) nil allowlist "did:key:zAlice"
                                {:method :get :path "/api/ledger"}))))))

(deftest the-router-serves-three-routes-and-refuses-the-rest
  (let [st (seeded)
        call (fn [method path & [b]]
               (edge/route st :ephemeral allowlist "did:key:zAlice"
                           {:method method :path path :body b}))]
    (is (= 200 (:status (call :post "/api/payroll-run" (body)))))
    (is (= 200 (:status (call :get "/api/payroll-run/c-1"))))
    (is (= 200 (:status (call :get "/api/ledger"))))
    (testing "a path nobody declared is 404"
      (is (= 404 (:status (call :post "/api/disburse" (body)))))
      (is (= 404 (:status (call :post "/api/reconcile" (body)))))
      (is (= 404 (:status (call :get "/")))))
    (testing "a method nobody declared on a real route is 405, not 404"
      (is (= 405 (:status (call :get "/api/payroll-run"))))
      (is (= [:post] (get-in (call :get "/api/payroll-run") [:body :allow])))
      (is (= 405 (:status (call :post "/api/ledger"))))
      (is (= 405 (:status (call :post "/api/payroll-run/c-1")))))))

(deftest the-router-carries-reads-across-requests-on-the-durable-backend
  (testing "a router that built its own store per request would answer 404 to
            every read while looking healthy"
    (let [st (seeded store/datomic-store [:jp])
          call (fn [method path & [b]]
                 (edge/route st :datomic allowlist "did:key:zAlice"
                             {:method method :path path :body b}))]
      (is (= 200 (:status (call :post "/api/payroll-run" (body)))))
      (let [r (call :get "/api/payroll-run/c-1")]
        (is (= 200 (:status r)))
        (is (= :commit (get-in r [:body :disposition])))
        (is (= 28000 (:gross (first (get-in r [:body :history]))))))
      (is (= 1 (get-in (call :get "/api/ledger") [:body :count])))
      (is (= :datomic (get-in (call :post "/api/payroll-run" (body :period "2026-08"))
                              [:body :store]))))))
