(ns payroll.provenance-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.provenance :as prov]))

(deftest a-numberless-provenance-may-not-carry-a-number
  (testing "the one thing this namespace exists to make impossible: a figure
            that renders as 1234 in every view that reads :figure/amount and
            carries a provenance nobody looked at"
    (doseq [p prov/numberless]
      (is (thrown? clojure.lang.ExceptionInfo
                   (prov/figure {:amount 1234 :provenance p :label "x"}))
          (str p " accepted an amount")))))

(deftest a-numberless-provenance-with-no-number-is-fine
  (testing "the other direction — the refusal is about the combination, not
            about the provenance"
    (doseq [p prov/numberless]
      (is (nil? (prov/amount (prov/figure {:amount nil :provenance p
                                           :label "x" :why "y"})))))))

(deftest an-unknown-provenance-is-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (prov/figure {:amount 1 :provenance :probably-fine :label "x"}))))

(deftest a-non-numeric-amount-is-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (prov/figure {:amount "280000" :provenance :declared :label "x"}))))

(deftest only-derived-escapes-the-unverified-mark
  (testing "a figure an operator typed with total confidence is still
            somebody's claim"
    (is (not (prov/unverified? (prov/derived "x" 1 "art. 1"))))
    (is (prov/unverified? (prov/declared "x" 1 "form")))
    (is (prov/unverified? (prov/imported "x" 1 "file")))
    (is (prov/unverified? (prov/unknown "x" "why")))
    (is (prov/unverified? (prov/held "x" "why")))
    (testing ":not-applicable is NOT unverified — a line that does not arise
              is a fact this repository established, not a claim"
      (is (not (prov/unverified? (prov/not-applicable "x" "why")))))))

(deftest a-total-refuses-rather-than-under-reporting
  (testing "one unknown figure and the answer is not a smaller number"
    (let [t (prov/total [(prov/derived "a" 100 "s")
                         (prov/unknown "b" "nobody observed it")])]
      (is (false? (:total/complete? t)))
      (is (nil? (:total/amount t)))
      (is (= ["b"] (mapv :figure/label (:total/blocked-by t)))))))

(deftest a-not-applicable-line-does-not-block-a-total
  (testing "a line that does not arise contributes nothing and is not a gap"
    (let [t (prov/total [(prov/derived "a" 100 "s")
                         (prov/not-applicable "b" "not a 被保険者")])]
      (is (:total/complete? t))
      (is (= 100 (:total/amount t))))))

(deftest a-total-takes-the-weakest-provenance-of-its-parts
  (testing "a certified-looking total must not be producible from an
            uncertified part"
    (is (= :derived (:total/provenance
                     (prov/total [(prov/derived "a" 1 "s")
                                  (prov/derived "b" 2 "s")]))))
    (is (= :declared (:total/provenance
                      (prov/total [(prov/derived "a" 1 "s")
                                   (prov/declared "b" 2 "form")]))))
    (is (= :imported (:total/provenance
                      (prov/total [(prov/derived "a" 1 "s")
                                   (prov/declared "b" 2 "form")
                                   (prov/imported "c" 3 "file")]))))))

(deftest a-total-of-only-not-applicable-lines-keeps-their-provenance
  (testing "nothing arose. Calling that :derived would claim a rule produced
            the zero"
    (is (= :not-applicable
           (:total/provenance (prov/total [(prov/not-applicable "a" "w")]))))))

(deftest an-empty-total-is-a-complete-zero-and-says-so
  (testing "there is genuinely nothing to add up; the caller learns the
            provenance is :not-applicable rather than :derived"
    (let [t (prov/total [])]
      (is (:total/complete? t))
      (is (zero? (:total/amount t)))
      (is (= :not-applicable (:total/provenance t))))))

(deftest a-total-of-nothing-is-not-a-printed-zero
  (testing "a 控除合計 of 0 asserts that deductions were computed and came to
            nothing, which is a different claim from none having arisen.
            `total` reports 0 as data; `total-figure` refuses to print it"
    (let [f (prov/total-figure "控除合計" [] "src")]
      (is (= :not-applicable (:figure/provenance f)))
      (is (nil? (prov/amount f)))
      (is (str/includes? (:figure/why f) "零ではなく")))
    (let [f (prov/total-figure "控除合計"
                               [(prov/not-applicable "健康保険料" "被保険者ではない")
                                (prov/not-applicable "介護保険料" "第二号ではない")]
                               "src")]
      (is (= :not-applicable (:figure/provenance f)))
      (is (nil? (prov/amount f)))
      (is (str/includes? (:figure/why f) "2 件")))))

(deftest an-incomplete-total-figure-names-the-lines-that-blocked-it
  (let [f (prov/total-figure "控除合計"
                             [(prov/derived "a" 100 "s")
                              (prov/held "健康保険料" "標準報酬月額が未登録")]
                             "src")]
    (is (= :unknown (:figure/provenance f)))
    (is (nil? (prov/amount f)))
    (is (str/includes? (:figure/why f) "健康保険料"))
    (is (str/includes? (:figure/why f) "保留"))))

(deftest every-provenance-is-classified-exactly-once
  (testing "an added provenance that nobody classified belongs to neither set,
            so `unverified?` is false for it — which would be the one
            direction that widens a pass. This asserts the partition instead"
    (doseq [p prov/provenances]
      (is (prov/provenance? p)))
    (is (empty? (set/intersection prov/numberless
                                          #{:derived :declared :imported})))
    (testing "and every numberless provenance is also unverified, except the
              one that is an established fact"
      (is (= #{:unknown :held}
             (set/intersection prov/numberless prov/unverified))))))
