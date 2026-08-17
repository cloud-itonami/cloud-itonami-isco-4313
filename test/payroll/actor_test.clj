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

(defn- jp-store
  "An employer that declares [:jp] and a contract carrying the facts
  所得税法 第百八十三条第一項 turns on."
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
                 :income-tax-withheld 8420}
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
