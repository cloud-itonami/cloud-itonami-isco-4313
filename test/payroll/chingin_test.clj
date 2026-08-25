(ns payroll.chingin-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.chingin :as chingin]
            [payroll.provenance :as prov]))

(defn- monthly [] (labor/contract "c-1" "w-1" "emp-1" "engineer" :monthly 280000))
(defn- hourly [] (labor/contract "c-2" "w-2" "emp-1" "baker" :hourly 2000))

;; ---------------------------------------------------------------------------
;; The claim about the dependency is MEASURED, not asserted
;; ---------------------------------------------------------------------------

(deftest wages-for-really-does-ignore-what-this-namespace-says-it-ignores
  (testing "`formula` says `wages-for` reads :contract/rate and :ts/hours and
            nothing else. That is a claim about ANOTHER repository, and this
            repository's own deps.edn records what happens to claims about
            other repositories that nothing rechecks. So it is fed perturbed
            inputs rather than trusted"
    (let [c (monthly)
          ts [(labor/timesheet "w-1" "2026-08-01" 8)]
          baseline (labor/wages-for c ts)]
      (doseq [{k :premium/key on :premium/on} chingin/premiums]
        (let [perturbed (case on
                          :contract (labor/wages-for (assoc c k 50000) ts)
                          :timesheet (labor/wages-for c [(assoc (first ts) k 40)]))]
          (is (= baseline perturbed)
              (str "kotoba.labor/wages-for moved when " k
                   " changed — `formula` is wrong and rule 15 is checking "
                   "the wrong thing")))))))

(deftest a-monthly-contract-ignores-its-timesheets-entirely
  (testing "measured against the dependency: the hours do not appear in the
            figure at all"
    (let [c (monthly)]
      (is (= 280000 (labor/wages-for c [])))
      (is (= 280000 (labor/wages-for c [(labor/timesheet "w-1" "2026-08-01" 8)
                                        (labor/timesheet "w-1" "2026-08-02" 8)]))))))

;; ---------------------------------------------------------------------------
;; assess
;; ---------------------------------------------------------------------------

(deftest a-clean-monthly-run-is-certifiable-and-still-says-the-hours-were-not-read
  (let [a (chingin/assess {:contract (monthly)
                           :timesheets [(labor/timesheet "w-1" "2026-08-01" 8)]})]
    (is (= :accounted-for (:chingin/answer a)))
    (is (:chingin/certifiable? a))
    (testing "`the hours were read and agreed` and `the hours were never read`
              must not print the same"
      (is (false? (:chingin/reads-timesheets? a)))
      (is (= 1 (:chingin/timesheet-count a)))
      (is (str/includes? (:chingin/why a) "勤怠は一切読まれていない")))))

(deftest an-hourly-run-reports-that-the-hours-were-read
  (let [a (chingin/assess {:contract (hourly)
                           :timesheets [(labor/timesheet "w-2" "2026-08-01" 8)]})]
    (is (:chingin/certifiable? a))
    (is (true? (:chingin/reads-timesheets? a)))))

(deftest a-registered-overtime-hour-makes-the-figure-uncertifiable
  (testing "rule 4 proves the advisor did not invent the number; this is
            about whether the number is the wage"
    (let [a (chingin/assess
             {:contract (monthly)
              :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                  :ts/overtime-hours 3)]})]
      (is (= :premium-not-priced (:chingin/answer a)))
      (is (not (:chingin/certifiable? a)))
      (is (= [:ts/overtime-hours] (mapv :premium/key (:chingin/unaccounted a))))
      (is (= 3 (:premium/registered (first (:chingin/unaccounted a)))))
      (testing "and it names the article as UNREAD rather than pricing it"
        (is (str/includes? (:premium/provision-not-read
                            (first (:chingin/unaccounted a)))
                           "第三十七条"))))))

(deftest overtime-hours-are-summed-across-the-month
  (let [a (chingin/assess
           {:contract (monthly)
            :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                :ts/overtime-hours 2)
                         (assoc (labor/timesheet "w-1" "2026-08-02" 8)
                                :ts/overtime-hours 3)]})]
    (is (= 5 (:premium/registered (first (:chingin/unaccounted a)))))))

(deftest a-registered-zero-is-an-answer-and-does-not-hold
  (testing "an operator who registered `no overtime this month` has answered
            the question. Holding an answered question trains them to stop
            answering it"
    (let [a (chingin/assess
             {:contract (monthly)
              :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                  :ts/overtime-hours 0)]})]
      (is (:chingin/certifiable? a))
      (is (empty? (:chingin/unaccounted a))))))

(deftest a-malformed-premium-fact-does-hold
  (testing "a string where a number belongs is not the same as no premium
            fact and must not read as one"
    (let [a (chingin/assess
             {:contract (monthly)
              :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                  :ts/overtime-hours "3")]})]
      (is (not (:chingin/certifiable? a))))))

(deftest a-contract-level-allowance-holds-too
  (let [a (chingin/assess {:contract (assoc (monthly) :contract/allowances 20000)
                           :timesheets []})]
    (is (not (:chingin/certifiable? a)))
    (is (= [:contract/allowances] (mapv :premium/key (:chingin/unaccounted a))))))

(deftest several-unaccounted-facts-are-all-named
  (let [a (chingin/assess
           {:contract (assoc (monthly) :contract/allowances 20000
                             :contract/commuting-allowance 12000)
            :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                :ts/overtime-hours 3 :ts/night-hours 1)]})]
    (is (= 4 (count (:chingin/unaccounted a))))
    (is (str/includes? (:chingin/why a) "時間外労働"))
    (is (str/includes? (:chingin/why a) "通勤手当"))))

(deftest an-hourly-contract-does-not-hold-on-its-own-hours
  (testing ":ts/hours IS read by the hourly formula, so it is not in the
            unaccounted set — which is why `ignores` is derived by
            subtraction rather than typed"
    (let [a (chingin/assess {:contract (hourly)
                             :timesheets [(labor/timesheet "w-2" "2026-08-01" 8)]})]
      (is (:chingin/certifiable? a)))))

(deftest an-unknown-wage-type-is-its-own-refusal
  (let [a (chingin/assess {:contract {:contract/wage-type :piece-rate}
                           :timesheets []})]
    (is (= :unknown-wage-type (:chingin/answer a)))
    (is (not (:chingin/certifiable? a)))
    (is (nil? (:chingin/reads-timesheets? a)))))

(deftest a-nil-contract-refuses-rather-than-throwing
  (let [a (chingin/assess {:contract nil :timesheets []})]
    (is (= :unknown-wage-type (:chingin/answer a)))))

(deftest every-refusal-answer-is-in-the-refusals-set
  (testing "an answer that is in neither set makes `certifiable?` false and
            the governor holds — a new answer defaults to refused"
    (doseq [answer [:premium-not-priced :unknown-wage-type]]
      (is (contains? chingin/refusals answer)))
    (is (empty? (set/intersection chingin/answers chingin/refusals)))))

;; ---------------------------------------------------------------------------
;; gross-figure
;; ---------------------------------------------------------------------------

(deftest an-uncertifiable-basis-withholds-the-number-entirely
  (testing "not `:declared` with a footnote — a payslip is a document
            somebody pays from, and the footnote is read by the person who
            wrote it"
    (let [a (chingin/assess
             {:contract (monthly)
              :timesheets [(assoc (labor/timesheet "w-1" "2026-08-01" 8)
                                  :ts/overtime-hours 3)]})
          f (chingin/gross-figure a 280000 {:derived prov/derived
                                            :held prov/held})]
      (is (= :held (:figure/provenance f)))
      (is (nil? (prov/amount f))))))

(deftest a-certifiable-basis-with-a-number-is-derived
  (let [a (chingin/assess {:contract (monthly) :timesheets []})
        f (chingin/gross-figure a 280000 {:derived prov/derived :held prov/held})]
    (is (= :derived (:figure/provenance f)))
    (is (= 280000 (prov/amount f)))
    (is (str/includes? (:figure/source f) "wages-for"))))

(deftest a-certifiable-basis-with-no-number-is-still-held
  (testing "a held run carries no gross; the basis being fine does not
            conjure one"
    (let [a (chingin/assess {:contract (monthly) :timesheets []})
          f (chingin/gross-figure a nil {:derived prov/derived :held prov/held})]
      (is (= :held (:figure/provenance f))))))
