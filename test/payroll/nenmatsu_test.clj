(ns payroll.nenmatsu-test
  "年末調整 as a reading of 所得税法 第百九十条, before any governor or route
  touches it.

  What is being tested here is almost entirely what this namespace REFUSES to
  say. The article applies the year's over-withholding against, and collects
  the shortfall with, the final payment — and the year's correct tax comes
  from 別表 (税額表), which `kotoba.taxlaw` records as unread. So the
  interesting assertions are that no figure is produced, that the ceiling
  check is sound in exactly one direction, and that three different facts do
  not print as one `out of scope`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.set]
            [kotoba.taxlaw :as taxlaw]
            [payroll.nenmatsu :as nenmatsu]))

(defn- run
  "A committed payroll-run record as `payroll.actor`'s `:commit` node writes
  one."
  [& {:keys [contract-id period gross withheld op]
      :or {contract-id "c-1" period "2026-07" gross 280000 withheld 8420
           op :draft-payroll-run}}]
  {:client-id "emp-1" :op op :contract-id contract-id
   :payload {:op op :period period :gross gross :income-tax-withheld withheld}})

(defn- assess [& {:keys [jurisdiction declaration final? settled? year records
                         contract-id]
                  :or {jurisdiction [:jp] year "2026" contract-id "c-1"}}]
  (nenmatsu/assess
   {:jurisdiction jurisdiction
    :contract (when (some? declaration)
                {:employment/year-end-declaration-filed? declaration})
    :year year
    :request {:contract-id contract-id
              :final-payment-of-year? final?
              :year-end-adjustment-settled? settled?}
    :records (or records [])}))

;; ---------------------------------------------------------------------------
;; The nine answers, and which of them are answers
;; ---------------------------------------------------------------------------

(deftest every-answer-is-reachable-and-classified
  (let [cases
        {:owed (assess :declaration true :final? true)
         :settled (assess :declaration true :final? true :settled? true)
         :year-not-finished (assess :declaration true :final? false)
         :declaration-not-filed (assess :declaration false :final? true)
         :above-ceiling (assess :declaration true :final? true
                                :records [(run :gross 20000001)])
         :jurisdiction-not-declared (assess :jurisdiction nil :declaration true
                                            :final? true)
         :not-catalogued (assess :jurisdiction [:us] :declaration true :final? true)
         :declaration-not-observed (assess :final? true)
         :final-payment-not-declared (assess :declaration true)}]
    (doseq [[expected a] cases]
      (testing (str expected)
        (is (= expected (:nenmatsu/answer a)))
        (is (= (contains? nenmatsu/answers expected) (:nenmatsu/answerable? a)))
        (is (not (str/blank? (:nenmatsu/why a)))
            "every answer explains itself; a bare keyword is not a refusal")))
    (testing "evidence floor — all nine, not a subset that happened to differ"
      (is (= 9 (count (distinct (map (comp :nenmatsu/answer val) cases))))))))

(deftest the-two-classifications-partition-the-answers
  (testing "an answer in neither set is `answerable? false` — a new answer
            defaults to refused rather than to a pass"
    (is (empty? (clojure.set/intersection nenmatsu/answers nenmatsu/refusals)))
    (is (= 5 (count nenmatsu/answers)))
    (is (= 4 (count nenmatsu/refusals)))))

;; ---------------------------------------------------------------------------
;; 1. no figure is invented
;; ---------------------------------------------------------------------------

(deftest the-over-under-is-never-computed
  (testing "第百九十条 settles the year's over/under at the final payment, and
            the year's correct tax comes from 別表 — which taxlaw records as
            unread. A number here would arrive stamped with an article of the
            Income Tax Act and nothing downstream could check it"
    (let [a (assess :declaration true :final? true
                    :records [(run :period "2026-11" :gross 280000 :withheld 8420)
                              (run :period "2026-12" :gross 280000 :withheld 8420)])
          amt (:nenmatsu/amount a)]
      (is (= :owed (:nenmatsu/answer a)))
      (is (= :not-computable (:nenmatsu/annual-tax amt)))
      (is (= :not-computable (:nenmatsu/over-or-under amt)))
      (testing "and it names the table nobody read, read OFF taxlaw"
        (is (= (:rule/amount-source-not-read
                (taxlaw/facet-of [:jp] :jurisdiction/wage-withholding))
               (:nenmatsu/amount-source-not-read amt)))
        (is (str/includes? (:nenmatsu/amount-source-not-read amt) "別表")))
      (testing "the only figures are sums of what THIS actor committed"
        (is (= 560000 (:nenmatsu/wages-recorded amt)))
        (is (= 16840 (:nenmatsu/withheld-recorded amt)))
        (is (= 2 (:nenmatsu/runs-recorded amt)))
        (is (true? (:nenmatsu/records-are-this-actors-only? amt)))))))

(deftest no-answer-carries-a-number-that-is-not-a-recorded-sum
  (testing "the guard against the figure creeping back in by another name:
            every number anywhere in the assessment is either a sum of
            recorded runs, a count, or the ceiling read from taxlaw"
    (let [a (assess :declaration true :final? true
                    :records [(run :gross 280000 :withheld 8420)])
          nums (->> (tree-seq coll? seq a) (filter number?) set)]
      (is (= #{280000 8420 1 0 20000000} nums)
          (str "unexpected numbers in the assessment: " (pr-str nums))))))

(deftest settled-is-a-claim-recorded-not-an-amount-verified
  (let [a (assess :declaration true :final? true :settled? true)]
    (is (= :settled (:nenmatsu/answer a)))
    (is (= :request-declaration (get-in a [:nenmatsu/settled-claim :source])))
    (is (false? (get-in a [:nenmatsu/settled-claim :verified?])))
    (testing "and the amount block is still uncomputed on a settled year"
      (is (= :not-computable (get-in a [:nenmatsu/amount :nenmatsu/over-or-under]))))))

;; ---------------------------------------------------------------------------
;; 2. the 二千万円 ceiling, checked as 以下
;; ---------------------------------------------------------------------------

(deftest the-ceiling-is-inclusive
  (testing "「二千万円以下」— at exactly 20,000,000 the employee is INSIDE"
    (let [at (assess :declaration true :final? true :records [(run :gross 20000000)])
          over (assess :declaration true :final? true :records [(run :gross 20000001)])]
      (is (= :owed (:nenmatsu/answer at)) "20,000,000 は以下に含まれる")
      (is (true? (get-in at [:nenmatsu/evidence :ceiling :nenmatsu/inside?])))
      (is (= :above-ceiling (:nenmatsu/answer over)))
      (is (false? (get-in over [:nenmatsu/evidence :ceiling :nenmatsu/inside?]))))))

(deftest the-ceiling-comes-from-taxlaw-and-is-not-typed-here
  (is (= (:rule/income-ceiling-yen
          (taxlaw/facet-of [:jp] :jurisdiction/year-end-adjustment))
         (get-in (assess :declaration true :final? true)
                 [:nenmatsu/evidence :ceiling :nenmatsu/ceiling-yen])))
  (is (= 20000000 (get-in (assess :declaration true :final? true)
                          [:nenmatsu/evidence :ceiling :nenmatsu/ceiling-yen]))
      "and the value taxlaw read off 第百九十条 is 二千万円"))

(deftest being-under-the-recorded-ceiling-establishes-nothing
  (testing "this actor holds only the runs it committed. Unseen wages can only
            ADD, so `over` is certain and `under` is not — and the field that
            would let a reader forget that is false even when `inside?` is true"
    (let [a (assess :declaration true :final? true :records [(run :gross 100)])
          c (get-in a [:nenmatsu/evidence :ceiling])]
      (is (true? (:nenmatsu/inside? c)))
      (is (false? (:nenmatsu/establishes-inside? c)))
      (is (= :wages-this-actor-recorded (:nenmatsu/basis c))))))

(deftest with-no-recorded-runs-the-ceiling-answers-nothing-at-all
  (testing "zero recorded wages is not a fact about wages. `inside?` is nil,
            not true, and the flag says the record is empty"
    (let [a (assess :declaration true :final? true)]
      (is (true? (get-in a [:nenmatsu/evidence :no-runs-recorded?])))
      (is (nil? (get-in a [:nenmatsu/evidence :ceiling :nenmatsu/inside?])))
      (is (nil? (get-in a [:nenmatsu/evidence :ceiling :nenmatsu/recorded]))))))

;; ---------------------------------------------------------------------------
;; 3. the declaration is a fact software cannot observe
;; ---------------------------------------------------------------------------

(deftest an-unregistered-declaration-is-its-own-answer-and-not-a-pass
  (testing "給与所得者の扶養控除等申告書 is a piece of paper. Unregistered is
            neither `filed` nor `not filed`, and must not commit"
    (let [a (assess :final? true)]
      (is (= :declaration-not-observed (:nenmatsu/answer a)))
      (is (false? (:nenmatsu/answerable? a)))
      (is (contains? nenmatsu/refusals (:nenmatsu/answer a)))
      (is (str/includes? (:nenmatsu/why a) "未観測は合格ではない")))))

(deftest a-string-is-not-a-declaration
  (testing "without normalising, `\"true\"` satisfies neither nil? nor false?
            and falls through to :owed — a pass bought with a typo"
    (doseq [bad ["true" :yes 1 "false" 0]]
      (testing (pr-str bad)
        (is (= :declaration-not-observed
               (:nenmatsu/answer (assess :declaration bad :final? true))))
        (is (= :final-payment-not-declared
               (:nenmatsu/answer (assess :declaration true :final? bad))))))))

(deftest a-settled-claim-that-is-not-a-boolean-is-not-a-settlement
  (is (= :owed (:nenmatsu/answer
                (assess :declaration true :final? true :settled? "true")))
      "an unreadable settlement claim leaves the adjustment owed, not settled"))

(deftest an-explicitly-unfiled-declaration-says-nothing-about-other-routes
  (let [a (assess :declaration false :final? true)]
    (is (= :declaration-not-filed (:nenmatsu/answer a)))
    (is (true? (:nenmatsu/answerable? a)))
    (is (str/includes? (:nenmatsu/why a) "確定申告"))))

;; ---------------------------------------------------------------------------
;; 4. the year may not be over
;; ---------------------------------------------------------------------------

(deftest not-yet-and-never-are-different-answers
  (testing "「その年最後に給与等の支払をする場合」 is a condition about a payment
            that may not have happened. `come back after the last payslip` is
            an instruction; `this employee does not qualify` is a finding. A
            single :out-of-scope would print them the same"
    (let [not-yet (assess :declaration true :final? false)
          never (assess :declaration false :final? true)
          over (assess :declaration true :final? true :records [(run :gross 20000001)])]
      (is (= :year-not-finished (:nenmatsu/answer not-yet)))
      (is (= :declaration-not-filed (:nenmatsu/answer never)))
      (is (= :above-ceiling (:nenmatsu/answer over)))
      (testing "taxlaw folds all three into one coverage — this does not"
        (is (= [:out-of-scope :out-of-scope :out-of-scope]
               (mapv #(get-in % [:nenmatsu/taxlaw :taxlaw/coverage])
                     [not-yet never over])))
        (is (= 3 (count (distinct (map :nenmatsu/answer [not-yet never over]))))))
      (testing "and `not yet` says so, rather than reading as disqualification"
        (is (str/includes? (:nenmatsu/why not-yet) "まだ時期ではない"))))))

(deftest an-undeclared-final-payment-is-held-not-assumed
  (testing "this actor has no clock and cannot see whether another payment is
            coming. Unstated is unanswerable, in both directions"
    (let [a (assess :declaration true)]
      (is (= :final-payment-not-declared (:nenmatsu/answer a)))
      (is (false? (:nenmatsu/answerable? a)))
      (is (= :not-declared (get-in a [:nenmatsu/taxlaw :taxlaw/coverage]))))))

(deftest terminal-answers-are-decided-before-transient-ones
  (testing "a permanent gap must not hide behind `come back later`. With BOTH
            no declaration filed and no statement about the final payment,
            taxlaw answers :not-declared (its first question is the final
            payment) and this answers :declaration-not-filed — because that
            one will not change before the year ends"
    (let [a (assess :declaration false)]
      (is (= :declaration-not-filed (:nenmatsu/answer a)))
      (is (= :not-declared (get-in a [:nenmatsu/taxlaw :taxlaw/coverage]))
          "the divergence is deliberate, and visible: taxlaw's own answer is
           carried verbatim next to this one"))))

;; ---------------------------------------------------------------------------
;; 5. non-JP — adding this must not widen a pass
;; ---------------------------------------------------------------------------

(deftest a-jurisdiction-whose-year-end-facet-was-not-read-is-refused
  (testing "requires-year-end-adjustment? is nil for [:us] and [:eu]; both are
            catalogued with that facet :out-of-scope. Neither may pass"
    (doseq [[j fragment] [[[:us] "annual return"]
                          [[:eu] "Member State law"]]]
      (testing (pr-str j)
        (is (nil? (taxlaw/requires-year-end-adjustment? j))
            "the premise: taxlaw says nil, not false")
        (let [a (assess :jurisdiction j :declaration true :final? true)]
          (is (= :not-catalogued (:nenmatsu/answer a)))
          (is (false? (:nenmatsu/answerable? a)))
          (is (str/includes? (:nenmatsu/why a) fragment)
              "the catalog's own reason is surfaced, not a generic `unknown`")
          (is (= :jurisdiction/year-end-adjustment
                 (get-in a [:nenmatsu/taxlaw :taxlaw/out-of-scope]))))))))

(deftest the-us-refusal-does-not-claim-the-us-has-no-obligation
  (testing "the United States has no year-end adjustment because the annual
            return performs that function — and IRC §6012 was not read. Both
            halves must reach the operator"
    (let [a (assess :jurisdiction [:us] :declaration true :final? true)]
      (is (str/includes? (:nenmatsu/why a) "6012")))))

(deftest a-jurisdiction-nobody-catalogued-at-all-is-also-refused
  (let [a (assess :jurisdiction [:atlantis] :declaration true :final? true)]
    (is (= :not-catalogued (:nenmatsu/answer a)))
    (is (nil? (get-in a [:nenmatsu/taxlaw :taxlaw/out-of-scope]))
        "no stated reason, because nobody considered it — distinct from [:us]")
    (is (str/includes? (:nenmatsu/why a) "kotoba.taxlaw に無い"))))

(deftest an-undeclared-jurisdiction-is-not-the-same-as-an-uncatalogued-one
  (let [a (assess :jurisdiction nil :declaration true :final? true)]
    (is (= :jurisdiction-not-declared (:nenmatsu/answer a)))
    (is (false? (:nenmatsu/answerable? a)))
    (is (str/includes? (:nenmatsu/why a) "適用なしの判断ではない"))))

;; ---------------------------------------------------------------------------
;; What counts as a payroll run for the year
;; ---------------------------------------------------------------------------

(deftest an-assessment-record-is-not-a-payroll-run
  (testing "this op commits records too. Counting one as wages would inflate
            the very figure the ceiling is tested against"
    (let [records [(run :gross 280000)
                   {:client-id "emp-1" :op :assess-year-end-adjustment
                    :contract-id "c-1"
                    :payload {:op :assess-year-end-adjustment :period "2026-07"
                              :gross 999999999}}]
          a (assess :declaration true :final? true :records records)]
      (is (= 1 (get-in a [:nenmatsu/amount :nenmatsu/runs-recorded])))
      (is (= 280000 (get-in a [:nenmatsu/amount :nenmatsu/wages-recorded]))))))

(deftest another-contract-and-another-year-are-not-this-employees-wages
  (let [records [(run :gross 100 :period "2026-07")
                 (run :gross 200 :period "2025-07")
                 (run :gross 400 :contract-id "c-2" :period "2026-08")]
        a (assess :declaration true :final? true :records records)]
    (is (= 1 (get-in a [:nenmatsu/amount :nenmatsu/runs-recorded])))
    (is (= 100 (get-in a [:nenmatsu/amount :nenmatsu/wages-recorded])))))

(deftest a-period-that-does-not-name-the-year-matches-nothing-and-says-so
  (testing "periods are opaque operator strings and this actor parses no
            dates. An operator writing `07/2026` gets zero matches — which is
            reported as an empty record, never as zero wages"
    (let [a (assess :declaration true :final? true
                    :records [(run :period "07/2026" :gross 280000)])]
      (is (true? (get-in a [:nenmatsu/evidence :no-runs-recorded?])))
      (is (= 0 (get-in a [:nenmatsu/amount :nenmatsu/wages-recorded]))))))

(deftest a-run-with-no-withheld-amount-is-counted-and-named
  (testing "a sum over runs where some recorded no withholding is not the
            year's withholding, and a reader given only the sum cannot tell"
    (let [a (assess :declaration true :final? true
                    :records [(run :withheld 8420) (run :period "2026-08" :withheld nil)])]
      (is (= 8420 (get-in a [:nenmatsu/amount :nenmatsu/withheld-recorded])))
      (is (= 1 (get-in a [:nenmatsu/amount :nenmatsu/runs-missing-a-withheld-amount]))))))

;; ---------------------------------------------------------------------------
;; The article is named on every answer that reached it
;; ---------------------------------------------------------------------------

(deftest the-provision-is-named
  (is (= "所得税法 第百九十条"
         (:nenmatsu/provision (assess :declaration true :final? true))))
  (is (nil? (:nenmatsu/provision (assess :jurisdiction [:us] :declaration true
                                         :final? true)))
      "and is absent where the catalog holds no rule to name"))
