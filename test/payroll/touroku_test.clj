(ns payroll.touroku-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.chingin :as chingin]
            [payroll.fixtures :as f]
            [payroll.governor :as governor]
            [payroll.store :as store]
            [payroll.touroku :as touroku]))

(def ^:private minimal
  {:contract/id "c-1" :contract/worker "w-1"
   :contract/wage-type :monthly :contract/rate 280000})

;; ---------------------------------------------------------------------------
;; Nothing is defaulted
;; ---------------------------------------------------------------------------

(deftest an-absent-coverage-flag-is-admitted-as-absent
  (testing "the single most important property here. A registration layer
            that helpfully filled it in with `false` would convert `nobody
            looked` into `not a 被保険者` — the exact substitution the whole
            actor refuses"
    (let [r (touroku/admit-contract "emp-1" minimal)]
      (is (= :ok (:touroku/status r)))
      (doseq [k [:employment/health-insurance-insured?
                 :employment/care-insurance-second-category?
                 :employment/employees-pension-insured?
                 :employment/employment-insurance-insured?
                 :employment/year-end-declaration-filed?]]
        (is (not (contains? (:touroku/record r) k))
            (str k " was defaulted"))))))

(deftest a-registered-false-is-written-and-is-not-the-same-as-absent
  (let [r (touroku/admit-contract
           "emp-1" (assoc minimal :employment/health-insurance-insured? false))]
    (is (= :ok (:touroku/status r)))
    (is (contains? (:touroku/record r) :employment/health-insurance-insured?))
    (is (false? (:employment/health-insurance-insured? (:touroku/record r))))))

(deftest a-string-true-is-refused-rather-than-coerced
  (testing "`payroll.shakai-hoken/declared` already normalises it to nil,
            which holds. This is the half that TELLS the operator"
    (let [r (touroku/admit-contract
             "emp-1" (assoc minimal :employment/health-insurance-insured? "true"))]
      (is (= :refused (:touroku/status r)))
      (is (str/includes? (:touroku/why r) "健康保険")))))

;; ---------------------------------------------------------------------------
;; Ownership
;; ---------------------------------------------------------------------------

(deftest the-employer-is-stamped-and-a-body-naming-one-is-refused
  (testing "refused and not dropped — the discipline
            `payroll.edge.endpoints` states for the run route, at the other
            place a caller can hand this actor an employer id"
    (is (= "emp-1" (:contract/employer
                    (:touroku/record (touroku/admit-contract "emp-1" minimal)))))
    (doseq [k touroku/employer-naming-keys]
      (let [r (touroku/admit-contract "emp-1" (assoc minimal k "emp-2"))]
        (is (= :refused (:touroku/status r)) k)
        (is (str/includes? (:touroku/why r) "検証済みの呼び出し元"))))))

;; ---------------------------------------------------------------------------
;; Unknown keys
;; ---------------------------------------------------------------------------

(deftest a-key-this-layer-does-not-read-is-refused
  (testing "an underscore where a hyphen belongs would otherwise be admitted,
            write a key nothing reads, and leave the operator looking at a
            screen that says the coverage is still unregistered while their
            form said it went through"
    (let [r (touroku/admit-contract
             "emp-1" (assoc minimal :employment/health_insurance_insured? true))]
      (is (= :refused (:touroku/status r)))
      (is (= [:employment/health_insurance_insured?] (:touroku/unknown-keys r))))))

;; ---------------------------------------------------------------------------
;; The field inventory
;; ---------------------------------------------------------------------------

(deftest required-fields-are-required
  (doseq [k [:contract/id :contract/worker :contract/wage-type :contract/rate]]
    (is (= :refused (:touroku/status (touroku/admit-contract
                                      "emp-1" (dissoc minimal k))))
        k)))

(deftest labor-validate-contract-gets-the-last-word
  (testing "the same function the governor's rule 3 runs, so a contract
            admitted here cannot be one the governor would later call
            invalid"
    (let [r (touroku/admit-contract "emp-1" (assoc minimal
                                                   :contract/wage-type :piece-rate))]
      (is (= :refused (:touroku/status r))))))

(deftest a-standard-remuneration-must-be-positive-whole-yen
  (is (= :ok (:touroku/status
              (touroku/admit-contract
               "emp-1" (assoc minimal
                              :employment/standard-remuneration-monthly-yen 280000)))))
  (doseq [bad [0 -1 280000.5 "280000"]]
    (is (= :refused (:touroku/status
                     (touroku/admit-contract
                      "emp-1" (assoc minimal
                                     :employment/standard-remuneration-monthly-yen bad))))
        (pr-str bad))))

(deftest a-standard-remuneration-month-must-be-the-one-shape
  (is (= :ok (:touroku/status
              (touroku/admit-contract
               "emp-1" (assoc minimal
                              :employment/standard-remuneration-month "2026-07")))))
  (doseq [bad ["2026-7" "2026/07" "令和8年7月" "2026-13"]]
    (is (= :refused (:touroku/status
                     (touroku/admit-contract
                      "emp-1" (assoc minimal
                                     :employment/standard-remuneration-month bad))))
        bad)))

(deftest a-payee-name-must-be-halfwidth-and-is-never-transliterated
  (is (= :ok (:touroku/status
              (touroku/admit-contract
               "emp-1" (assoc minimal :bank/payee-name-kana "ｶｸｳ ｼﾖｳｼﾞ")))))
  (let [r (touroku/admit-contract "emp-1" (assoc minimal
                                                 :bank/payee-name-kana "カクウ"))]
    (is (= :refused (:touroku/status r)))
    (is (str/includes? (:touroku/why r) "半角"))))

(deftest the-timesheet-inventory-is-generated-from-the-premium-facts
  (testing "a fact added to `payroll.chingin/premiums` must be registerable,
            and a hand-kept second list is the one that goes stale"
    (let [keys* (set (map :field/key touroku/timesheet-fields))]
      (doseq [p chingin/premiums
              :when (= :timesheet (:premium/on p))]
        (is (contains? keys* (:premium/key p)) (:premium/key p))))))

(deftest a-premium-fact-can-be-registered-and-says-what-it-will-cost
  (let [r (touroku/admit-timesheet
           "emp-1" {:ts/worker f/worker :ts/date "2026-08-01" :ts/hours 8
                    :ts/overtime-hours 3}
           [(f/contract)])]
    (is (= :ok (:touroku/status r)))
    (is (= 3 (:ts/overtime-hours (:touroku/record r))))
    (is (str/includes? (:field/holds
                        (first (filter #(= :ts/overtime-hours (:field/key %))
                                       touroku/timesheet-fields)))
                       "保留"))))

;; ---------------------------------------------------------------------------
;; Tenant isolation on the write side
;; ---------------------------------------------------------------------------

(deftest a-timesheet-for-another-employers-worker-is-refused
  (testing "a timesheet is the only admissible basis for an hourly wage, so
            an entry admitted against another employer's worker would move
            that worker's gross and hold their honest run for
            `:wage-mismatch`"
    (let [r (touroku/admit-timesheet
             "emp-1" {:ts/worker "他社の従業員" :ts/date "2026-08-01" :ts/hours 8}
             [(f/contract)])]
      (is (= :refused (:touroku/status r)))
      (is (str/includes? (:touroku/why r) "他社の従業員")))))

(deftest a-timesheet-for-a-known-worker-is-admitted
  (is (= :ok (:touroku/status
              (touroku/admit-timesheet
               "emp-1" {:ts/worker f/worker :ts/date "2026-08-01" :ts/hours 8}
               [(f/contract)])))))

;; ---------------------------------------------------------------------------
;; The checklist
;; ---------------------------------------------------------------------------

(deftest the-gap-list-names-what-each-absence-costs
  (testing "a list of unregistered keys is a chore; a list saying which
            refusal each one causes is a work queue in priority order"
    (let [gaps (touroku/registration-gaps minimal)]
      (is (seq gaps))
      (is (every? :gap/consequence gaps))
      (is (contains? (set (map :gap/key gaps))
                     :employment/health-insurance-insured?))
      (is (str/includes?
           (:gap/consequence (first (filter #(= :employment/health-insurance-insured?
                                                (:gap/key %))
                                            gaps)))
           "「該当しない」ではない")))))

(deftest a-fully-registered-contract-has-no-gaps-worth-showing
  (let [gaps (touroku/registration-gaps (f/contract))]
    (is (= #{:employment/recipient-residency :employment/paid-in
             :contract/allowances :contract/commuting-allowance
             ;; 住民税 is left unregistered on purpose, and the fixture is
             ;; the only place that decision is visible: a test that wants
             ;; the payslip line to carry a figure registers the obligation
             ;; itself, so a run that carries one is a run something
             ;; classified rather than one the fixture classified for it.
             :employment/resident-tax-obligation}
           (set (map :gap/key gaps)))
        "only the facts the fixture deliberately leaves unregistered")
    (testing "and the 住民税 gap says what its absence IS, since 未登録 there is
              neither 対象外 nor a default to 特別徴収"
      (let [g (first (filter #(= :employment/resident-tax-obligation
                                 (:gap/key %))
                             gaps))]
        (is (str/includes? (:gap/consequence g) "まだ誰も分類していない"))
        (is (str/includes? (:gap/consequence g) "「対象外」ではない"))))))

(deftest an-employer-with-no-jurisdiction-is-a-named-gap
  (let [gaps (touroku/employer-gaps {:client-id "emp-1" :name "x"})]
    (is (= [:jurisdiction] (mapv :gap/key gaps)))
    (is (str/includes? (:gap/consequence (first gaps)) "調べていない"))))

(deftest admitting-is-not-governing
  (testing "an admission layer that could make a run PASS would be a second
            governor. This one can only make a run possible: a contract with
            every coverage flag absent is admitted and the run is still held"
    (let [r (touroku/admit-contract "emp-1" minimal)
          st (store/mem-store)]
      (is (= :ok (:touroku/status r)))
      (store/register-client! st (f/employer))
      (store/register-contract! st (:touroku/record r))
      (let [v (governor/check
               {:client-id f/employer-id} {}
               (f/proposal {:contract-id "c-1" :gross 280000
                            :net (- 280000 f/deduction-total)})
               st)]
        (is (:hard? v))
        (is (some #(= :social-insurance-coverage-not-observed (:rule %))
                  (:violations v)))))))
