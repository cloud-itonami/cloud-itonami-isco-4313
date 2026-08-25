(ns payroll.fixtures
  "Shared fixtures for the vertical slice.

  ## Nothing here is a real person, a real company or a real account

  Every name is invented, every bank field is `0000` / `ZZZ`, and every DID is
  `did:key:zFIXTURE…`. That is a rule and not an accident: this repository is
  public under AGPL, and a fixture is the easiest place for a real employee's
  wage, a real account number or a real MoneyForward employee id to enter a
  repository and never leave it.

  `payroll.fixtures-test` asserts the property mechanically — it scans this
  namespace's own source for the shapes real data takes — so the rule
  survives somebody adding a fixture in a hurry.

  ## The employee is one monthly salaried worker, because that is the case

  `gftd-japan` is a company with one monthly salaried employee, which is the
  shape this repository is being pointed at. The figures are round and
  obviously synthetic: 280,000 gross, and deductions that add up."
  (:require [kotoba.labor :as labor]
            [payroll.meisai :as meisai]
            [payroll.store :as store]))

(def employer-id "emp-fixture")
(def caller-did "did:key:zFIXTUREnotarealkey")
(def allowlist-string (str caller-did "=" employer-id))
(def contract-id "c-fixture-1")
(def worker "従業員甲")
(def period "2026-08")

(def gross 280000)

;; The four contributions and the income tax. 厚生年金 is the only one bounded
;; by a rate in a statute: 標準報酬月額 280,000 × 183/2000 = 25,620 exactly, so
;; that figure is what `payroll.shakai-hoken/within-one-yen?` will accept. The
;; others are round numbers with no claim to correctness, which is the point —
;; nothing in this repository certifies them.
(def standard-remuneration 280000)
(def employees-pension 25620)
(def health-insurance 14000)
(def care-insurance 2500)
(def employment-insurance 1680)
(def income-tax 6000)

(def deduction-total
  (+ income-tax health-insurance care-insurance employees-pension
     employment-insurance))

(def net (- gross deduction-total))

(defn employer
  ([] (employer {}))
  ([overrides]
   (merge {:client-id employer-id :name "架空商事株式会社" :jurisdiction [:jp]}
          overrides)))

(defn contract
  "A fully registered monthly contract — every fact an operator can register,
  registered. Overrides are how a test removes ONE of them and asserts the
  refusal that follows."
  ([] (contract {}))
  ([overrides]
   (merge (labor/contract contract-id worker employer-id "事務" :monthly gross
                          :currency "JPY")
          {:employment/health-insurance-insured? true
           :employment/care-insurance-second-category? true
           :employment/employees-pension-insured? true
           :employment/employment-insurance-insured? true
           :employment/standard-remuneration-monthly-yen standard-remuneration
           :employment/standard-remuneration-month "2026-07"
           :employment/year-end-declaration-filed? true
           :bank/financial-institution-code "0000"
           :bank/branch-code "000"
           :bank/account-type :ordinary
           :bank/account-number "0000000"
           ;; halfwidth katakana, and an obviously fictional name
           :bank/payee-name-kana "ｶｸｳ ｼﾖｳｼﾞ"
           :mf/employee-number "9001"}
          overrides)))

(defn run-fields
  "The request fields for a clean run — every contribution accounted for."
  ([] (run-fields {}))
  ([overrides]
   (merge {:contract-id contract-id
           :period period
           :deductions deduction-total
           :income-tax-withheld income-tax
           :health-insurance-withheld health-insurance
           :care-insurance-withheld care-insurance
           :employees-pension-withheld employees-pension
           :employment-insurance-withheld employment-insurance}
          overrides)))

(defn proposal
  ([] (proposal {}))
  ([overrides]
   (merge (run-fields)
          {:op :draft-payroll-run :effect :propose
           :gross gross :net net :confidence 0.95 :stake :low}
          overrides)))

(defn fresh-store
  "A store with the employer and the contract registered, and nothing else."
  ([] (fresh-store {}))
  ([{:keys [employer-overrides contract-overrides timesheets]}]
   (let [st (store/mem-store)]
     (store/register-client! st (employer (or employer-overrides {})))
     (store/register-contract! st (contract (or contract-overrides {})))
     (doseq [t (or timesheets [])] (store/register-timesheet! st t))
     st)))

(defn run-body
  "The EDN body `payroll.edge.endpoints` accepts, for a clean run."
  ([] (run-body {}))
  ([overrides] (pr-str (run-fields overrides))))

(defn lines
  "`payroll.meisai/lines` over a store and a verdict, the way the console
  builds it."
  [{:keys [contract* timesheets run verdict disposition]}]
  (meisai/lines {:contract (or contract* (contract))
                 :timesheets (or timesheets [])
                 :run (or run (proposal))
                 :verdict verdict
                 :disposition (or disposition :commit)}))

(defn verdict-for
  "The governor's real verdict for a proposal against a store.

  Built by RUNNING the governor rather than hand-written. A hand-written
  verdict is a second opinion that can disagree with the one runs are
  actually decided on, and every artifact in this repository reads the
  verdict — so a fixture verdict that drifted would let the payslip tests
  pass over figures the governor would have held."
  ([] (verdict-for (fresh-store) (proposal)))
  ([st p]
   ((requiring-resolve 'payroll.governor/check)
    {:client-id employer-id} {} p st)))
