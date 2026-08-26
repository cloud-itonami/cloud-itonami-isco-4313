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
            [payroll.juminzei :as juminzei]
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

;; 住民税 is a REGISTERED figure and not a computed one, so the fixture
;; registers a notice exactly as an operator would — a municipality, a tax
;; year and twelve monthly amounts. Without one every fixture run carries an
;; `:unknown` 住民税 line and is not payable, which is the correct behaviour
;; and is asserted directly in `payroll.juminzei-test`.
(def municipality "架空区")
(def resident-tax 8200)

(def resident-tax-notice-as-transcribed
  "What an operator types off the paper — the ARGUMENT to
  `payroll.juminzei/admit-notice`, not its result.

  Kept separately from the admitted record below because the two are not
  interchangeable at the write boundary: `admit-registration` REFUSES a body
  carrying `:notice/employer`, and the admitted record carries one (it is
  stamped from the verified caller). A test that registered the record would
  be testing the employer-naming refusal by accident."
  {:notice/kind :notice/decision
   :notice/municipality municipality
   :notice/tax-year "2026"
   ;; 通知書番号. Fictional in the same way every other code here is: a
   ;; 令和8年度 paper would carry something shaped like this, and this one is
   ;; all zeroes so it cannot be mistaken for a number a municipality issued.
   :notice/reference "R8-0000-0000"
   :notice/revision 0
   :notice/replaces nil
   :notice/designated-number "0000000"
   :notice/months (into {} (for [k juminzei/month-keys] [k resident-tax]))
   ;; the twelve months, added up. Written as the multiplication rather than
   ;; as 98400 so that changing `resident-tax` cannot leave a notice whose
   ;; months no longer sum to its 年税額 — which `admit-notice` refuses, and
   ;; which would then read as a bug in the refusal.
   :notice/annual-total (* 12 resident-tax)
   ;; REGISTERED, never derived: this actor holds no clock. 5月末日 is when
   ;; the guide says a 決定通知書 arrives.
   :notice/registered-at "2026-05-31"})

(def resident-tax-notice
  (:notice/record
   (juminzei/admit-notice employer-id resident-tax-notice-as-transcribed)))

(def resident-tax-notice-id
  "The id the decision notice will be stored under, computed the one way it is
  ever computed. A literal here would be a second copy of `notice-id`'s rule,
  and the copy is the one that drifts."
  (juminzei/notice-id resident-tax-notice))

(def resident-tax-revised
  "The corrected monthly figure, kept next to the original so a test asserting
  which of the two governs a month reads as a comparison rather than as two
  magic numbers."
  9000)

(def resident-tax-notice-revised-as-transcribed
  "A 変更通知書 that REPLACES the decision notice, as transcribed.

  It carries `:notice/revision 1` and names `resident-tax-notice-id` as what
  it replaces, which is what `payroll.juminzei/admit-registration` requires of
  a correction — and its own 通知書番号 is a DIFFERENT paper, because a
  municipality that reissues sends a new document rather than editing the one
  the employer already holds.

  **`:notice/effective-from` is 6月 and it carries all twelve months, and that
  is not incidental.** A notice that replaces another puts the replaced paper
  out of force (`payroll.juminzei/effective-notices`), so one that replaced a
  full-year 決定通知書 while carrying only 10月 onward would leave 6月–9月 with
  no notice in force at all — `coverage` would report four uncovered months and
  `assess` would refuse them. That is the correct answer to that registration,
  and it is not the shape a fixture should hand every test that needs `a
  correction`. A mid-year change that does NOT supersede the decision is a
  different registration: revision 0, `:notice/replaces` nil, and its own
  `:notice/effective-from`.

  `:notice/annual-total` is nil, which `admit-notice` allows for a revision and
  never for a decision."
  {:notice/kind :notice/revision
   :notice/municipality municipality
   :notice/tax-year "2026"
   :notice/reference "R8-0000-0001"
   :notice/revision 1
   :notice/replaces resident-tax-notice-id
   :notice/designated-number "0000000"
   :notice/effective-from :juminzei/m06
   :notice/months (into {} (for [k juminzei/month-keys]
                             [k resident-tax-revised]))
   :notice/annual-total nil
   :notice/registered-at "2026-07-15"})

(def resident-tax-notice-revised
  (:notice/record
   (juminzei/admit-notice employer-id
                          resident-tax-notice-revised-as-transcribed)))

(defn notices-store
  "A `MemStore` with `transcriptions` registered through
  `payroll.juminzei/register-notice!`, in order.

  Through the real write boundary and not by seeding the atom, so a fixture
  cannot register something the admission layer would have refused — a store
  seeded past `admit-registration` would let a test assert a read of a record
  that can never exist. The arguments are therefore the `-as-transcribed`
  maps and not the admitted records."
  ([] (notices-store [resident-tax-notice-as-transcribed]))
  ([transcriptions]
   (let [st (store/mem-store)]
     (doseq [n transcriptions]
       (juminzei/register-notice! st {:employer employer-id :notice n}))
     st)))

(defn juminzei-assessment
  ([] (juminzei-assessment period))
  ([p] (juminzei/assess {:period p
                         :notices [resident-tax-notice]
                         :obligation :special-collection})))

(def deduction-total
  (+ income-tax health-insurance care-insurance employees-pension
     employment-insurance resident-tax))

(def net (- gross deduction-total))

(defn employer
  "A fully registered employer — the same rule the contract fixture keeps,
  minus that fixture's one deliberate exception: every fact an operator CAN
  register is registered, so that a test which removes one is testing that
  removal and nothing else.

  The `:zengin/*` fields are the 依頼人 half of the 全銀 総合振込 header. They
  are here rather than only in the 全銀 tests because
  `payroll.edge.console-test/every-artifact-can-be-exported-in-every-format-it-declares`
  exports every artifact in every format it declares, and an employer missing
  them would make that test assert a 400 for the one artifact whose bytes
  matter most.

  All-zero codes for the same reason the contract's account number is
  all-zeroes (`payroll.fixtures-test` scans for plausible ones), and the
  依頼人名 says 架空 in its own halfwidth characters."
  ([] (employer {}))
  ([overrides]
   (merge {:client-id employer-id :name "架空商事株式会社" :jurisdiction [:jp]
           :zengin/origin-name-kana "ｶｸｳｼﾖｳｼﾞ(ｶ"
           :zengin/origin-branch-code "000"
           :zengin/origin-account-number "0000000"
           :zengin/origin-bank-name-kana "ﾍﾟｲﾍﾟｲｷﾞﾝｺｳ"
           :zengin/origin-branch-name-kana "ｶｸｳｼﾃﾝ"
           ;; MMDD, registered per file. This actor holds no calendar and
           ;; does not derive it from the period.
           :zengin/transfer-date-mmdd "0825"}
          overrides)))

(defn contract
  "A fully registered monthly contract — every fact an operator can register,
  registered, with ONE deliberate exception.

  `:employment/resident-tax-obligation` is deliberately ABSENT, so the
  default fixture is an employee nobody has classified and its 住民税 line is
  a refusal rather than a figure — held once a notice is registered, unknown
  before that. A test that wants the declared line registers
  `:special-collection` itself, which is what keeps held-versus-declared a
  thing tests choose rather than a thing the fixture bakes in.

  Overrides are how a test removes ONE of the registered facts and asserts
  the refusal that follows."
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
           :resident-tax-withheld resident-tax
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
  [{:keys [contract* timesheets run verdict disposition juminzei]}]
  (meisai/lines {:contract (or contract* (contract))
                 :timesheets (or timesheets [])
                 :run (or run (proposal))
                 :verdict verdict
                 ;; `:none` means "this run was never assessed for 住民税",
                 ;; which is the state a run is in before a notice is
                 ;; registered — and is not the same as passing nil, which
                 ;; would silently get the fixture's own assessment.
                 :juminzei (when-not (= :none juminzei)
                             (or juminzei (juminzei-assessment)))
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
