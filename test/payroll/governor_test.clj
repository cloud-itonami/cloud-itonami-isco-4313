(ns payroll.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
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

;; ---------------------------------------------------------------------------
;; 源泉徴収 — 所得税法 第百八十三条第一項, via kotoba.taxlaw
;; ---------------------------------------------------------------------------

(defn- jp-store
  "Same fixture, but the employer DECLARES where it pays wages and the
  contract carries the two facts the article turns on. Both are registered by
  the operator; neither is proposed by the advisor.

  `overrides` patches the registered contract, so a test can declare a
  non-resident worker or a payment made abroad."
  ([] (jp-store {} {}))
  ([client-overrides contract-overrides]
   (let [st (store/mem-store)]
     (store/register-client! st (merge {:client-id "emp-jp" :name "Hanako's Bakery"
                                        :jurisdiction [:jp]}
                                       client-overrides))
     (store/register-contract!
      st (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
                {:employment/recipient-residency :resident
                 :employment/paid-in :domestic}
                contract-overrides))
     (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
     (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
     st)))

(defn- jp-proposal [& {:as extra}]
  (merge {:op :draft-payroll-run :effect :propose :contract-id "c-jp"
          :period "2026-07" :gross 28000 :deductions 3000 :net 25000
          :confidence 0.9 :stake :low}
         extra))

(deftest hard-when-a-jp-payroll-run-does-not-account-for-withheld-income-tax
  (testing "所得税法 第百八十三条第一項 — 「その支払の際、その給与等について
            所得税を徴収し」。計上の無い run は零税額の run ではなく未回答"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))
      (testing "the hold names the article it rests on"
        (is (some #(clojure.string/includes? (:detail %) "所得税法 第百八十三条第一項")
                  (:violations v)))))))

(deftest no-confidence-buys-past-the-withholding-hold
  (testing "a HARD rule is never escalatable — an approver cannot be invited
            to wave through a payroll run with no withholding accounted for"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :confidence 0.99) st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (not (:ok? v))))))

(deftest ok-when-the-run-accounts-for-withheld-income-tax
  (let [st (jp-store)
        v (governor/check {:client-id "emp-jp"} {}
                          (jp-proposal :income-tax-withheld 8420) st)]
    (is (:ok? v))
    (is (not (:hard? v)))))

(deftest zero-withheld-is-an-answer-and-a-malformed-amount-is-not
  (testing "zero is accepted: the article says collect THE tax on that 給与等,
            and neither taxlaw nor this governor read 別表第二"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 0) (jp-store))]
      (is (:ok? v))))
  (testing "a negative or non-numeric amount is not an accounting"
    (doseq [bad [-1 "8420"]]
      (let [v (governor/check {:client-id "emp-jp"} {}
                              (jp-proposal :income-tax-withheld bad) (jp-store))]
        (is (:hard? v) (str "should hold " (pr-str bad)))
        (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))))))

(deftest hard-on-a-jurisdiction-nobody-catalogued
  (testing "declaring an unknown jurisdiction must not be the cheap way past
            the withholding rule — an unchecked jurisdiction is a hold"
    (let [st (jp-store {:jurisdiction [:atlantis]} {})
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :unchecked-jurisdiction (:rule %)) (:violations v)))
      (is (not (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))
          "taxlaw could not check it, so this governor must not claim it did"))))

;; ---------------------------------------------------------------------------
;; Where the governor deliberately does NOT hold, the verdict says so
;; ---------------------------------------------------------------------------

(deftest an-employer-with-no-declared-jurisdiction-is-not-held-but-is-reported
  (testing "nobody asserted where these wages are paid, so no withholding law
            was consulted — and that must not look like a clean check"
    (let [st (fresh-store)                       ; emp-1 declares no jurisdiction
          v (governor/check {:client-id "emp-1"} {} (clean-proposal) st)]
      (is (:ok? v) "not held: nothing was asserted")
      (is (nil? (get-in v [:tax :jurisdiction])))
      (is (= :not-declared (get-in v [:tax :withholding :taxlaw/coverage]))
          "`nobody looked` and `we looked and it was fine` must differ")
      (is (not (clojure.string/blank? (get-in v [:tax :withholding :taxlaw/why])))))))

(deftest a-payment-outside-the-read-article-is-not-held-and-says-why
  (testing "所得税法 第百八十三条第一項 binds a payer 「居住者に対し国内において」。
            A declared non-resident is outside it — and outside is NOT a
            finding that no obligation exists, because the provisions that
            govern those payments were never read"
    (doseq [[label overrides]
            [[:non-resident {:employment/recipient-residency :non-resident}]
             [:overseas {:employment/paid-in :overseas}]]]
      (testing (str label)
        (let [st (jp-store {} overrides)
              v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
          (is (:ok? v) "not held")
          (is (= :out-of-scope (get-in v [:tax :withholding :taxlaw/coverage])))
          (is (= "所得税法 第百八十三条第一項"
                 (get-in v [:tax :withholding :taxlaw/read-provision]))
              "the verdict names the article that WAS read, so a reader can
               see the limit of the check"))))))

(deftest silence-about-residency-does-not-exempt-a-run
  (testing "a registered contract that says nothing about residency or place
            stays IN scope — absence of a declaration is the unchecked case,
            and the unchecked case never buys the article's exclusion"
    (let [st (jp-store {} {:employment/recipient-residency nil
                           :employment/paid-in nil})
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v))))))

(deftest the-amount-is-never-certified
  (testing "an accounted-for run passes, but the verdict does not claim the
            AMOUNT was checked — 別表第二 / 別表第五 were not read"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 1) (jp-store))]
      (is (:ok? v))
      (is (false? (get-in v [:tax :withholding :taxlaw/amount-checked?]))
          "1 yen of withholding on 28000 of wages passes this gate; saying
           the amount was checked would be a lie the next reader acts on"))))

(deftest year-end-adjustment-is-reported-as-not-evaluated
  (testing "所得税法 第百九十条 is read and catalogued upstream and nothing
            here calls it. A rule that is silently never called looks exactly
            like a rule that was called and passed"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 8420) (jp-store))]
      (is (:ok? v))
      (is (= :not-evaluated (get-in v [:tax :year-end-adjustment :taxlaw/coverage])))
      (is (not (clojure.string/blank?
                (get-in v [:tax :year-end-adjustment :taxlaw/why])))))))

(deftest the-proposal-cannot-choose-its-own-jurisdiction
  (testing "the jurisdiction is the EMPLOYER's. An advisor that could pick one
            could pick the one whose rules it satisfies — here [:atlantis],
            which taxlaw cannot check and which would therefore silence the
            withholding rule"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :jurisdiction [:atlantis]) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v))
          "the proposal's jurisdiction must be ignored entirely")
      (is (= [:jp] (get-in v [:tax :jurisdiction]))))))

(deftest non-draft-ops-carry-no-tax-report-at-all
  (testing "a disbursement or reconciliation asserts nothing about a payment
            of 給与等, so there is nothing for taxlaw to answer about"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            {:op :reconcile-timesheets :effect :propose
                             :confidence 0.9} (jp-store))]
      (is (nil? (:tax v))))))
