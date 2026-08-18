(ns payroll.shiwake-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [payroll.shiwake :as shiwake]))

(def ^:private mapping
  {:wages "給料手当" :withholding "預り金" :payable "未払金"})

(defn- committed [& {:keys [disposition gross net withheld contract-id period currency]
                     :or {disposition :commit gross 300000 net 270000 withheld 30000
                          contract-id "c-1" period "2026-07" currency "JPY"}}]
  {:disposition disposition
   :run {:contract-id contract-id :period period :gross gross :net net
         :income-tax-withheld withheld :currency currency}})

;; ---------------------------------------------------------------------------
;; three lines, not two
;; ---------------------------------------------------------------------------

(deftest withheld-tax-becomes-a-liability-not-a-smaller-expense
  (testing "所得税法 第百八十三条第一項 obliges the payer to 徴収し…納付.
            Netting the withholding into one credit line would make that
            liability disappear from the balance sheet while the obligation
            continued to exist."
    (let [req (:shiwake/request (shiwake/entry-request (committed) mapping))]
      (is (= 3 (count (:lines req))))
      (is (= [["給料手当" :dr 300000] ["預り金" :cr 30000] ["未払金" :cr 270000]]
             (mapv (juxt :account :side :amount) (:lines req))))
      (testing "and it balances"
        (let [by (group-by :side (:lines req))]
          (is (= (reduce + (map :amount (:dr by)))
                 (reduce + (map :amount (:cr by))))))))))

(deftest zero-withholding-omits-the-line-rather-than-posting-zero
  (testing "預り金 0 asserts a liability of nothing, which is a different
            claim from having none"
    ;; NOTE: a first draft of this test opened with an assertion that
    ;; double-extracted :shiwake/request and checked it was nil. It passed
    ;; trivially and checked nothing. Removed rather than left as filler.
    (let [req (:shiwake/request
               (shiwake/entry-request (committed :withheld 0 :net 300000) mapping))]
      (is (= 2 (count (:lines req))))
      (is (= ["給料手当" "未払金"] (mapv :account (:lines req))))
      (is (= (:amount (first (:lines req)))
             (:amount (second (:lines req)))) "still balances"))))

;; ---------------------------------------------------------------------------
;; refusals
;; ---------------------------------------------------------------------------

(deftest an-unapproved-run-yields-a-named-refusal
  (testing "an unapproved payroll run is precisely the one somebody must look
            at, and nil would let a caller skip it"
    (doseq [d [:hold :request-approval]]
      (let [r (shiwake/entry-request (committed :disposition d) mapping)]
        (is (= :not-approved (:shiwake/status r)))
        (is (= d (:shiwake/disposition r)))
        (is (nil? (:shiwake/request r)))))))

(deftest figures-that-do-not-add-up-are-refused-here
  (testing "gross = withheld + net is the identity the ENTRY depends on;
            4311 would refuse the unbalanced result, but by then the message
            would be about currency arithmetic rather than this run"
    (let [r (shiwake/entry-request (committed :net 260000) mapping)]
      (is (= :unusable-run (:shiwake/status r)))
      (is (re-find #"≠" (:shiwake/why r))))))

(deftest a-missing-figure-is-not-zero
  (doseq [c [(committed :withheld nil) (committed :gross nil) (committed :net nil)
             (committed :withheld "30000") (committed :gross -1)]]
    (is (= :unusable-run (:shiwake/status (shiwake/entry-request c mapping))))))

(deftest a-half-filled-mapping-is-no-mapping
  (testing "an entry missing one line balances by having lost it"
    (doseq [m [{:wages "給料手当" :withholding "預り金"}
               {:wages "給料手当" :payable "未払金"}
               {:wages "" :withholding "預り金" :payable "未払金"}
               {}]]
      (is (= :no-mapping (:shiwake/status (shiwake/entry-request (committed) m)))))))

(deftest a-run-citing-no-contract-has-no-source-document
  (is (= :unusable-run (:shiwake/status
                        (shiwake/entry-request (committed :contract-id nil) mapping)))))

(deftest the-contract-is-carried-as-the-source-document
  (testing "4311 refuses an entry citing a document its own registry does not
            know — the ledger's registry is the one that counts"
    (is (= "c-9" (get-in (shiwake/entry-request (committed :contract-id "c-9") mapping)
                         [:shiwake/request :source-doc])))))

(deftest a-batch-keeps-what-it-could-not-convert
  (let [b (shiwake/entry-requests
           [(committed) (committed :disposition :hold) (committed :net 1)]
           mapping)]
    (is (= 1 (count (:ok b))))
    (is (= 2 (count (:skipped b))))
    (is (every? :shiwake/run (:skipped b)))))

(deftest this-namespace-computes-no-tax-and-reaches-nothing
  (testing "taxlaw records :taxlaw/amount-checked? false — nothing in this
            fleet certifies how much should have been withheld, and this
            namespace inherits that exactly"
    (let [src (slurp "src/payroll/shiwake.cljc")]
      (doseq [tok ["http" "fetch" "slurp" "4311" "js/"]]
        (is (not (re-find (re-pattern (str "\\(" tok)) src))
            (str "must not call out: found " tok)))
      (is (not (re-find #"\*\s*0\.|rate|tax-table" src))
          "must not compute a withholding amount"))))

;; ---------------------------------------------------------------------------
;; 五 lines once 社会保険 exists
;;
;; A payroll run that withholds 健康保険料 / 介護保険料 / 厚生年金保険料 /
;; 雇用保険料 and posts a three-line entry is an entry that has lost the
;; difference. Until 2026-08-18 this namespace refused every such run with
;; `:unusable-run` — safe, and no help to anyone.
;; ---------------------------------------------------------------------------

(def ^:private full-mapping
  (assoc mapping :social-insurance "社会保険料預り金"
                 :employment-insurance "雇用保険料預り金"))

(defn- insured-run
  "gross 300000 = 所得税 8420 + 社会保険 (13860 + 0 + 25620) + 雇用保険 1800
   + net 250300."
  [& {:as extra}]
  {:disposition :commit
   :run (merge {:contract-id "c-1" :period "2026-07" :currency "JPY"
                :gross 300000 :net 250300 :income-tax-withheld 8420
                :health-insurance-withheld 13860
                :employees-pension-withheld 25620
                :employment-insurance-withheld 1800}
               extra)})

(deftest social-insurance-becomes-two-liabilities-and-not-one
  (testing "健保法 第百六十一条第二項 and 厚年法 第八十二条第二項 make the employer
            liable to the insurer month by month; 労働保険徴収法 collects
            労働保険料 for a 保険年度. Netting them would put a monthly
            liability and an annual one in the same balance"
    (let [req (:shiwake/request (shiwake/entry-request (insured-run) full-mapping))]
      (is (= 5 (count (:lines req))))
      (is (= [["給料手当" :dr 300000]
              ["預り金" :cr 8420]
              ["社会保険料預り金" :cr 39480]      ; 13860 + 0 + 25620
              ["雇用保険料預り金" :cr 1800]
              ["未払金" :cr 250300]]
             (mapv (juxt :account :side :amount) (:lines req))))
      (testing "and it balances"
        (let [by (group-by :side (:lines req))]
          (is (= (reduce + (map :amount (:dr by)))
                 (reduce + (map :amount (:cr by))))))))))

(deftest a-contribution-that-was-deducted-and-omitted-here-is-caught-by-the-identity
  (testing "this namespace reads a run and not a verdict, so it cannot tell
            `nil because not a 被保険者` from `nil because somebody forgot`.
            It does not have to — gross ≠ the sum, and the run is unusable
            rather than an entry that balances by having lost the difference"
    (let [r (shiwake/entry-request (insured-run :employees-pension-withheld nil)
                                   full-mapping)]
      (is (= :unusable-run (:shiwake/status r)))
      (is (clojure.string/includes? (:shiwake/why r) "社会保険")))))

(deftest a-run-with-no-social-insurance-still-produces-the-three-line-entry
  (testing "requiring the two new accounts from every run would refuse entries
            this namespace produced correctly before 社会保険 existed"
    (let [req (:shiwake/request (shiwake/entry-request (committed) mapping))]
      (is (= 3 (count (:lines req)))))))

(deftest a-withheld-contribution-with-no-account-mapped-is-no-mapping
  (doseq [[label run needle]
          [["社会保険" (insured-run) ":social-insurance"]
           ["雇用保険" (insured-run :health-insurance-withheld 0
                                    :employees-pension-withheld 0
                                    :net 289780)
            ":employment-insurance"]]]
    (testing label
      (let [r (shiwake/entry-request run mapping)]
        (is (= :no-mapping (:shiwake/status r)))
        (is (clojure.string/includes? (:shiwake/why r) needle))))))

(deftest a-zero-social-insurance-total-omits-its-line-like-the-tax-one-does
  (let [req (:shiwake/request
             (shiwake/entry-request
              (insured-run :health-insurance-withheld 0
                           :employees-pension-withheld 0
                           :employment-insurance-withheld 0
                           :net 291580)
              mapping))]
    (is (= ["給料手当" "預り金" "未払金"] (mapv :account (:lines req)))
        "and the unmapped accounts are not required, because no line arises")))

(deftest a-malformed-contribution-is-refused-before-the-arithmetic
  (doseq [bad [-1 "13860" :none]]
    (let [r (shiwake/entry-request (insured-run :health-insurance-withheld bad)
                                   full-mapping)]
      (is (= :unusable-run (:shiwake/status r)) (str "should refuse " (pr-str bad)))
      (is (clojure.string/includes? (:shiwake/why r) "non-negative")))))
