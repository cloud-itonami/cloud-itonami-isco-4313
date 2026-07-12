(ns payroll.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.store :as store]
            [payroll.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-1" :name "Hanako's Bakery"})
    (store/register-contract! st (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))
;; registered hours: 14h × 2000 = 28000 gross

(defn- clean-proposal []
  {:op :draft-payroll-run :effect :propose :contract-id "c-1"
   :period "2026-07" :gross 28000 :deductions 3000 :net 25000
   :confidence 0.9 :stake :low})

(deftest ok-on-clean-recomputable-run
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {} (clean-proposal) st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-employer
  (let [st (fresh-store)
        v (governor/check {:client-id "no-such-employer"} {} (clean-proposal) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-missing-contract
  (testing "a payroll run without a contract is invented employment"
    (let [st (fresh-store)
          v (governor/check {:client-id "emp-1"} {}
                            (assoc (clean-proposal) :contract-id nil) st)]
      (is (:hard? v))
      (is (some #(= :no-contract (:rule %)) (:violations v))))))

(deftest hard-on-unknown-contract
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :contract-id "c-999") st)]
    (is (:hard? v))
    (is (some #(= :unknown-contract (:rule %)) (:violations v)))))

(deftest hard-on-contract-of-another-employer
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "emp-2" :name "Taro's Garage"})
    (let [v (governor/check {:client-id "emp-2"} {} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :contract-wrong-employer (:rule %)) (:violations v))))))

(deftest hard-on-wage-mismatch
  (testing "fair pay is arithmetic, not opinion — an inflated or shorted
            gross is held even at confidence 0.99"
    (let [st (fresh-store)
          v (governor/check {:client-id "emp-1"} {}
                            (assoc (clean-proposal) :gross 27999 :net 24999
                                   :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :wage-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-net-mismatch
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :net 26000) st)]
    (is (:hard? v))
    (is (some #(= :net-mismatch (:rule %)) (:violations v)))))

(deftest monthly-contract-ignores-timesheet-hours
  (testing "kotoba.labor semantics pass through: monthly rate is the gross"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "emp-3" :name "Studio K"})
      (store/register-contract! st (labor/contract "c-3" "worker-3" "emp-3" "editor" :monthly 300000))
      (let [v (governor/check {:client-id "emp-3"} {}
                              {:op :draft-payroll-run :effect :propose :contract-id "c-3"
                               :period "2026-07" :gross 300000 :deductions 0 :net 300000
                               :confidence 0.9 :stake :low} st)]
        (is (:ok? v))))))

(deftest escalates-wage-disbursement
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          {:op :disburse-wages :effect :propose
                           :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          {:op :reconcile-timesheets :effect :propose
                           :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
