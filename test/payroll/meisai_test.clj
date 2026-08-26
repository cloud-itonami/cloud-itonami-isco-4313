(ns payroll.meisai-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.fixtures :as f]
            [payroll.meisai :as meisai]
            [payroll.provenance :as prov]))

(defn- line [m k]
  (some #(when (= k (:line/key %)) (:line/figure %)) (:meisai/deductions m)))

(deftest a-clean-run-derives-gross-and-net-and-declares-the-rest
  (let [m (f/lines {:verdict (f/verdict-for)})]
    (testing "gross and net are the only figures this repository computed"
      (is (= :derived (:figure/provenance (:meisai/gross m))))
      (is (= f/gross (prov/amount (:meisai/gross m))))
      (is (= :derived (:figure/provenance (:meisai/net m))))
      (is (= f/net (prov/amount (:meisai/net m)))))
    (testing "every deduction is :declared, including the one bounded by a
              statutory rate — the yen figure is still not ours"
      (doseq [d (:meisai/deductions m)]
        (is (= :declared (:figure/provenance (:line/figure d)))
            (:line/label d))))
    (is (meisai/payable? m))))

(deftest the-income-tax-is-never-derived-however-clean-the-run
  (testing "taxlaw records :taxlaw/amount-checked? false because 別表第二 /
            別表第五 were not read. A payslip that printed 6000 the same way
            it prints a figure computed from a rate in an Act would be
            laundering it through the presentation layer"
    (let [f* (line (f/lines {:verdict (f/verdict-for)}) :income-tax-withheld)]
      (is (= :declared (:figure/provenance f*)))
      (is (prov/unverified? f*))
      (is (str/includes? (:figure/why f*) "別表")))))

(deftest a-scheme-the-worker-is-not-covered-by-is-not-a-zero
  (testing ":not-covered is the absence of a line, not a 0-yen line"
    (let [st (f/fresh-store
              {:contract-overrides
               {:employment/care-insurance-second-category? false}})
          p (f/proposal {:care-insurance-withheld nil})
          m (f/lines {:contract* (f/contract
                                  {:employment/care-insurance-second-category? false})
                      :run p
                      :verdict (f/verdict-for st p)})
          care (line m :care-insurance-withheld)]
      (is (= :not-applicable (:figure/provenance care)))
      (is (nil? (prov/amount care)))
      (is (not (prov/unverified? care))))))

(deftest an-unobserved-coverage-flag-holds-the-line-and-names-the-key
  (testing "`見ていない` alone is not an instruction"
    (let [st (f/fresh-store
              {:contract-overrides
               {:employment/health-insurance-insured? nil}})
          p (f/proposal)
          m (f/lines {:contract* (f/contract {:employment/health-insurance-insured? nil})
                      :run p :verdict (f/verdict-for st p) :disposition :hold})
          hi (line m :health-insurance-withheld)]
      (is (= :held (:figure/provenance hi)))
      (is (str/includes? (:figure/why hi) "employment/health-insurance-insured?"))
      (is (not (meisai/payable? m))))))

(deftest one-held-line-blocks-the-deduction-total
  (testing "a total over figures where some are unknown is not the total, and
            a caller handed a number cannot tell"
    (let [st (f/fresh-store
              {:contract-overrides {:employment/health-insurance-insured? nil}})
          p (f/proposal)
          m (f/lines {:contract* (f/contract {:employment/health-insurance-insured? nil})
                      :run p :verdict (f/verdict-for st p) :disposition :hold})]
      (is (= :unknown (:figure/provenance (:meisai/deduction-total m))))
      (is (nil? (prov/amount (:meisai/deduction-total m))))
      (testing "and therefore the net line too"
        (is (= :unknown (:figure/provenance (:meisai/net m))))))))

(deftest the-net-line-refuses-when-the-declared-net-disagrees
  (testing "`:deductions` is a single number nobody forces to equal the sum of
            the five withholding fields. A payslip is the last place that
            disagreement should be resolved silently in favour of either"
    (let [p (f/proposal {:net (+ f/net 1000)})
          m (f/lines {:run p :verdict (f/verdict-for (f/fresh-store) (f/proposal))})]
      (is (= :held (:figure/provenance (:meisai/net m))))
      (is (str/includes? (:figure/why (:meisai/net m)) (str f/net)))
      (is (str/includes? (:figure/why (:meisai/net m)) (str (+ f/net 1000))))
      (is (not (meisai/payable? m))))))

(deftest a-run-with-no-jurisdiction-reports-the-tax-as-unknown-not-zero
  (testing "no withholding law was consulted; that is not a finding that
            nothing is owed"
    (let [st (f/fresh-store {:employer-overrides {:jurisdiction nil}})
          p (f/proposal {:income-tax-withheld nil})
          m (f/lines {:run p :verdict (f/verdict-for st p)})
          tax (line m :income-tax-withheld)]
      (is (= :unknown (:figure/provenance tax)))
      (is (str/includes? (:figure/why tax) "適用なしの判断ではない")))))

(deftest a-held-run-with-no-figures-at-all-produces-lines-that-say-so
  (testing "a held ledger entry carries a proposal now, but a run held before
            it reached one produces nothing — and the lines must still be
            legible rather than a screen of nils"
    (let [m (meisai/lines {:contract (f/contract) :timesheets []
                           :run nil :verdict nil :disposition :hold})]
      (is (= :held (:figure/provenance (:meisai/gross m))))
      (is (every? #(contains? #{:unknown :held}
                              (:figure/provenance (:line/figure %)))
                  (:meisai/deductions m)))
      (is (not (meisai/payable? m))))))

(deftest payable-requires-a-commit-as-well-as-clean-figures
  (testing "a run can have every figure resolved and still be awaiting a
            signature"
    (let [v (f/verdict-for)]
      (is (meisai/payable? (f/lines {:verdict v :disposition :commit})))
      (is (not (meisai/payable? (f/lines {:verdict v :disposition :request-approval})))))))

(deftest payable-does-not-require-every-figure-to-be-derived
  (testing "every payslip there will ever be carries a :declared income tax.
            A predicate that refused those is one nobody can use and
            therefore one everybody routes around"
    (let [m (f/lines {:verdict (f/verdict-for)})]
      (is (pos? (:meisai/unverified m)))
      (is (meisai/payable? m)))))

(deftest an-unaccounted-premium-fact-holds-the-gross
  (let [ts [(assoc (labor/timesheet f/worker "2026-08-01" 8)
                   :ts/overtime-hours 4)]
        st (f/fresh-store {:timesheets ts})
        p (f/proposal)
        m (f/lines {:timesheets ts :run p :verdict (f/verdict-for st p)
                    :disposition :hold})]
    (is (= :held (:figure/provenance (:meisai/gross m))))
    (is (str/includes? (:figure/why (:meisai/gross m)) "時間外労働"))
    (is (not (meisai/payable? m)))))

(deftest the-deduction-lines-are-in-payslip-order
  (testing "a vector rather than a map, so the order does not change between
            runtimes"
    (is (= [:income-tax-withheld :health-insurance-withheld
            :care-insurance-withheld :employees-pension-withheld
            :employment-insurance-withheld :resident-tax-withheld]
           (mapv :line/key meisai/deduction-lines))))
  (testing "住民税 is last and is neither a social-insurance scheme nor a tax
            this actor computes — it is a registered municipality notice"
    (let [l (last meisai/deduction-lines)]
      (is (= :resident-tax (:line/kind l)))
      (is (nil? (:line/scheme l))))))

(deftest an-unassessed-resident-tax-is-unknown-and-not-zero
  (testing "a run for which no municipality notice was consulted has an
            unanswered question about a lawful deduction, and `payable?`
            refuses it — which is the enforcement, since there is
            deliberately no governor rule for 住民税"
    (let [m (f/lines {:verdict (f/verdict-for) :juminzei :none})
          f* (:line/figure (last (:meisai/deductions m)))]
      (is (= :unknown (:figure/provenance f*)))
      (is (nil? (:figure/amount f*)))
      (is (str/includes? (:figure/why f*) "住民税が無い」ではない"))
      (is (not (meisai/payable? m))))))
