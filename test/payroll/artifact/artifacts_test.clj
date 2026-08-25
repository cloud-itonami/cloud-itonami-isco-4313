(ns payroll.artifact.artifacts-test
  "The five output artifacts: determinism, refusals, and the claims each one
  does not make."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.artifact.chingin-daicho :as daicho]
            [payroll.artifact.deduction-summary :as summary]
            [payroll.artifact.payslip :as payslip]
            [payroll.artifact.text :as text]
            [payroll.fixtures :as f]
            [payroll.provenance :as prov]
            [payroll.ui.a11y :as a11y]))

(defn- clean-meisai [] (f/lines {:verdict (f/verdict-for)}))

(defn- held-meisai []
  (let [st (f/fresh-store
            {:contract-overrides {:employment/health-insurance-insured? nil}})
        p (f/proposal)]
    (f/lines {:contract* (f/contract {:employment/health-insurance-insured? nil})
              :run p :verdict (f/verdict-for st p) :disposition :hold})))

(defn- slip [m]
  (payslip/record {:employer (f/employer) :contract (f/contract)
                   :period f/period :meisai m}))

(defn- runs [m]
  [{:period f/period :contract-id f/contract-id :worker f/worker :meisai m}])

;; ---------------------------------------------------------------------------
;; 給与支払明細書
;; ---------------------------------------------------------------------------

(deftest the-payslip-says-on-its-face-that-it-is-not-a-statutory-form
  (testing "所得税法 第二百三十一条第一項 and the rule prescribing the
            contents were not read; a plausible-looking 明細書 whose title
            asserts compliance is the most expensive kind of wrong here,
            because the recipient has no way to check"
    (let [html (str (payslip/->hiccup (slip (clean-meisai))))
          json (payslip/->json (slip (clean-meisai)))]
      (is (str/includes? html "法定様式ではない"))
      (is (str/includes? html "第二百三十一条"))
      (is (str/includes? json "\"statutory_form\":false")))))

(deftest an-unresolved-line-prints-its-reason-in-the-amount-column
  (testing "not a blank with a footnote — a blank is read as zero on the way
            past"
    (let [tree (payslip/->hiccup (slip (held-meisai)))
          s (pr-str tree)]
      (is (str/includes? s text/unknown-cell))
      (is (str/includes? s "employment/health-insurance-insured?")))))

(deftest the-payslip-tree-passes-the-accessibility-invariants
  (let [r (a11y/check (payslip/->hiccup (slip (clean-meisai))))]
    (is (a11y/clean? r) (a11y/report r))
    (testing "and something was actually scanned"
      (is (pos? (get-in r [:a11y/scanned :elements])))
      (is (pos? (get-in r [:a11y/scanned :tables]))))))

(deftest a-held-payslip-also-passes-them
  (testing "the held rendering is a different code path and is the one an
            operator sees on a bad month"
    (let [r (a11y/check (payslip/->hiccup (slip (held-meisai))))]
      (is (a11y/clean? r) (a11y/report r)))))

(deftest the-payslip-json-is-deterministic
  (let [m (clean-meisai)]
    (is (apply = (repeatedly 20 #(payslip/->json (slip m)))))))

(deftest the-payslip-json-emits-deductions-in-a-fixed-order
  (testing "fixed here rather than taken from the data, so a reordering shows
            up as a failing test and not as a silently different file"
    (let [json (payslip/->json (slip (clean-meisai)))
          positions (mapv #(str/index-of json (str "\"" (name %) "\""))
                          payslip/json-line-order)]
      (is (every? some? positions))
      (is (= positions (sort positions))))))

(deftest the-payslip-carries-its-own-coverage-counts
  (testing "an export whose every figure is unverified certifies nothing, and
            one with zero figures is an empty file that reads as a clean one"
    (let [s (slip (clean-meisai))]
      (is (pos? (get-in s [:payslip/coverage :coverage/figures])))
      (is (str/includes? (payslip/->json s) "\"certified_by_this_repository\"")))))

;; ---------------------------------------------------------------------------
;; 賃金台帳
;; ---------------------------------------------------------------------------

(deftest the-wage-ledger-does-not-enumerate-requirements-it-has-not-read
  (testing "a list of `required items` written from memory would be worse
            than the absence, because a list that looks read invites an
            operator to tick it off"
    (is (str/includes? (:sufficiency/why daicho/sufficiency) "読んでいない"))
    (is (str/includes? (:sufficiency/why daicho/sufficiency) "第五十四条"))
    (is (str/includes? (daicho/->json []) "\"statutory_form\":false"))
    (testing "and no such list appears anywhere in the namespace"
      (let [src (slurp "src/payroll/artifact/chingin_daicho.cljc")]
        (is (not (str/includes? src "記載事項は次")))
        (is (not (str/includes? src "required-items")))))))

(deftest held-runs-are-rows-in-the-wage-ledger
  (testing "a ledger of only the runs that committed answers `what was paid`
            and cannot answer `why was this month not paid`"
    (let [csv (daicho/->csv (mapv daicho/row (runs (held-meisai))))]
      (is (str/includes? csv "保留"))
      (is (str/includes? csv text/unknown-cell))
      (is (str/includes? csv "不可")))))

(deftest the-wage-ledger-csv-is-deterministic-and-column-ordered
  (let [rows (mapv daicho/row (runs (clean-meisai)))]
    (is (apply = (repeatedly 20 #(daicho/->csv rows))))
    (is (= (str/join "," (map :column/header daicho/columns))
           (first (str/split-lines (daicho/->csv rows)))))))

(deftest the-wage-ledger-says-whether-the-hours-produced-the-figure
  (testing "a monthly run's timesheets are not read, and a ledger that did
            not say so would make `read and agreed` and `never read` print
            the same"
    (let [csv (daicho/->csv (mapv daicho/row (runs (clean-meisai))))]
      (is (str/includes? csv "使っていない")))))

(deftest a-wage-ledger-of-nothing-reports-a-row-count-of-zero
  (testing "an empty file must not read as a clean one"
    (is (str/includes? (daicho/->json []) "\"row_count\":0"))))

;; ---------------------------------------------------------------------------
;; 控除額集計
;; ---------------------------------------------------------------------------

(deftest a-summary-refuses-a-total-that-would-under-report
  (testing "a period where one contribution was never observed produces
            未確定 and NOT the sum of the ones that were — an operator types
            this number into a payment"
    (let [s (summary/summarise {:employer (f/employer) :period f/period
                                :runs (runs (held-meisai))})]
      ;; the held run is excluded from the totals entirely, so the schemes
      ;; are totals over zero contributors — which the report says.
      (is (= 1 (count (:summary/excluded s))))
      (is (zero? (:summary/included s)))
      (is (every? #(zero? (:scheme/contributors %)) (:summary/schemes s))))))

(deftest a-scheme-total-over-zero-contributors-is-visible-as-such
  (testing "0 over 0 contributors and 0 over 3 contributors are different
            facts and must not print the same"
    (let [s (summary/summarise {:employer (f/employer) :period f/period
                                :runs (runs (clean-meisai))})
          hi (first (filter #(= :health-insurance-withheld (:scheme/key %))
                            (:summary/schemes s)))]
      (is (= 1 (:scheme/contributors hi)))
      (is (= f/health-insurance (prov/amount (:scheme/figure hi)))))))

(deftest the-summary-total-blocks-on-any-unresolved-scheme
  (let [s (summary/summarise
           {:employer (f/employer) :period f/period
            :runs (conj (runs (clean-meisai))
                        {:period f/period :contract-id "c-2" :worker "乙"
                         :meisai (assoc (clean-meisai)
                                        :meisai/deductions
                                        (assoc-in (vec (:meisai/deductions
                                                        (clean-meisai)))
                                                  [0 :line/figure]
                                                  (prov/held "所得税" "未計上")))})})]
    (is (= :unknown (:figure/provenance (:summary/total s))))))

(deftest the-summary-names-who-each-withholding-is-owed-to
  (let [s (summary/summarise {:employer (f/employer) :period f/period
                              :runs (runs (clean-meisai))})]
    (is (every? :scheme/remit-to (:summary/schemes s)))
    (is (every? :scheme/provision (:summary/schemes s)))
    (is (str/includes? (summary/->csv s) "所得税法 第百八十三条第一項"))))

(deftest the-summary-csv-is-deterministic
  (let [s (summary/summarise {:employer (f/employer) :period f/period
                              :runs (runs (clean-meisai))})]
    (is (apply = (repeatedly 20 #(summary/->csv s))))))

;; ---------------------------------------------------------------------------
;; 振込データ
;; ---------------------------------------------------------------------------

(deftest zengin-is-refused-for-every-input-always
  (testing "there is no argument that makes this return bytes. The record
            layout has not been read, and a file assembled from memory would
            move somebody's salary"
    (doseq [rs [[] [{:contract (f/contract)}]
                [{:contract (f/contract {:bank/payee-name-kana nil})}]]]
      (let [z (bank/zengin {:runs rs})]
        (is (= :unsupported (:zengin/status z)))
        (is (nil? (:zengin/bytes z)))
        (is (seq (:zengin/also-needed z)))))))

(deftest the-zengin-refusal-tells-an-operator-what-half-is-theirs
  (testing "an operator told `unsupported` learns nothing"
    (let [z (bank/zengin {:runs [{:contract (f/contract
                                             {:bank/account-number nil})}]})
          per (first (:zengin/per-contract z))]
      (is (= [:bank/account-number] (:zengin/missing per)))
      (is (= 4 (count (:zengin/registered per)))))))

(deftest halfwidth-is-checked-and-never-transliterated
  (testing "the reading of a Japanese surname is not derivable from its
            characters, and a name that does not match the account is a
            payment that bounces"
    (is (bank/halfwidth? "ｶｸｳ ｼﾖｳｼﾞ"))
    (is (bank/halfwidth? "ABC-123"))
    (is (not (bank/halfwidth? "カクウ")))
    (is (not (bank/halfwidth? "架空")))
    (is (not (bank/halfwidth? "ＡＢＣ")))
    (testing "and a blank is not halfwidth — an empty payee name is a missing
              registration, not a passing check"
      (is (not (bank/halfwidth? "")))
      (is (not (bank/halfwidth? nil))))))

(deftest the-halfwidth-check-declares-what-it-does-not-establish
  (is (str/includes? (:check/does-not-establish bank/halfwidth-check-limits)
                     "全銀協"))
  (is (str/includes? (bank/->json (bank/prepare {:employer (f/employer)
                                                 :period f/period :runs []}))
                     "does_not_establish")))

(deftest a-full-width-payee-name-refuses-the-transfer-line
  (let [p (bank/payee {:contract (f/contract {:bank/payee-name-kana "カクウ"})
                       :meisai (clean-meisai) :period f/period})]
    (is (= :refused (:payee/status p)))
    (is (= [:bank/payee-name-kana] (mapv :missing/key (:payee/missing p))))
    (is (str/includes? (:payee/why p) "受取人名"))))

(deftest a-run-that-must-not-be-paid-is-refused-before-any-bank-field
  (testing "telling an operator to register an account number for a payment
            that is on hold sends them to do work that changes nothing"
    (let [p (bank/payee {:contract (f/contract {:bank/account-number nil
                                                :bank/payee-name-kana nil})
                         :meisai (held-meisai) :period f/period})]
      (is (= :refused (:payee/status p)))
      (is (empty? (:payee/missing p)))
      (is (str/includes? (:payee/why p) "支払える状態ではない")))))

(deftest a-transfer-file-never-silently-drops-a-payee
  (testing "a file listing three of four employees pays three of four, and
            the missing one is discovered by the person who did not get paid"
    (let [t (bank/prepare
             {:employer (f/employer) :period f/period
              :runs [{:contract (f/contract) :meisai (clean-meisai)}
                     {:contract (f/contract {:bank/account-number nil})
                      :meisai (clean-meisai)}]})]
      (is (= 1 (count (:transfer/lines t))))
      (is (= 1 (count (:transfer/refused t))))
      (is (false? (:transfer/complete? t)))
      (is (str/includes? (bank/->json t) "口座番号")))))

(deftest the-transfer-csv-declares-that-it-is-not-a-standard
  (is (= :none (:format/standard bank/format-declaration)))
  (is (str/includes? (bank/->json (bank/prepare {:employer (f/employer)
                                                 :period f/period :runs []}))
                     "\"format_standard\":\"none\"")))

(deftest the-transfer-csv-is-deterministic
  (let [t (bank/prepare {:employer (f/employer) :period f/period
                         :runs [{:contract (f/contract) :meisai (clean-meisai)}]})]
    (is (apply = (repeatedly 20 #(bank/->csv t))))
    (is (= f/net (:transfer/total t)))))
