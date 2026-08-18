(ns payroll.shakai-hoken-test
  "社会保険・労働保険 as a pure question.

  Two things are being measured and they are not the same. That the governor
  HOLDS the right runs is `payroll.governor-test`'s job. What lives here is
  the reading itself: that every refusal is a refusal, that the one amount
  this workspace can compute is computed from a rate read out of a statute,
  that the three it cannot are named rather than guessed, and that adding a
  jurisdiction to the catalog cannot widen a pass."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.shakai-hoken :as hoken]))

(def ^:private insured
  {:employment/health-insurance-insured? true
   :employment/employees-pension-insured? true
   :employment/care-insurance-second-category? false
   :employment/employment-insurance-insured? true
   :employment/standard-remuneration-monthly-yen 280000
   :employment/standard-remuneration-month "2026-06"})

;; 280000 × 183 / 2000 = 25620 exactly — 厚生年金保険法 第八十一条第四項
(def ^:private declared
  {:health-insurance-withheld 13860
   :employees-pension-withheld 25620
   :employment-insurance-withheld 168})

(defn- assess [& {:keys [jurisdiction contract proposal]
                  :or {jurisdiction [:jp]}}]
  (hoken/assess {:jurisdiction jurisdiction
                 :contract (merge insured contract)
                 :proposal (merge declared proposal)}))

(defn- answer-of [a scheme] (get-in a [:shakai-hoken/schemes scheme :scheme/answer]))

;; ---------------------------------------------------------------------------
;; The answers that ARE answers
;; ---------------------------------------------------------------------------

(deftest a-fully-registered-fully-declared-run-is-answered
  (let [a (assess)]
    (is (= :answered (:shakai-hoken/answer a)))
    (is (:shakai-hoken/answerable? a))
    (is (empty? (:shakai-hoken/refusals a)))
    (testing "three accounted for, and the fourth answered as not covered —
              which is an answer and not a silence"
      (is (= [:scheme/health-insurance :scheme/employees-pension
              :scheme/employment-insurance]
             (:shakai-hoken/accounted a)))
      (is (= [:scheme/long-term-care-insurance] (:shakai-hoken/not-covered a)))
      (is (= :not-covered (answer-of a :scheme/long-term-care-insurance))))))

(deftest a-scheme-the-worker-is-not-insured-under-needs-no-amount
  (testing "`:not-covered` is an answer, so a run that declares nothing for it
            still answers — otherwise every employer would have to file a zero
            for every scheme its workers are outside"
    (let [a (assess :contract {:employment/employment-insurance-insured? false}
                    :proposal {:employment-insurance-withheld nil})]
      (is (= :answered (:shakai-hoken/answer a)))
      (is (= :not-covered (answer-of a :scheme/employment-insurance))))))

(deftest zero-is-an-accounting-and-nil-is-not
  (testing "a declared zero says the contribution was zero; nil says nothing"
    (is (= :accounted-for
           (answer-of (assess :proposal {:health-insurance-withheld 0})
                      :scheme/health-insurance)))
    (is (= :not-accounted-for
           (answer-of (assess :proposal {:health-insurance-withheld nil})
                      :scheme/health-insurance)))))

;; ---------------------------------------------------------------------------
;; The refusals — most of the work
;; ---------------------------------------------------------------------------

(deftest every-refusal-is-a-refusal-and-names-what-is-missing
  (doseq [[label scheme contract proposal expected missing]
          [["資格未登録"
            :scheme/health-insurance {:employment/health-insurance-insured? nil}
            {} :coverage-not-observed :employment/health-insurance-insured?]
           ["標準報酬月額未登録"
            :scheme/employees-pension
            {:employment/standard-remuneration-monthly-yen nil}
            {} :standard-remuneration-not-observed
            :employment/standard-remuneration-monthly-yen]
           ["月分未登録"
            :scheme/employees-pension
            {:employment/standard-remuneration-month nil}
            {} :standard-remuneration-month-not-observed
            :employment/standard-remuneration-month]
           ["計上なし"
            :scheme/employees-pension {} {:employees-pension-withheld nil}
            :not-accounted-for :employees-pension-withheld]
           ["計上が不正"
            :scheme/employees-pension {} {:employees-pension-withheld "25620"}
            :malformed-amount nil]]]
    (testing label
      (let [a (assess :contract contract :proposal proposal)
            r (get-in a [:shakai-hoken/schemes scheme])]
        (is (= expected (:scheme/answer r)))
        (is (not (:scheme/answerable? r)))
        (is (contains? hoken/refusals (:scheme/answer r)))
        (is (= :refused (:shakai-hoken/answer a)))
        (is (not (:shakai-hoken/answerable? a)))
        (is (not (str/blank? (:scheme/why r))))
        (when missing
          (is (= missing (:scheme/missing r))
              "a refusal that does not name the key is not an instruction")
          (is (= missing (:missing (first (filter #(= scheme (:scheme %))
                                                  (:shakai-hoken/refusals a)))))))))))

(deftest a-string-true-is-not-a-registration
  (testing "\"true\" satisfies neither nil? nor false?; without normalising it
            here it would fall through to `covered` — a pass bought with a
            typo, which is `payroll.nenmatsu/declared`'s reason"
    (doseq [bad ["true" :yes 1]]
      (is (= :coverage-not-observed
             (answer-of (assess :contract {:employment/health-insurance-insured? bad})
                        :scheme/health-insurance))
          (str "should refuse " (pr-str bad))))))

(deftest a-negative-or-fractional-contribution-is-malformed
  (doseq [bad [-1 25620.5 "25620" :none]]
    (is (= :malformed-amount
           (answer-of (assess :proposal {:employees-pension-withheld bad})
                      :scheme/employees-pension))
        (str "should refuse " (pr-str bad)))))

(deftest the-month-must-be-a-month-and-nothing-else-is-parsed
  (doseq [bad ["2026-6" "2026/06" "令和八年六月" "2026-13" "2026" 202606]]
    (is (= :standard-remuneration-month-not-observed
           (answer-of (assess :contract {:employment/standard-remuneration-month bad})
                      :scheme/employees-pension))
        (str "should refuse " (pr-str bad))))
  (is (hoken/month? "2026-06"))
  (is (not (hoken/month? "2026-00"))))

(deftest a-month-before-the-transcribed-rate-row-is-refused-not-rated
  (testing "厚年法 第八十一条第四項 is a table of fourteen rows and only the
            last, open-ended one was transcribed. Applying 千分の百八十三 to a
            month the row does not cover would be inventing a rate out of a
            table that is right there in the Act"
    (is (= :rate-period-not-read
           (answer-of (assess :contract {:employment/standard-remuneration-month "2017-08"})
                      :scheme/employees-pension)))
    (is (= :accounted-for
           (answer-of (assess :contract {:employment/standard-remuneration-month "2017-09"})
                      :scheme/employees-pension))
        "the row's own first month is covered by it")))

;; ---------------------------------------------------------------------------
;; The one amount that can be computed, and the three that cannot
;; ---------------------------------------------------------------------------

(deftest the-pension-share-is-exact-and-is-not-a-number
  (testing "厚年法 第八十一条第三項・第四項 + 第八十二条第一項: 標準報酬月額 ×
            千分の百八十三 ÷ 2. Returned as a ratio because ClojureScript has
            no distinct float type and this is money"
    (is (= {:numerator 51240000 :denominator 2000 :unit :yen}
           (hoken/pension-employee-share 280000 183)))
    (let [amt (get-in (assess) [:shakai-hoken/schemes :scheme/employees-pension
                                :scheme/amount])]
      (is (true? (:amount/computable? amt)))
      (is (= 183 (:amount/rate-per-mille amt)))
      (is (= "厚生年金保険法 第八十一条第四項" (:amount/provision amt)))
      (testing "and the YEN figure is still refused, because no 端数処理 rule
                for the employee's half was read"
        (is (= :not-computable (:amount/employee-share-yen amt)))
        (is (str/includes? (:amount/why-no-yen-figure amt) "一円未満"))))))

(deftest the-one-yen-bound-holds-under-every-rounding-rule
  (testing "no 端数処理 rule is needed to know that a whole-yen rendering is
            within one yen of the exact value. 100001 is used rather than a
            real 標準報酬月額 grade precisely because every grade is an even
            number of thousands, which makes the exact half a whole yen and
            the bound untestable"
    (let [exact (hoken/pension-employee-share 100001 183)]   ; 9150.0915
      (is (= 18300183 (:numerator exact)))
      (is (hoken/within-one-yen? 9150 exact))
      (is (hoken/within-one-yen? 9151 exact) "ceiling is inside the bound too")
      (is (not (hoken/within-one-yen? 9149 exact)))
      (is (not (hoken/within-one-yen? 9152 exact))))))

(deftest an-amount-no-rounding-rule-could-produce-is-refused
  (let [a (assess :proposal {:employees-pension-withheld 9999})]
    (is (= :amount-contradicts-statutory-rate
           (answer-of a :scheme/employees-pension)))
    (is (= :refused (:shakai-hoken/answer a))))
  (testing "and the exact figure passes"
    (is (= :accounted-for
           (answer-of (assess :proposal {:employees-pension-withheld 25620})
                      :scheme/employees-pension)))))

(deftest the-three-unread-rates-are-refused-by-name-and-not-guessed
  (testing "a rate typed from memory is the most dangerous value that could be
            added here, so each of the three says WHICH artefact was not read"
    (doseq [[scheme amount-key needle]
            [[:scheme/health-insurance :health-insurance-withheld "協会けんぽ"]
             [:scheme/long-term-care-insurance :care-insurance-withheld "介護保険料率"]
             [:scheme/employment-insurance :employment-insurance-withheld "告示"]]]
      (testing (str scheme)
        (let [a (assess :contract {:employment/care-insurance-second-category? true}
                        :proposal {amount-key 1})
              amt (get-in a [:shakai-hoken/schemes scheme :scheme/amount])]
          (is (false? (:amount/computable? amt)))
          (is (str/includes? (:amount/why amt) needle)))))))

(deftest the-employment-insurance-share-is-not-half-and-the-base-is-different
  (testing "労働保険徴収法 第三十一条第一項第一号 — the employee gets half of the
            雇用保険率 portion AFTER the 二事業率 portion is taken out, and the
            base is 賃金 rather than 標準報酬月額. Either difference alone makes
            a single combined `:social-insurance-withheld` field unwritable"
    (let [ei (hoken/facet-of [:jp] :scheme/employment-insurance)
          hi (hoken/facet-of [:jp] :scheme/health-insurance)]
      (is (= :half (:scheme/split hi)))
      (is (= :half-of-the-non-nijigyo-part (:scheme/split ei)))
      (is (= :standard-remuneration-monthly (:scheme/base hi)))
      (is (= :wages (:scheme/base ei)))
      (is (str/includes? (:scheme/split-quote ei) "二事業率")))))

(deftest rousai-is-named-as-a-non-withholding-rather-than-omitted
  (testing "an omission and a finding print identically"
    (let [nw (:shakai-hoken/not-withheld (assess))]
      (is (= "労災保険" (:scheme/label nw)))
      (is (str/includes? (:scheme/why nw) "第三十一条第三項"))
      (is (= "厚生労働大臣" (:scheme/rate-set-by nw))))))

;; ---------------------------------------------------------------------------
;; 介護保険 — the one that turns on a fact this actor structurally cannot see
;; ---------------------------------------------------------------------------

(deftest the-care-insurance-category-is-observed-and-never-derived
  (let [a (assess :contract {:employment/care-insurance-second-category? nil
                             ;; a date of birth changes NOTHING, deliberately
                             :employment/date-of-birth "1980-04-01"})
        r (get-in a [:shakai-hoken/schemes :scheme/long-term-care-insurance])]
    (is (= :coverage-not-observed (:scheme/answer r)))
    (testing "and the refusal carries the article, so `register a boolean` is
              an instruction rather than a demand"
      (is (= "介護保険法 第九条第二号" (:scheme/eligibility-provision r)))
      (is (str/includes? (:scheme/eligibility-quote r) "四十歳以上六十五歳未満"))
      (is (str/includes? (:scheme/eligibility-not-derivable r)
                         "年齢計算ニ関スル法律")))))

;; ---------------------------------------------------------------------------
;; Non-JP must not widen a pass
;; ---------------------------------------------------------------------------

(deftest an-uncatalogued-jurisdiction-is-refused-with-its-reason
  (doseq [[j needle] [[[:us] "FICA"] [[:eu] "883/2004"]]]
    (testing (str j)
      (let [a (assess :jurisdiction j)]
        (is (= :not-catalogued (:shakai-hoken/answer a)))
        (is (not (:shakai-hoken/answerable? a)))
        (is (= 1 (count (:shakai-hoken/refusals a))))
        (is (str/includes? (:shakai-hoken/why a) needle))
        (is (str/includes? (:shakai-hoken/why a) "読んでいない"))))))

(deftest a-jurisdiction-nobody-mentioned-at-all-is-also-refused
  (let [a (assess :jurisdiction [:atlantis])]
    (is (= :not-catalogued (:shakai-hoken/answer a)))
    (is (nil? (:shakai-hoken/out-of-scope a))
        "there is no stated reason, because nobody considered it")
    (is (str/includes? (:shakai-hoken/why a) "未検査は合格ではない"))))

(deftest a-nil-jurisdiction-is-not-catalogued-either
  (is (= :not-catalogued (:shakai-hoken/answer (assess :jurisdiction nil))))
  (is (not (hoken/covered? nil))))

(deftest the-catalog-answers-per-jurisdiction-and-a-keyword-is-a-path
  (is (hoken/covered? [:jp]))
  (is (hoken/covered? :jp) "actors store a jurisdiction however their schema does")
  (is (not (hoken/covered? [:us]))))

;; ---------------------------------------------------------------------------
;; The citations, and the evidence floors
;; ---------------------------------------------------------------------------

(deftest every-scheme-cites-a-read-article-with-a-quote
  (doseq [s hoken/schemes]
    (testing (str s)
      (let [r (hoken/facet-of [:jp] s)]
        (is (= :read-from-source (:scheme/review r)))
        (doseq [k [:scheme/deduction-provision :scheme/deduction-quote
                   :scheme/split-provision :scheme/split-quote]]
          (is (not (str/blank? (str (get r k)))) (str k " missing")))
        (is (seq (:scheme/sources r)))
        (doseq [src (:scheme/sources r)]
          (is (some? (get hoken/sources src)) (str "unknown source " src)))))))

(deftest every-source-says-which-text-was-read-and-when
  (is (= 5 (count hoken/sources)) "evidence floor: five statutes were read")
  (doseq [[id src] hoken/sources]
    (testing (str id)
      (is (not (str/blank? (:law/id src))))
      (is (not (str/blank? (:law/revision-id src)))
          "a law id alone does not say WHICH text; these are amended constantly")
      (is (str/starts-with? (:law/revision-id src) (:law/id src)))
      (is (= "2026-08-18" (:source/retrieved-at src)))
      (is (str/includes? (:source/retrieved-via src) (:law/id src))))))

(deftest the-answer-and-refusal-sets-are-disjoint-and-cover-what-assess-emits
  (is (empty? (clojure.set/intersection hoken/answers hoken/refusals)))
  (testing "an answer classified as neither is answerable? false, so a new
            answer defaults to refused rather than to a quiet commit"
    (is (not (contains? hoken/answers :something-new)))))

(deftest withheld-total-refuses-a-partial-sum
  (let [a (assess)]
    (is (= (+ 13860 25620)
           (hoken/withheld-total a [:scheme/health-insurance
                                    :scheme/employees-pension])))
    (testing "and refuses rather than under-reporting when one did not declare"
      (is (nil? (hoken/withheld-total a [:scheme/health-insurance
                                         :scheme/long-term-care-insurance]))))))

(deftest the-rounding-law-that-was-read-says-what-it-does-not-settle
  (let [r (:shakai-hoken/rounding (assess))]
    (is (str/includes? (:rounding/provision r) "端数計算に関する法律"))
    (is (str/includes? (:rounding/quote r) "一円未満の端数"))
    (is (str/includes? (:rounding/does-not-settle r) "折半額"))))

(deftest the-one-yen-bound-is-strict-and-the-boundary-is-the-whole-claim
  (testing "`within-one-yen?` bounds an answer it cannot compute: whatever
            端数処理 rule renders the exact share into whole yen — floor,
            ceiling, half-up, or the employer absorbing the remainder — the
            result differs from the exact value by STRICTLY less than one
            yen. So the bound is `<`, not `<=`, and the only case that can
            tell them apart is an amount exactly one yen out.

            A mutation relaxing `<` to `<=` survived the first run: every
            case was comfortably inside or comfortably outside, and a bound
            tested nowhere near its boundary is not a bound. That is the
            same shape as `taxlaw`'s 五千万円以下 test, which pins exactly
            the ceiling because 以下 and 未満 differ only there."
    (let [ratio {:numerator 250000 :denominator 1000 :unit :yen}]  ; exact = 250
      (testing "inside the bound, on both sides of an exact value that is not
                itself a whole yen — 250.5, so 250 and 251 are each half a
                yen away and both inside"
        (let [half {:numerator 250500 :denominator 1000 :unit :yen}]
          (is (true? (hoken/within-one-yen? 250 half)))
          (is (true? (hoken/within-one-yen? 251 half))))
        (is (true? (hoken/within-one-yen? 250 ratio)) "exactly right")
        (is (true? (hoken/within-one-yen? 251 (assoc ratio :numerator 250001)))
            "999/1000 of a yen away"))
      (testing "EXACTLY one yen out is out — this is the assertion the
                mutation survived on, in both directions"
        (is (false? (hoken/within-one-yen? 251 ratio)))
        (is (false? (hoken/within-one-yen? 249 (assoc ratio :numerator 250000)))
            "249 is exactly one yen below 250")
        (is (false? (hoken/within-one-yen? 251 ratio))))
      (testing "and further out stays out, so the boundary case is not the
                only thing holding this up"
        (is (false? (hoken/within-one-yen? 300 ratio)))
        (is (false? (hoken/within-one-yen? 0 ratio)))))))
