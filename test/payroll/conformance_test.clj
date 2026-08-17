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
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.store :as store]
            [payroll.governor :as governor]
            [governor.core :as gov]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-1" :name "Hanako's Bakery"})
    (store/register-client! st {:client-id "emp-2" :name "Taro's Garage"})
    (store/register-contract! st (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000))
    (store/register-contract! st (labor/contract "c-2" "worker-2" "emp-2" "mechanic" :hourly 2500))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))
;; registered hours for worker-1: 14h × 2000 = 28000 gross

(def ^:private clean
  {:op :draft-payroll-run :effect :propose :contract-id "c-1"
   :period "2026-07" :gross 28000 :deductions 3000 :net 25000
   :confidence 0.9 :stake :low})

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
    :proposal {:op :review :effect :propose}}])

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

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; evidence floor: a conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing.
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 1) "no clean case")
    (is (>= (count (filter :hard? vs)) 7) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 3) "escalation under-covered")))

(deftest escalation-carries-a-reason
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:escalate? v)]
    (testing (str name)
      (is (some? (:escalation-reason v))))))
