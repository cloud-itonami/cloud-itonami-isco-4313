(ns payroll.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had drifted into reporting a HARD violation as escalatable,
  so an approval queue would show a permanently-refused payroll operation as
  awaiting sign-off. The drift was invisible through the actor's own graph —
  the router tests `:hard?` first — so no ordinary test caught it.

  This suite checks not WHAT the governor decided (the other suites do that)
  but that whatever it decided is internally consistent, across every
  disposition this actor has."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.nenmatsu :as nenmatsu]
            [payroll.store :as store]
            [payroll.governor :as governor]
            [governor.core :as gov]))

(def ^:private insured
  "社会保険: the operator-registered facts 健康保険法 / 厚生年金保険法 /
  労働保険徴収法 turn on. Contracts that carry them can be answered; contracts
  that do not are HELD, and both appear below."
  {:employment/health-insurance-insured? true
   :employment/employees-pension-insured? true
   :employment/care-insurance-second-category? false
   :employment/employment-insurance-insured? true
   :employment/standard-remuneration-monthly-yen 280000
   :employment/standard-remuneration-month "2026-06"})

(def ^:private contributions
  "What a run must declare for the three schemes `insured` covers.
  280000 × 183 / 2000 = 25620 exactly — 厚生年金保険法 第八十一条第四項."
  {:health-insurance-withheld 13860
   :employees-pension-withheld 25620
   :employment-insurance-withheld 168})

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-1" :name "Hanako's Bakery"})
    (store/register-client! st {:client-id "emp-2" :name "Taro's Garage"})
    ;; two employers that DECLARE where they pay wages — the asserted
    ;; condition the withholding rules fire on. emp-1 / emp-2 declare none,
    ;; which is itself a case worth pinning.
    (store/register-client! st {:client-id "emp-jp" :name "Studio Kotoba"
                                :jurisdiction [:jp]})
    (store/register-client! st {:client-id "emp-atl" :name "Atlantis Co"
                                :jurisdiction [:atlantis]})
    (store/register-contract! st (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000))
    ;; 賃金の基礎 (rule 15): a worker whose registered timesheet carries an
    ;; overtime hour `kotoba.labor/wages-for` provably does not read. The
    ;; contract is otherwise identical to c-1, so the case set carries the
    ;; two sides of rule 15 and nothing else differs between them.
    (store/register-contract! st (labor/contract "c-ot" "worker-ot" "emp-1" "baker" :hourly 2000))
    (store/register-timesheet! st (labor/timesheet "worker-ot" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-ot" "2026-07-02" 6))
    (store/register-timesheet!
     st (assoc (labor/timesheet "worker-ot" "2026-07-03" 0)
               :ts/overtime-hours 3))
    (store/register-contract! st (labor/contract "c-2" "worker-2" "emp-2" "mechanic" :hourly 2500))
    (store/register-contract!
     st (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :resident
                :employment/paid-in :domestic}
               insured))
    ;; NOT insured, so that the case set carries a run whose 所得税 is fine and
    ;; whose 社会保険 is unobserved — which is precisely the verdict that used
    ;; to commit.
    (store/register-contract!
     st (merge (labor/contract "c-jp-uninsured" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :resident
                :employment/paid-in :domestic}))
    (store/register-contract!
     st (merge (labor/contract "c-jp-nr" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :non-resident
                :employment/paid-in :overseas}
               ;; 所得税法 第百八十三条第一項 does not reach a non-resident paid
               ;; abroad. 健康保険法 and 厚生年金保険法 say nothing of the kind,
               ;; so the 社会保険 registrations stay — an exclusion under one
               ;; statute is not an exclusion under another.
               insured))
    (store/register-contract!
     st (labor/contract "c-atl" "worker-1" "emp-atl" "baker" :hourly 2000))
    ;; the one contract on which the 申告書 is actually registered. Every other
    ;; contract here leaves it unobserved, which is itself a case: an
    ;; assessment against any of them must HOLD.
    (store/register-contract!
     st (merge (labor/contract "c-jp-nen" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :resident
                :employment/paid-in :domestic
                :employment/year-end-declaration-filed? true}))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))
;; registered hours for worker-1: 14h × 2000 = 28000 gross

(def ^:private clean
  {:op :draft-payroll-run :effect :propose :contract-id "c-1"
   :period "2026-07" :gross 28000 :deductions 3000 :net 25000
   :confidence 0.9 :stake :low})

(def ^:private nen
  "What `payroll.advisor/mock-advisor` produces for the assessment op: the
  base proposal and nothing else. Carrying no contract and no facts is the
  property, not an omission."
  {:op :assess-year-end-adjustment :effect :propose :confidence 0.95 :stake :low})

(def ^:private cases
  [{:name :clean :request {:client-id "emp-1"} :proposal clean}

   {:name :hard/no-client :request {:client-id "nobody"} :proposal clean}

   {:name :hard/no-actuation :request {:client-id "emp-1"}
    :proposal (assoc clean :effect :direct-write)}

   {:name :hard/no-contract :request {:client-id "emp-1"}
    :proposal (assoc clean :contract-id nil)}

   {:name :hard/unknown-contract :request {:client-id "emp-1"}
    :proposal (assoc clean :contract-id "c-missing")}

   ;; the case that drove `:scope-key` into the shared library: the contract
   ;; carries :contract/employer, the request carries :client-id.
   {:name :hard/contract-wrong-employer :request {:client-id "emp-1"}
    :proposal (assoc clean :contract-id "c-2")}

   {:name :hard/wage-mismatch :request {:client-id "emp-1"}
    :proposal (assoc clean :gross 99999 :net 96999)}

   {:name :hard/net-mismatch :request {:client-id "emp-1"}
    :proposal (assoc clean :net 27000)}

   {:name :escalate/disburse-wages :request {:client-id "emp-1"}
    :proposal {:op :disburse-wages :effect :propose :confidence 0.9}}

   {:name :escalate/low-confidence :request {:client-id "emp-1"}
    :proposal {:op :review :effect :propose :confidence 0.3}}

   ;; a proposal that does not say how confident it is has not said it is
   ;; confident — the absent key must read as 0.0, never as trustworthy.
   {:name :escalate/no-confidence-key :request {:client-id "emp-1"}
    :proposal {:op :review :effect :propose}}

   ;; --- 源泉徴収 (所得税法 第百八十三条第一項) --------------------------------
   {:name :hard/income-tax-not-withheld :request {:client-id "emp-jp"}
    :proposal (assoc clean :contract-id "c-jp")}

   {:name :hard/unchecked-jurisdiction :request {:client-id "emp-atl"}
    :proposal (assoc clean :contract-id "c-atl")}

   ;; the two cases the governor deliberately does NOT hold. They belong in
   ;; the conformance set precisely because they are passes: a verdict that
   ;; passes for a reason must still be a well-formed verdict, and
   ;; `every-non-hold-tax-case-says-what-was-not-checked` below pins that the
   ;; reason is on it.
   {:name :ok/withholding-accounted-for :request {:client-id "emp-jp"}
    :proposal (merge clean contributions
                     {:contract-id "c-jp" :income-tax-withheld 8420})}

   {:name :ok/outside-the-read-article :request {:client-id "emp-jp"}
    :proposal (merge clean contributions {:contract-id "c-jp-nr"})}

   ;; --- 社会保険・労働保険 (健保法/厚年法/介護保険法/徴収法) ------------------
   ;;
   ;; The verdict that used to commit: 所得税 accounted for, and nothing said
   ;; about the other three quarters of the payslip.
   {:name :hard/social-insurance-coverage-not-observed
    :request {:client-id "emp-jp"}
    :proposal (assoc clean :contract-id "c-jp-uninsured"
                     :income-tax-withheld 8420)}

   ;; registered as insured, and the run declares no contributions.
   {:name :hard/social-insurance-not-accounted-for
    :request {:client-id "emp-jp"}
    :proposal (assoc clean :contract-id "c-jp" :income-tax-withheld 8420)}

   ;; 厚年法 第八十一条第四項 is the one rate this workspace read from a
   ;; statute, so this is the one amount it can refuse.
   {:name :hard/social-insurance-amount-contradicts-statutory-rate
    :request {:client-id "emp-jp"}
    :proposal (merge clean contributions
                     {:contract-id "c-jp" :income-tax-withheld 8420
                      :employees-pension-withheld 9999})}

   ;; --- 年末調整 (所得税法 第百九十条) ----------------------------------------
   ;;
   ;; The assessment op reads its contract and its three condition facts off
   ;; the REQUEST and the REGISTERED contract, never off the proposal, so
   ;; these cases carry a full request and a bare proposal — which is exactly
   ;; what `payroll.advisor/mock-advisor` produces for this op.
   {:name :hard/year-end-declaration-not-observed
    :request {:client-id "emp-jp" :contract-id "c-jp" :year "2026"
              :final-payment-of-year? true}
    :proposal nen}

   {:name :hard/final-payment-not-declared
    :request {:client-id "emp-jp" :contract-id "c-jp-nen" :year "2026"}
    :proposal nen}

   {:name :hard/unchecked-year-end-jurisdiction
    :request {:client-id "emp-atl" :contract-id "c-atl" :year "2026"
              :final-payment-of-year? true}
    :proposal nen}

   {:name :hard/year-end-jurisdiction-not-declared
    :request {:client-id "emp-1" :contract-id "c-1" :year "2026"
              :final-payment-of-year? true}
    :proposal nen}

   {:name :ok/year-end-owed
    :request {:client-id "emp-jp" :contract-id "c-jp-nen" :year "2026"
              :final-payment-of-year? true}
    :proposal nen}

   ;; `not yet` is a pass and `never` is a pass, and they are different
   ;; passes. Both belong here for the reason the two withholding passes do.
   {:name :ok/year-not-finished
    :request {:client-id "emp-jp" :contract-id "c-jp-nen" :year "2026"
              :final-payment-of-year? false}
    :proposal nen}

   ;; 賃金の基礎 (rule 15). The store below registers an overtime hour against
   ;; `worker-ot`, so the run whose gross ignores it is HELD — and the run
   ;; that cites a contract for a worker with no premium fact is not.
   {:name :hold/wage-basis-unaccounted
    :request {:client-id "emp-1"}
    :proposal {:op :draft-payroll-run :effect :propose :contract-id "c-ot"
               :period "2026-07" :gross 28000 :deductions 0 :net 28000
               :confidence 0.9 :stake :low}}])

(defn- verdict-for [{:keys [request proposal]}]
  (governor/check request {} proposal (fresh-store)))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:hard? v)]
    (testing (str name)
      (is (not (:escalate? v))
          "fair pay is arithmetic — an approver cannot be invited to wave it through")
      (is (not (:ok? v)))
      (is (seq (:violations v)) "a hold must say what it refused"))))

(deftest every-draft-case-reports-a-wage-basis
  ;; The same device as `every-non-hold-tax-case-says-what-was-not-checked`,
  ;; one rule over: an omitted report and a satisfied rule look the same, and
  ;; a monthly run whose timesheets were never read looks exactly like one
  ;; where they were.
  (doseq [{:keys [name proposal] :as c} cases
          :when (= :draft-payroll-run (:op proposal))]
    (testing (str name)
      (let [b (:wage-basis (verdict-for c))]
        (is (some? b) "a draft verdict with no wage-basis report")
        (is (contains? b :chingin/answer))
        (is (contains? b :chingin/certifiable?))
        (is (seq (:chingin/why b)))))))

(deftest every-wage-basis-refusal-is-held
  ;; answerable ⇔ not held, pinned in both directions — the equivalence
  ;; `every-year-end-answer-is-either-answered-or-held` pins for 年末調整.
  (doseq [{:keys [name proposal] :as c} cases
          :when (= :draft-payroll-run (:op proposal))
          :let [v (verdict-for c)
                b (:wage-basis v)]
          ;; only where the basis was actually evaluated — a run with no
          ;; registered contract is held for its own reason and the basis
          ;; report says `:no-registered-contract`
          :when (contains? #{:accounted-for :premium-not-priced :unknown-wage-type}
                           (:chingin/answer b))]
    (testing (str name)
      (if (:chingin/certifiable? b)
        (is (not-any? #(= :wage-basis-unaccounted (:rule %)) (:violations v)))
        (is (and (:hard? v)
                 (some #(contains? #{:wage-basis-unaccounted
                                     :wage-basis-uncomputable}
                                   (:rule %))
                       (:violations v)))
            "an uncertifiable wage basis that did not hold the run")))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; evidence floor: a conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing.
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 5) "no clean case")
    (is (>= (count (filter :hard? vs)) 17) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 3) "escalation under-covered")))

(deftest every-non-hold-tax-case-says-what-was-not-checked
  ;; The device that keeps `nobody looked` from printing the same verdict as
  ;; `we looked and it was fine`. Three of these cases pass for three
  ;; DIFFERENT reasons — no jurisdiction declared, the payment is outside the
  ;; one article that was read, and the withholding is accounted for — and a
  ;; reader must be able to tell which.
  (let [coverage (fn [client contract-id & {:as extra}]
                   (get-in (verdict-for
                            {:request {:client-id client}
                             :proposal (merge (assoc clean :contract-id contract-id)
                                              extra)})
                           [:tax :withholding :taxlaw/coverage]))]
    (is (= :not-declared (coverage "emp-1" "c-1"))
        "emp-1 declares no jurisdiction, so no withholding law was consulted")
    (is (= :out-of-scope (coverage "emp-jp" "c-jp-nr"))
        "declared outside 所得税法 第百八十三条第一項")
    (is (= :checked (coverage "emp-jp" "c-jp" :income-tax-withheld 8420))
        "consulted and satisfied")
    (testing "and every draft verdict records that 年末調整 was not evaluated"
      (doseq [[client cid] [["emp-1" "c-1"] ["emp-jp" "c-jp"]]]
        (is (= :not-evaluated
               (get-in (verdict-for {:request {:client-id client}
                                     :proposal (assoc clean :contract-id cid)})
                       [:tax :year-end-adjustment :taxlaw/coverage])))))))

(deftest escalation-carries-a-reason
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:escalate? v)]
    (testing (str name)
      (is (some? (:escalation-reason v))))))

(deftest every-year-end-answer-is-either-answered-or-held
  ;; The same device as `every-non-hold-tax-case-says-what-was-not-checked`,
  ;; one article over. Four of the assessment's nine answers are the ABSENCE
  ;; of an answer, and every one of them must be a HOLD — an assessment that
  ;; committed while saying `nobody registered the 申告書` would be the
  ;; unevaluated-rule defect wearing the evaluated rule's clothes.
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:nenmatsu v)]
    (testing (str name)
      (let [answer (get-in v [:nenmatsu :nenmatsu/answer])]
        (is (some? answer))
        (is (= (:hard? v) (contains? nenmatsu/refusals answer))
            "answerable ⇔ not held, in both directions")
        (is (= (:nenmatsu/answerable? (:nenmatsu v))
               (contains? nenmatsu/answers answer)))))))

(deftest the-year-end-cases-cover-both-sides
  ;; evidence floor: a set of assessment cases that all landed on one side
  ;; would pass while measuring nothing.
  (let [vs (keep :nenmatsu (map verdict-for cases))
        answers (map :nenmatsu/answer vs)]
    (is (>= (count vs) 6) "assessment cases under-covered")
    (is (>= (count (filter nenmatsu/refusals answers)) 4)
        "every refusal must appear, or the ⇔ above is half-tested")
    (is (>= (count (filter nenmatsu/answers answers)) 2))))

(deftest every-social-insurance-answer-is-either-answered-or-held
  ;; The same device as `every-year-end-answer-is-either-answered-or-held`,
  ;; four statutes over. `payroll.shakai-hoken` answers per scheme and every
  ;; refusal it can return is a HOLD — a run that committed while saying
  ;; `nobody registered whether this worker is a 被保険者` would be the
  ;; unevaluated-rule defect wearing the evaluated rule's clothes, which is
  ;; exactly what this actor did until 2026-08-18.
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)
                si (:social-insurance v)]
          :when si]
    (testing (str name)
      (let [answer (:shakai-hoken/answer si)]
        (is (some? answer))
        (is (not (clojure.string/blank? (str (or (:shakai-hoken/why si)
                                                 (:shakai-hoken/answer si)))))
            "a report nobody can read is not a report")
        (when (contains? #{:not-catalogued :refused} answer)
          (is (:hard? v) (str answer " must be a hold")))
        (when (= :answered answer)
          (is (not (some #(= "social-insurance"
                             (namespace (or (:rule %) :x)))
                         (:violations v)))))))))

(deftest the-social-insurance-cases-cover-both-sides
  ;; evidence floor: a case set that all landed on one side would pass while
  ;; measuring nothing. Five distinct answers exist and four must appear.
  (let [answers (->> cases (map verdict-for) (keep :social-insurance)
                     (map :shakai-hoken/answer) set)]
    (is (contains? answers :answered))
    (is (contains? answers :refused))
    (is (contains? answers :not-catalogued))
    (is (contains? answers :jurisdiction-not-declared))
    (is (>= (count answers) 4))))
