(ns payroll.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.actor :as actor]
            [payroll.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-1" :name "Hanako's Bakery"})
    (store/register-contract! st (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))

(deftest commits-a-clean-payroll-run-with-lib-computed-wages
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "emp-1" :op :draft-payroll-run :stake :low
                 :contract-id "c-1" :period "2026-07" :deductions 3000}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    ;; mock advisor computed gross via kotoba.labor: 14h × 2000
    (is (= 28000 (get-in result [:state :record :payload :gross])))
    (is (= 25000 (get-in result [:state :record :payload :net])))
    (is (= 1 (count (store/records-of st "emp-1"))))))

(deftest holds-invented-employment-without-committing
  (testing "no contract cited -> HARD hold, nothing written"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:client-id "emp-1" :op :draft-payroll-run :stake :low
                   :contract-id nil :period "2026-07"}
          result (actor/run-request! graph request {} "thread-2")]
      (is (= :done (:status result)))
      (is (nil? (get-in result [:state :record])))
      (is (empty? (store/records-of st "emp-1")))
      (is (= :hold (:disposition (:state result)))))))

(def ^:private insured
  "社会保険: the registrations that make the four contributions answerable.
  A contract without them is HELD — `holds-a-jp-run-with-no-social-insurance-
  registered` below runs exactly that case through the graph."
  {:employment/health-insurance-insured? true
                 :employment/employees-pension-insured? true
                 :employment/care-insurance-second-category? false
                 :employment/employment-insurance-insured? true
                 :employment/standard-remuneration-monthly-yen 280000
                 :employment/standard-remuneration-month "2026-06"})

(defn- jp-store
  "An employer that declares [:jp] and a contract carrying the facts
  所得税法 第百八十三条第一項 turns on, plus the 社会保険 registrations
  健康保険法 / 厚生年金保険法 / 労働保険徴収法 turn on."
  []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-jp" :name "Studio Kotoba"
                                :jurisdiction [:jp]})
    (store/register-contract!
     st (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :resident
                :employment/paid-in :domestic}
               insured))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))

(deftest holds-a-jp-run-that-does-not-account-for-withheld-income-tax
  (testing "end to end: the graph must not write a payroll record for a
            payment of 給与等 with no withholding accounted for"
    (let [st (jp-store)
          graph (actor/build-graph {:store st})
          request {:client-id "emp-jp" :op :draft-payroll-run :stake :low
                   :contract-id "c-jp" :period "2026-07" :deductions 3000}
          result (actor/run-request! graph request {} "thread-jp-1")]
      (is (= :done (:status result)))
      (is (= :hold (:disposition (:state result))))
      (is (nil? (get-in result [:state :record])))
      (is (empty? (store/records-of st "emp-jp")))
      (is (some #(= :income-tax-not-withheld (:rule %))
                (get-in result [:state :verdict :violations]))))))

(deftest commits-the-same-run-once-the-withholding-is-accounted-for
  (let [st (jp-store)
        graph (actor/build-graph {:store st})
        request {:client-id "emp-jp" :op :draft-payroll-run :stake :low
                 :contract-id "c-jp" :period "2026-07" :deductions 3000
                 :income-tax-withheld 8420
                 ;; 280000 × 183 / 2000 = 25620 (厚年法 第八十一条第四項)
                 :health-insurance-withheld 13860
                 :employees-pension-withheld 25620
                 :employment-insurance-withheld 168}
        result (actor/run-request! graph request {} "thread-jp-2")]
    (is (= :done (:status result)))
    (is (= 28000 (get-in result [:state :record :payload :gross])))
    (is (= 8420 (get-in result [:state :record :payload :income-tax-withheld]))
        "the committed record carries the withheld amount, so the ledger can
         later be asked what was withheld and when")
    (is (= 1 (count (store/records-of st "emp-jp"))))))

(deftest interrupts-then-commits-disbursement-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; real fund movement: always escalates
        request {:client-id "emp-1" :op :disburse-wages :stake :high}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "emp-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "emp-1")))))))

;; ---------------------------------------------------------------------------
;; 年末調整 through the graph
;; ---------------------------------------------------------------------------

(defn- nen-store [& {:keys [declaration]}]
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-jp" :name "Studio Kotoba"
                                :jurisdiction [:jp]})
    (store/register-contract!
     st (cond-> (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
          (some? declaration)
          (assoc :employment/year-end-declaration-filed? declaration)))
    st))

(deftest an-assessment-is-a-governed-op-and-not-a-bare-function
  (testing "it runs the same graph, commits a record and leaves a ledger
            entry — an assessment nobody can read back later is not one"
    (let [st (nen-store :declaration true)
          graph (actor/build-graph {:store st})
          request {:client-id "emp-jp" :op :assess-year-end-adjustment
                   :contract-id "c-jp" :year "2026"
                   :final-payment-of-year? true}
          r (actor/run-request! graph request {} "nen-1")]
      (is (= :done (:status r)))
      (is (= :commit (get-in r [:state :disposition])))
      (is (= :owed (get-in r [:state :verdict :nenmatsu :nenmatsu/answer])))
      (is (= 1 (count (store/records-of st "emp-jp"))))
      (let [entry (last (store/ledger st))]
        (is (= :commit (:disposition entry)))
        (is (= "2026" (:year entry))
            "the ledger stamp carries the year; this op has no period, and an
             assessment that cannot be attributed to a year is not one")
        (is (= "c-jp" (:contract-id entry)))
        (is (= 1 (count (store/run-history st "c-jp"))))))))

(deftest an-unobserved-declaration-holds-the-graph-and-writes-no-record
  (let [st (nen-store)
        graph (actor/build-graph {:store st})
        r (actor/run-request! graph
                              {:client-id "emp-jp" :op :assess-year-end-adjustment
                               :contract-id "c-jp" :year "2026"
                               :final-payment-of-year? true}
                              {} "nen-2")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (empty? (store/records-of st "emp-jp")))
    (testing "the hold is still in the ledger, with the year it refused about"
      (let [entry (last (store/ledger st))]
        (is (= :hold (:disposition entry)))
        (is (= "2026" (:year entry)))))))

(deftest a-payroll-run-ledger-entry-carries-no-year-key-at-all
  (testing "absent rather than nil, so a reader counting years is not handed
            one for an op that has none"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "emp-1" :op :draft-payroll-run
                                 :contract-id "c-1" :period "2026-07"
                                 :deductions 3000}
                          {} "thread-year")
      (is (not (contains? (last (store/ledger st)) :year))))))

;; ---------------------------------------------------------------------------
;; 社会保険 through the graph
;; ---------------------------------------------------------------------------

(defn- uninsured-store
  "The [:jp] fixture as it was before 2026-08-18: 所得税 facts registered, and
  nothing about 健康保険 / 介護保険 / 厚生年金 / 雇用保険."
  []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-jp" :name "Studio Kotoba"
                                :jurisdiction [:jp]})
    (store/register-contract!
     st (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
               {:employment/recipient-residency :resident
                :employment/paid-in :domestic}))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))

(deftest holds-a-jp-run-that-accounts-for-income-tax-and-nothing-else
  (testing "end to end: the graph must not write a payroll record for a run
            that accounts for one of the four withholdings a Japanese payslip
            carries. This request committed until 2026-08-18"
    (let [st (uninsured-store)
          graph (actor/build-graph {:store st})
          request {:client-id "emp-jp" :op :draft-payroll-run :stake :low
                   :contract-id "c-jp" :period "2026-07" :deductions 3000
                   :income-tax-withheld 8420}
          result (actor/run-request! graph request {} "thread-si-1")]
      (is (= :done (:status result)))
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "emp-jp")))
      (is (some #(= :social-insurance-coverage-not-observed (:rule %))
                (get-in result [:state :verdict :violations])))
      (testing "and the ledger keeps the refusal, with the report on it"
        (let [entry (last (store/ledger st))]
          (is (= :hold (:disposition entry)))
          (is (= :refused (get-in entry [:verdict :social-insurance
                                         :shakai-hoken/answer]))))))))

(deftest commits-the-same-run-once-all-four-are-answered
  (let [st (jp-store)
        graph (actor/build-graph {:store st})
        request {:client-id "emp-jp" :op :draft-payroll-run :stake :low
                 :contract-id "c-jp" :period "2026-07" :deductions 3000
                 :income-tax-withheld 8420
                 :health-insurance-withheld 13860
                 :employees-pension-withheld 25620
                 :employment-insurance-withheld 168}
        result (actor/run-request! graph request {} "thread-si-2")]
    (is (= :commit (get-in result [:state :disposition])))
    (testing "and the committed record carries every contribution, so the
              ledger can later be asked what actually left the payslip"
      (is (= {:income-tax-withheld 8420
              :health-insurance-withheld 13860
              :care-insurance-withheld nil
              :employees-pension-withheld 25620
              :employment-insurance-withheld 168}
             (select-keys (get-in result [:state :record :payload])
                          [:income-tax-withheld :health-insurance-withheld
                           :care-insurance-withheld :employees-pension-withheld
                           :employment-insurance-withheld]))))))
