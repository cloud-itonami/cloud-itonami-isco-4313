(ns payroll.shiwake-test
  (:require [clojure.test :refer [deftest is testing]]
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
