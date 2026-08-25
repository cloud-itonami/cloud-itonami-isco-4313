(ns payroll.edge.console-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.edge.console :as console]
            [payroll.edge.endpoints :as api]
            [payroll.fixtures :as f]
            [payroll.store :as store]
            [payroll.ui.views :as views]))

(def allowlist (api/parse-allowlist f/allowlist-string))

(defn- base [st]
  {:store st :store-mode :ephemeral :allowlist allowlist
   :caller-did f/caller-did :css "" :self-origin "http://localhost:9"
   :durability {:store/what "MemStore" :store/why "消える"
                :store/survives-process-restart? false}})

(defn- GET
  ([st path] (GET st path {}))
  ([st path query]
   (console/route (base st) {:method :get :path path :query query})))

(defn- POST
  ([st path form] (POST st path form "http://localhost:9"))
  ([st path form origin]
   (console/route (base st) {:method :post :path path :form form
                             :origin origin})))

;; ---------------------------------------------------------------------------
;; Form decoding
;; ---------------------------------------------------------------------------

(deftest a-literal-plus-survives-decoding
  (testing "`+` is turned into `%20` BEFORE decoding. A post-decode
            replacement would turn a `%2B` into a space and silently corrupt
            any field an operator typed a plus into"
    (is (= {"a" "1 2"} (console/parse-form "a=1+2")))
    (is (= {"a" "1+2"} (console/parse-form "a=1%2B2")))
    (is (= {"a" "あ"} (console/parse-form "a=%E3%81%82")))))

(deftest a-repeated-key-keeps-the-first-value
  (testing "so appending an override to a submitted body does not work"
    (is (= {"a" "1"} (console/parse-form "a=1&a=2")))))

(deftest an-empty-body-decodes-to-nothing-rather-than-throwing
  (is (= {} (console/parse-form "")))
  (is (= {} (console/parse-form nil))))

;; ---------------------------------------------------------------------------
;; The gates
;; ---------------------------------------------------------------------------

(deftest an-unconfigured-store-serves-503
  (is (= 503 (:status (console/route (assoc (base (f/fresh-store))
                                            :store-mode nil)
                                     {:method :get :path "/console"}))))
  (is (= 503 (:status (console/route (assoc (base (f/fresh-store)) :store nil)
                                     {:method :get :path "/console"})))))

(deftest an-absent-allowlist-serves-503-and-never-an-open-console
  (let [r (console/route (assoc (base (f/fresh-store)) :allowlist nil)
                         {:method :get :path "/console"})]
    (is (= 503 (:status r)))
    (is (str/includes? (:body r) "allow-list"))))

(deftest a-caller-not-on-the-allowlist-is-403
  (is (= 403 (:status (console/route (assoc (base (f/fresh-store))
                                            :caller-did "did:key:zSomeoneElse")
                                     {:method :get :path "/console"})))))

(deftest a-cross-origin-post-is-refused
  (let [r (POST (f/fresh-store) "/console/contract"
                {"contract-id" "c-x" "worker" "w" "wage-type" "monthly"
                 "rate" "1"}
                "http://evil.example")]
    (is (= 403 (:status r)))
    (is (str/includes? (:body r) "cross-origin"))))

(deftest a-post-with-no-origin-is-allowed
  (testing "a non-browser client sends none, and refusing those would make
            the console unusable from anything but a browser without adding
            protection — the attack this refuses is a page on another origin,
            and a page always sends one"
    (is (console/same-origin? nil "http://localhost:9"))
    (is (console/same-origin? "" "http://localhost:9"))
    (is (console/same-origin? "http://localhost:9" "http://localhost:9"))
    (is (not (console/same-origin? "http://evil.example" "http://localhost:9")))))

(deftest a-get-is-not-origin-checked
  (is (= 200 (:status (GET (f/fresh-store) "/console")))))

;; ---------------------------------------------------------------------------
;; Routing
;; ---------------------------------------------------------------------------

(deftest every-view-in-the-table-has-a-route
  (doseq [{:view/keys [path label]} views/views]
    (is (= 200 (:status (GET (f/fresh-store) path))) label)))

(deftest a-path-nobody-declared-is-404
  (is (= 404 (:status (GET (f/fresh-store) "/console/disburse")))))

(deftest a-wrong-method-on-a-real-path-is-405-and-not-404
  (testing "`POST /console/ledger` is a caller using the wrong verb on a real
            page and `POST /console/disburse` is a caller inventing one"
    (is (= 405 (:status (POST (f/fresh-store) "/console/ledger" {}))))
    (is (= 404 (:status (POST (f/fresh-store) "/console/disburse" {}))))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest a-contract-can-be-registered-and-nothing-is-defaulted
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (let [r (POST st "/console/contract"
                  {"contract-id" "c-new" "worker" "新人" "wage-type" "monthly"
                   "rate" "300000" "health-insurance-insured" ""})]
      (is (= 200 (:status r)))
      (let [c (store/contract-of st "c-new")]
        (is (= f/employer-id (:contract/employer c)))
        (is (not (contains? c :employment/health-insurance-insured?)))))))

(deftest an-empty-select-stays-absent-and-a-false-is-written
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (POST st "/console/contract"
          {"contract-id" "c-a" "worker" "a" "wage-type" "monthly" "rate" "1"
           "health-insurance-insured" "false"})
    (is (false? (:employment/health-insurance-insured?
                 (store/contract-of st "c-a"))))))

(deftest a-refused-registration-writes-nothing
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (let [r (POST st "/console/contract"
                  {"contract-id" "c-bad" "worker" "b" "wage-type" "monthly"
                   "rate" "not a number"})]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "flash-error"))
      (is (nil? (store/contract-of st "c-bad"))))))

(deftest a-registration-cannot-name-another-employer
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (POST st "/console/contract"
          {"contract-id" "c-x" "worker" "x" "wage-type" "monthly" "rate" "1"})
    (is (= f/employer-id (:contract/employer (store/contract-of st "c-x"))))))

;; ---------------------------------------------------------------------------
;; A governed run through the console
;; ---------------------------------------------------------------------------

(deftest a-clean-run-through-the-console-commits
  (let [st (f/fresh-store)
        r (POST st "/console/run"
                {"contract-id" f/contract-id "period" f/period
                 "deductions" (str f/deduction-total)
                 "income-tax-withheld" (str f/income-tax)
                 "health-insurance-withheld" (str f/health-insurance)
                 "care-insurance-withheld" (str f/care-insurance)
                 "employees-pension-withheld" (str f/employees-pension)
                 "employment-insurance-withheld" (str f/employment-insurance)})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "台帳に記録した"))
    (is (= 1 (count (store/ledger st))))
    (is (= :commit (:disposition (first (store/ledger st)))))))

(deftest a-run-missing-a-contribution-is-held-and-the-screen-says-why
  (let [st (f/fresh-store)
        r (POST st "/console/run"
                {"contract-id" f/contract-id "period" f/period
                 "deductions" (str f/income-tax)
                 "income-tax-withheld" (str f/income-tax)})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "保留した"))
    (is (str/includes? (:body r) "social-insurance-not-accounted-for"))
    (is (= :hold (:disposition (first (store/ledger st)))))))

(deftest a-non-numeric-amount-is-refused-before-the-graph
  (testing "`::bad` and not 0 — a field an operator typed `28,000` into is
            not an accounting of zero"
    (let [st (f/fresh-store)
          r (POST st "/console/run"
                  {"contract-id" f/contract-id "period" f/period
                   "income-tax-withheld" "6,000"})]
      (is (str/includes? (:body r) "非負の整数ではない"))
      (is (empty? (store/ledger st))))))

(deftest a-blank-amount-is-omitted-rather-than-sent-as-zero
  (let [{:keys [body]} (console/run-body {"period" "2026-08"
                                          "income-tax-withheld" ""
                                          "health-insurance-withheld" "1"})]
    (is (not (str/includes? body ":income-tax-withheld")))
    (is (str/includes? body ":health-insurance-withheld 1"))))

;; ---------------------------------------------------------------------------
;; Tenant isolation
;; ---------------------------------------------------------------------------

(deftest one-employer-cannot-see-anothers-runs
  (let [st (f/fresh-store)]
    ;; a second employer with their own contract and a committed run
    (store/register-client! st {:client-id "emp-other" :name "他社"})
    (store/register-contract! st (f/contract {:contract/id "c-other"
                                              :contract/employer "emp-other"}))
    (store/append-ledger! st {:client-id "emp-other" :contract-id "c-other"
                              :period f/period :disposition :commit
                              :record {:op :draft-payroll-run
                                       :payload {:gross 999999}}})
    (let [body (:body (GET st "/console/ledger"))]
      (is (not (str/includes? body "c-other")))
      (is (not (str/includes? body "999999"))))
    (testing "and their contract is not in this caller's context"
      (is (empty? (filter #(= "c-other" (:contract/id %))
                          (:contracts (console/context st f/employer-id {}))))))))

(deftest a-contract-registered-by-another-employer-is-not-listed
  (let [st (f/fresh-store)]
    (store/register-contract! st (f/contract {:contract/id "c-foreign"
                                              :contract/employer "emp-other"}))
    (store/append-ledger! st {:client-id f/employer-id
                              :contract-id "c-foreign"
                              :period f/period :disposition :commit})
    (testing "the ledger entry is the caller's, but the contract is not — and
              `contracts-of` filters on the contract's own employer"
      (is (empty? (console/contracts-of st f/employer-id ["c-foreign"]))))))

;; ---------------------------------------------------------------------------
;; Exports
;; ---------------------------------------------------------------------------

(defn- committed-store []
  (let [st (f/fresh-store)]
    (POST st "/console/run"
          {"contract-id" f/contract-id "period" f/period
           "deductions" (str f/deduction-total)
           "income-tax-withheld" (str f/income-tax)
           "health-insurance-withheld" (str f/health-insurance)
           "care-insurance-withheld" (str f/care-insurance)
           "employees-pension-withheld" (str f/employees-pension)
           "employment-insurance-withheld" (str f/employment-insurance)})
    st))

(deftest every-artifact-can-be-exported-in-every-format-it-declares
  (let [st (committed-store)]
    (doseq [{:artifact/keys [key formats label]} views/artifacts
            fmt formats]
      (let [r (GET st "/console/export"
                   {"kind" (name key) "format" (name fmt)
                    "contract-id" f/contract-id "period" f/period})]
        (is (= 200 (:status r)) (str label " " fmt))
        (is (seq (:body r)) (str label " " fmt))))))

(deftest an-export-with-no-matching-run-refuses-rather-than-emitting-an-empty-file
  (testing "an operator discovers an empty file after emailing it"
    (let [r (GET (committed-store) "/console/export"
                 {"kind" "payslip" "format" "json" "period" "1999-01"})]
      (is (= 400 (:status r)))
      (is (str/includes? (:body r) "出力が空であることは違う")))))

(deftest an-unknown-artifact-names-the-ones-that-exist
  (let [r (GET (committed-store) "/console/export" {"kind" "nenkin-todoke"})]
    (is (= 400 (:status r)))
    (is (str/includes? (:body r) "payslip"))))

(deftest an-unsupported-format-is-refused-and-not-silently-substituted
  (let [r (GET (committed-store) "/console/export"
               {"kind" "payslip" "format" "csv" "contract-id" f/contract-id})]
    (is (= 400 (:status r)))
    (is (str/includes? (:body r) "json"))))

(deftest an-export-carries-a-filename
  (let [r (GET (committed-store) "/console/export"
               {"kind" "wage-ledger" "format" "csv"})]
    (is (= "chingin-daicho.csv" (:filename r)))))

(deftest the-journal-export-refuses-for-want-of-a-chart-of-accounts
  (testing "the accounts are the employer's chart and this actor does not
            choose them — served as a refusal and not as an empty array,
            which would read as `nothing to post`"
    (let [r (GET (committed-store) "/console/export" {"kind" "journal"})]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "\"ok\":0"))
      (is (str/includes? (:body r) "\"skipped\":1"))
      (is (str/includes? (:body r) "この actor は選ばない")))))

;; ---------------------------------------------------------------------------
;; MoneyForward
;; ---------------------------------------------------------------------------

(deftest a-reconciliation-writes-nothing
  (testing "not the file, not a row, not a run. Pasting a payroll export into
            this box has to be a bounded act"
    (let [st (committed-store)
          before (count (store/ledger st))
          r (POST st "/console/mf"
                  {"period" f/period
                   "csv" (str "従業員番号,氏名,支給年月,総支給額,差引支給額\n"
                              "9001,従業員甲," f/period "," f/gross "," f/net)})]
      (is (= 200 (:status r)))
      (is (= before (count (store/ledger st))))
      (is (nil? (store/contract-of st "9001"))))))

(deftest a-reconciliation-of-a-differing-file-shows-the-difference
  (let [st (committed-store)
        r (POST st "/console/mf"
                {"period" f/period
                 "csv" (str "従業員番号,氏名,支給年月,総支給額,差引支給額\n"
                            "9001,従業員甲," f/period "," (inc f/gross) "," f/net)})]
    (is (str/includes? (:body r) "一致していない"))
    (is (str/includes? (:body r) "不一致"))))

(deftest an-unparseable-paste-does-not-throw
  (let [r (POST (committed-store) "/console/mf" {"period" f/period "csv" "???"})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "必須の列が無い"))))
