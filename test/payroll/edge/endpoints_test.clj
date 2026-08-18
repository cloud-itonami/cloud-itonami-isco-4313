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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; The route set grew from four to five when `:assess-year-end-adjustment`
;; landed. This test is the one place that enumerates the surface, so it is
;; the one place that had to change; no assertion in it was weakened, and the
;; three 404/405 groups below are unchanged.
(deftest the-router-serves-five-routes-and-refuses-the-rest
  (let [st (seeded)
        call (fn [method path & [b]]
               (edge/route st :ephemeral allowlist "did:key:zAlice"
                           {:method method :path path :body b}))]
    (is (= 200 (:status (call :post "/api/payroll-run" (body)))))
    (is (= 200 (:status (call :get "/api/payroll-run/c-1"))))
    (is (= 200 (:status (call :get "/api/ledger"))))
    (is (= 200 (:status (call :post "/api/handoff"
                              (pr-str {:handoffs [{:submission {:contract-id "c-1"}
                                                   :response {:status 200
                                                              :body {:ok true
                                                                     :duplicate? false}}}]})))))
    (is (= 409 (:status (call :post "/api/year-end-adjustment"
                              (pr-str {:contract-id "c-1" :year "2026"
                                       :final-payment-of-year? true}))))
        "the route is reachable; c-1 registers no 申告書, so it HOLDS")
    (testing "a path nobody declared is 404"
      (is (= 404 (:status (call :post "/api/disburse" (body)))))
      (is (= 404 (:status (call :post "/api/reconcile" (body)))))
      (is (= 404 (:status (call :get "/")))))
    (testing "a method nobody declared on a real route is 405, not 404"
      (is (= 405 (:status (call :get "/api/payroll-run"))))
      (is (= [:post] (get-in (call :get "/api/payroll-run") [:body :allow])))
      (is (= 405 (:status (call :post "/api/ledger"))))
      (is (= 405 (:status (call :post "/api/payroll-run/c-1"))))
      (is (= 405 (:status (call :get "/api/year-end-adjustment"))))
      (is (= [:post] (get-in (call :get "/api/year-end-adjustment")
                             [:body :allow]))))))

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

;; ---------------------------------------------------------------------------
;; Bringing the ledger actor's answer back
;;
;; `payroll.handoff` is pure and calls nothing, which is right — posting into
;; another actor's ledger is actuation this repo does not do. The consequence
;; was that nothing called it either: a namespace reachable from nowhere, whose
;; reconciliation never touched the audit trail. These tests are about the
;; route, not the pure function, so they are phrased about the CALLER.
;; ---------------------------------------------------------------------------

(defn- handoff-body [& pairs]
  (pr-str {:handoffs (vec pairs)}))

(def ^:private posted-200
  {:status 200 :body {:ok true :duplicate? false :posting "je-abc"}})

(defn- record [st did b]
  (edge/record-handoff-core! st allowlist did b))

(defn- handoffs-of [st client-id]
  (filterv #(= :handoff (:disposition %)) (store/ledger-of st client-id)))

(deftest the-route-is-what-makes-the-reconciliation-reach-the-ledger
  (testing "before it, handoff was computed by nobody and read by nobody"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (handoff-body {:submission {:contract-id "c-1" :period "2026-07"
                                                :shiwake/request {:source-doc "c-1"}}
                                   :response posted-200}))]
      (is (= 200 (:status r)))
      (is (= 1 (get-in r [:body :recorded])))
      (let [[f] (handoffs-of st "emp-1")]
        (is (= :posted (:handoff/outcome f)))
        (is (= "je-abc" (:handoff/posting f)))
        (is (= "c-1" (:handoff/source-doc f)))))))

(deftest the-employer-comes-from-the-did-here-too
  (testing "ledger-of slices by :client-id, so a body-chosen employer would
            write one client's reconciliation into another client's books"
    (let [st (seeded)]
      (is (= 200 (:status (record st "did:key:zAlice"
                                  (handoff-body {:submission {:client-id "emp-2"
                                                              :contract-id "c-1"
                                                              :shiwake/request {:source-doc "c-1"}}
                                                 :response posted-200})))))
      (is (= 1 (count (handoffs-of st "emp-1"))) "landed in the caller's slice")
      (is (empty? (handoffs-of st "emp-2")) "and not in the one the body named"))))

(deftest a-handoff-is-never-counted-as-a-commit
  (testing "a reader tallying what this actor paid must not tally what another
            actor recorded"
    (let [st (seeded)]
      (record st "did:key:zAlice"
              (handoff-body {:submission {:contract-id "c-1"} :response posted-200}))
      (is (= [:handoff] (mapv :disposition (store/ledger-of st "emp-1")))))))

(deftest every-outcome-is-recorded-not-only-the-refusals
  (testing "a ledger that drops the successes cannot answer `was this run
            recorded downstream`, which is what the seam exists for"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (handoff-body {:submission {:contract-id "c-1"} :response posted-200}
                                  {:submission {:contract-id "c-2"}
                                   :response {:status 409 :body {:ok false :violations []}}}
                                  {:submission {:contract-id "c-3"}
                                   :response {:status 200 :body {:ok true :duplicate? true}}}))]
      (is (= 3 (get-in r [:body :recorded])))
      (is (= 3 (count (handoffs-of st "emp-1"))))
      (is (= {:posted 1 :held 1 :duplicate 1} (get-in r [:body :outcomes]))))))

(deftest unresolved-names-what-a-person-still-has-to-look-at
  (testing "the carrier learns it in the same round-trip, rather than having to
            re-read the ledger to find out whether anything needs attention"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (handoff-body {:submission {:contract-id "c-1"} :response posted-200}
                                  {:submission {:contract-id "c-2"}
                                   :response {:status 200 :body {:ok true :duplicate? true}}}
                                  {:submission {:contract-id "c-3"}
                                   :response {:status 409 :body {:ok false}}}))]
      (is (= ["c-3"] (mapv :contract-id (get-in r [:body :unresolved])))
          "a duplicate is settled; a hold is not"))))

(deftest the-carrier-states-the-pairing-so-position-cannot-be-wrong
  (testing "the batch form must detect misattribution because position is all
            it has; here each pair names its own submission"
    (let [st (seeded)]
      (record st "did:key:zAlice"
              (handoff-body {:submission {:contract-id "c-2"}
                             :response {:status 409 :body {:ok false}}}
                            {:submission {:contract-id "c-1"} :response posted-200}))
      (is (= {"c-2" :held "c-1" :posted}
             (into {} (map (juxt :contract-id :handoff/outcome))
                   (handoffs-of st "emp-1")))))))

(deftest an-unreadable-handoff-body-appends-nothing
  (testing "a ledger holding half a batch is less trustworthy than an empty one"
    (doseq [b ["(((" "42" "{:handoffs {}}" "{:handoffs []}"
               (pr-str {:handoffs [{:submission {} :response posted-200}
                                   {:submission {}}]})]]
      (let [st (seeded)
            r (record st "did:key:zAlice" b)]
        (is (= 400 (:status r)) (str "for " b))
        (is (empty? (store/ledger-of st "emp-1")) (str "wrote nothing, for " b))))))

(deftest a-response-that-is-not-a-map-is-the-callers-error-not-an-odd-answer
  (testing "recording it as :unreadable would blame the ledger actor for a
            malformed request this actor's own caller sent"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (pr-str {:handoffs [{:submission {:contract-id "c-1"}
                                         :response "200 OK"}]}))]
      (is (= 400 (:status r)))
      (is (empty? (store/ledger-of st "emp-1"))))))

(deftest the-handoff-route-has-the-same-two-gates-as-the-others
  (let [st (seeded)
        b (handoff-body {:submission {:contract-id "c-1"} :response posted-200})]
    (is (= 503 (:status (edge/record-handoff-core! st nil "did:key:zAlice" b))))
    (is (= 403 (:status (record st "did:key:zMallory" b))))
    (is (empty? (store/ledger-of st "emp-1")) "neither gate wrote anything")))

(deftest the-router-reaches-the-handoff-route
  (testing "a core function nothing routes to is reachable from nothing —
            which is the defect this route exists to fix"
    (let [st (seeded)
          call (fn [method b]
                 (edge/route st :ephemeral allowlist "did:key:zAlice"
                             {:method method :path "/api/handoff" :body b}))]
      (is (= 200 (:status (call :post (handoff-body {:submission {:contract-id "c-1"}
                                                     :response posted-200})))))
      (is (= 1 (count (handoffs-of st "emp-1"))))
      (is (= 405 (:status (call :get nil))))
      (is (= [:post] (get-in (call :get nil) [:body :allow]))))))

(deftest a-set-of-pairs-is-refused-because-a-set-is-not-a-list-of-events
  (testing "a set silently drops a hand-off repeated inside one batch and
            gives :unresolved no stable order — two records of the same run
            answered twice is a fact about the carrier, not a duplicate to
            collapse"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (pr-str {:handoffs #{{:submission {:contract-id "c-1"}
                                          :response posted-200}}}))]
      (is (= 400 (:status r)))
      (is (empty? (store/ledger-of st "emp-1"))))))

(deftest a-pair-with-no-submission-is-refused-rather-than-recorded-unjoinable
  (testing "`payroll.handoff` tolerates an unidentifiable submission on
            purpose — losing the outcome would be worse than recording an
            unjoinable one. The route does not have that excuse: its caller
            can be told to send the submission, and a fact with no contract
            is one nobody can ever read back"
    (let [st (seeded)
          r (record st "did:key:zAlice"
                    (pr-str {:handoffs [{:response posted-200}]}))]
      (is (= 400 (:status r)))
      (is (empty? (store/ledger-of st "emp-1"))))))

;; ---------------------------------------------------------------------------
;; POST /api/year-end-adjustment
;;
;; What has to live here is that the surface does not turn a nine-valued
;; reading of 所得税法 第百九十条 into a green tick, does not let the body say
;; whose 年末調整 this is, and does not accept a string where the article needs
;; a declared fact.
;; ---------------------------------------------------------------------------

(defn- nen-seeded
  "`seeded`, plus a second contract for emp-1 on which the 申告書 IS
  registered. Having both is the point: the default contract leaves it
  unobserved, which must hold."
  ([] (nen-seeded []))
  ([runs]
   (let [st (seeded)]
     (store/register-contract!
      st (merge (labor/contract "c-1-nen" "worker-1" "emp-1" "baker" :hourly 2000)
                {:employment/recipient-residency :resident
                 :employment/paid-in :domestic
                 :employment/year-end-declaration-filed? true}))
     (doseq [r runs] (store/commit-record! st r))
     st)))

(defn- nen-body [& {:as overrides}]
  (pr-str (merge {:contract-id "c-1-nen" :year "2026"
                  :final-payment-of-year? true}
                 overrides)))

(defn- nen [st did b]
  (edge/assess-year-end-core! st :ephemeral allowlist did b))

(deftest the-year-end-route-has-the-same-two-gates-as-the-others
  (let [st (nen-seeded)]
    (is (= 503 (:status (edge/assess-year-end-core!
                         st :ephemeral nil "did:key:zAlice" (nen-body)))))
    (is (= 403 (:status (nen st "did:key:zMallory" (nen-body)))))))

(deftest a-fully-observed-year-end-question-is-answered-with-200
  (let [r (nen (nen-seeded) "did:key:zAlice" (nen-body))
        a (get-in r [:body :year-end-adjustment])]
    (is (= 200 (:status r)))
    (is (= :commit (get-in r [:body :disposition])))
    (is (= "emp-1" (get-in r [:body :employer])))
    (is (= "2026" (get-in r [:body :year])))
    (is (= :owed (:answer a)))
    (is (true? (:answerable? a)))
    (is (= "所得税法 第百九十条" (:provision a)))
    (testing "a 200 is not an approval — no :ok true anywhere on it"
      (is (not (contains? (:body r) :ok))))
    (testing "and it still refuses to produce a figure"
      (is (= :not-computable (get-in a [:amount :nenmatsu/over-or-under])))
      (is (= :not-computable (get-in a [:amount :nenmatsu/annual-tax])))
      (is (str/includes? (get-in a [:amount :nenmatsu/amount-source-not-read])
                         "別表")))))

(deftest the-three-passes-that-are-the-article-not-reaching-this-employee
  (testing "`not yet`, `no 申告書 filed` and `over 二千万円` all commit and are
            all different answers. A boolean would print them the same, and
            only one of the three is an instruction to come back"
    (let [not-yet (nen (nen-seeded) "did:key:zAlice"
                       (nen-body :final-payment-of-year? false))
          over (nen (nen-seeded [{:client-id "emp-1" :op :draft-payroll-run
                                  :contract-id "c-1-nen"
                                  :payload {:op :draft-payroll-run
                                            :period "2026-07"
                                            :gross 20000001
                                            :income-tax-withheld 1}}])
                    "did:key:zAlice" (nen-body))]
      (is (= 200 (:status not-yet)))
      (is (= 200 (:status over)))
      (is (= :year-not-finished (get-in not-yet [:body :year-end-adjustment :answer])))
      (is (= :above-ceiling (get-in over [:body :year-end-adjustment :answer])))
      (is (every? true? [(get-in not-yet [:body :year-end-adjustment :answerable?])
                         (get-in over [:body :year-end-adjustment :answerable?])])))))

(deftest an-unobserved-declaration-is-409-and-names-the-rule
  (testing "c-1 registers no 申告書. Software cannot see a piece of paper, and
            unseen is not filed"
    (let [r (nen (nen-seeded) "did:key:zAlice" (nen-body :contract-id "c-1"))]
      (is (= 409 (:status r)))
      (is (= :hold (get-in r [:body :disposition])))
      (is (some #(= :year-end-declaration-not-observed (:rule %))
                (get-in r [:body :violations])))
      (is (= :declaration-not-observed
             (get-in r [:body :year-end-adjustment :answer])))
      (is (false? (get-in r [:body :year-end-adjustment :answerable?]))))))

(deftest a-jurisdiction-whose-year-end-facet-was-not-read-is-409-not-200
  (testing "adding this op must not widen a pass. emp-2 declares [:us], whose
            年末調整 facet taxlaw records as out-of-scope"
    (let [st (seeded store/mem-store [:us])]
      (store/register-contract!
       st (merge (labor/contract "c-2-nen" "worker-2" "emp-2" "engraver" :hourly 3000)
                 {:employment/year-end-declaration-filed? true}))
      (let [r (nen st "did:key:zBob" (nen-body :contract-id "c-2-nen"))
            hit (first (filter #(= :unchecked-year-end-jurisdiction (:rule %))
                               (get-in r [:body :violations])))]
        (is (= 409 (:status r)))
        (is (some? hit))
        (is (str/includes? (:detail hit) "annual return")
            "the catalog's own reason reaches the operator")
        (is (= :not-catalogued (get-in r [:body :year-end-adjustment :answer])))))))

(deftest the-body-cannot-say-whose-year-end-this-is
  (doseq [k [:client-id :employer :employer-id :contract/employer]]
    (testing (str k)
      (let [r (nen (nen-seeded) "did:key:zAlice"
                   (pr-str {:contract-id "c-1-nen" :year "2026"
                            :final-payment-of-year? true k "emp-2"}))]
        (is (= 400 (:status r)))
        (is (str/includes? (get-in r [:body :error]) "verified caller"))))))

(deftest a-year-end-request-must-name-a-year
  (doseq [b [(pr-str {:contract-id "c-1-nen"})
             (pr-str {:contract-id "c-1-nen" :year "  "})
             (pr-str {:contract-id "c-1-nen" :year 2026})
             "not edn ["
             "[:a :vector]"]]
    (testing (pr-str b)
      (is (= 400 (:status (nen (nen-seeded) "did:key:zAlice" b)))))))

(deftest a-string-is-not-a-declaration-at-the-edge-either
  (testing "the caller who sent \"true\" is told, and the caller who bypasses
            this surface still cannot buy a pass — `payroll.nenmatsu`
            normalises it to nil, which HOLDS"
    (doseq [bad ["true" 1 :yes]]
      (testing (pr-str bad)
        (let [r (nen (nen-seeded) "did:key:zAlice"
                     (nen-body :final-payment-of-year? bad))]
          (is (= 400 (:status r)))
          (is (str/includes? (get-in r [:body :error]) "true or false")))
        (let [r (nen (nen-seeded) "did:key:zAlice"
                     (nen-body :year-end-adjustment-settled? bad))]
          (is (= 400 (:status r))))))))

(deftest the-op-is-hardcoded-and-a-body-naming-another-cannot-reach-it
  (testing "a body asking for :disburse-wages assesses a year end"
    (let [r (nen (nen-seeded) "did:key:zAlice" (nen-body :op :disburse-wages))]
      (is (= 200 (:status r)))
      (is (= :owed (get-in r [:body :year-end-adjustment :answer]))))))

(deftest an-assessment-in-the-ledger-is-not-a-payroll-run-that-paid-nothing
  (testing "an assessment record has no gross and no period. Rendered like a
            run it would read as a payment of nothing — the worst lie this
            ledger could tell, told about the op whose whole point is not to
            invent figures"
    (let [st (nen-seeded)]
      (is (= 200 (:status (edge/submit-payroll-run-core!
                           st :ephemeral allowlist "did:key:zAlice" (body)))))
      (is (= 200 (:status (nen st "did:key:zAlice" (nen-body)))))
      (let [entries (get-in (edge/ledger-core st allowlist "did:key:zAlice")
                            [:body :entries])
            assessment (last entries)]
        (is (= 2 (count entries)))
        (is (= [:draft-payroll-run :assess-year-end-adjustment] (mapv :op entries)))
        (is (= "2026" (:year assessment)))
        (is (nil? (:gross assessment)))
        (is (= :owed (get-in assessment [:year-end-adjustment :answer])))
        (testing "and the payroll run is not reported as having a year-end answer"
          (is (= :not-assessed
                 (get-in (first entries) [:year-end-adjustment :answer])))
          (is (false? (get-in (first entries)
                              [:year-end-adjustment :answerable?]))))))))

(deftest year-end-summary-of-a-verdict-that-carried-no-assessment
  (testing "`not assessed` is not an answer and must not print as one"
    (is (= {:answer :not-assessed :answerable? false}
           (edge/year-end-summary nil)))
    (is (= {:answer :not-assessed :answerable? false}
           (edge/year-end-summary {:tax {:jurisdiction [:jp]}})))))

(deftest a-year-end-assessment-is-visible-in-the-contract-history
  (let [st (nen-seeded)]
    (nen st "did:key:zAlice" (nen-body))
    (let [r (edge/run-verdict-core st allowlist "did:key:zAlice" "c-1-nen")]
      (is (= 200 (:status r)))
      (is (= [:assess-year-end-adjustment] (mapv :op (get-in r [:body :history]))))
      (is (= ["2026"] (mapv :year (get-in r [:body :history])))))))
