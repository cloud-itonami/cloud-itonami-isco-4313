(ns payroll.shiwake
  "仕訳 — an approved payroll run as a journal entry request.

  Deciding is not bookkeeping. **A payroll run that was approved and never
  became an entry is wages nobody's books show, and withholding nobody's
  books owe.** `cloud-itonami-isco-4311` owns the ledger; this namespace
  produces the value that actor accepts at `POST /api/entry`.

  ## Three lines, not two

  An expense claim is one debit and one credit. Payroll is not: the gross
  wage is an expense, and it splits on the credit side into what the employee
  receives and what the employer is now **holding on someone else's behalf**.

  ```text
  借方  給料手当           gross
  貸方  預り金             income tax withheld  ← 所得税法 第百八十三条第一項
  貸方  社会保険料預り金   健康保険 + 介護保険 + 厚生年金
  貸方  雇用保険料預り金   雇用保険
  貸方  未払金             net
  ```

  Five lines once 社会保険 exists, and the two new credits are TWO because
  they are owed to different creditors under different regimes: 健保法
  第百六十一条第二項 and 厚年法 第八十二条第二項 make the employer liable to
  the insurer month by month, while 労働保険徴収法 collects 労働保険料 for a
  保険年度. Netting them would put a monthly liability and an annual one in
  one balance, and the balance sheet would stop being able to say when either
  falls due.

  Both are omitted entirely when their total is zero, exactly as the 所得税
  line is: 預り金 0 asserts a liability of nothing, which is a different claim
  from having none.

  第百八十三条第一項 obliges the payer to 徴収し … 国に納付しなければならない.
  Withheld tax is therefore not a reduction of the expense; it is a liability
  the employer owes the state until it is remitted. Netting it into one
  credit line would make that liability disappear from the balance sheet
  while the obligation continued to exist.

  ## It produces a value; it makes no call

  No HTTP, no client, no reference to 4311 — asserted by a test that scans
  this namespace's own source. This actor proposes; writing into another
  actor's ledger would be the actuation the design refuses. And a call would
  make the accounts this actor's business when they are the client's chart —
  `kotoba-lang/shohyo` refuses to guess what an account is because a
  statement that guessed still balances.

  ## A nil contribution is not zero, and the identity is what says so

  The four 社会保険 fields may legitimately be absent — a worker who is not a
  被保険者 of a scheme has no contribution to that scheme, and
  `payroll.shakai-hoken` answers `:not-covered` for it. This namespace cannot
  tell that nil from the nil that means somebody forgot, because it reads a
  run and not a verdict. It does not have to: **the identity catches it.**
  `gross = 所得税 + 社会保険 + 雇用保険 + net` fails the moment a contribution
  that was actually deducted is missing here, and the run is `:unusable-run`
  rather than an entry that balances by having lost the difference.

  ## The withheld amount is carried, never computed

  `taxlaw` records `:taxlaw/amount-checked? false`: 別表第二 and 別表第五
  were not read, so nothing in this fleet certifies how much should have been
  withheld. This namespace inherits that exactly — it moves the figure the
  run declared into 預り金 and computes none of it. A run that declares no
  withheld amount in a jurisdiction that obliges withholding is refused by
  the governor before it ever reaches here; one that reaches here with the
  field absent is `:unusable-run`, not zero."
  (:require [clojure.string :as str]))

(defn- amount? [x] (and (number? x) (not (neg? x))))

(defn entry-request
  "An approved payroll run as the `:draft-entry` request `isco-4311` accepts,
  or the reason there is none.

      {:shiwake/status :not-approved}  held or escalated
      {:shiwake/status :no-mapping}    the three accounts are not all mapped
      {:shiwake/status :unusable-run}  gross/net/withheld missing or not a
                                       number, or the arithmetic disagrees
      {:shiwake/status :ok :shiwake/request {...}}

  `:not-approved` is its own status rather than nil, because a caller that
  read \"no entry\" as \"nothing to do\" would skip exactly the runs somebody
  has to look at — and an unapproved payroll run is the one somebody must.

  `mapping` is `{:wages a :withholding a :payable a}`, plus
  `:social-insurance` and `:employment-insurance` when the run has those
  contributions. The first three are all-or-none: a half-filled mapping is no
  mapping, since an entry missing one line balances by having lost it. The
  last two are required exactly when their line would be non-zero — demanding
  them from a run with no social insurance would refuse entries this
  namespace produced correctly before 社会保険 existed, and pre-mapping an
  account for a liability that never arises is how a chart of accounts fills
  up with accounts nobody posts to."
  [{:keys [disposition run] :as _committed} mapping]
  (let [{:keys [contract-id period gross net income-tax-withheld currency
                health-insurance-withheld care-insurance-withheld
                employees-pension-withheld employment-insurance-withheld]} run
        {:keys [wages withholding payable social-insurance employment-insurance]} mapping
        blank? #(str/blank? (str %))
        ;; nil is absent, not malformed — see the docstring. A PRESENT value
        ;; that is not a non-negative number is malformed and is caught by the
        ;; `every?` branch below.
        ;;
        ;; TOTAL, and it has to be: `let` bindings are eager, so `si-total` is
        ;; computed before any branch of the `cond` runs. A version that
        ;; returned a string unchanged threw a ClassCastException out of
        ;; `reduce +` before the branch that refuses that string could ever be
        ;; reached — measured 2026-08-18 by a test written before the code was
        ;; looked at. Coercing here is safe rather than lax, because the
        ;; malformed branch inspects the RAW values and fires first.
        present (fn [x] (if (number? x) x 0))
        si-parts [health-insurance-withheld care-insurance-withheld
                  employees-pension-withheld]
        si-total (reduce + 0 (map present si-parts))
        ei-total (present employment-insurance-withheld)]
    (cond
      (not= :commit disposition)
      {:shiwake/status :not-approved :shiwake/disposition disposition}

      (not (and (amount? gross) (amount? net) (amount? income-tax-withheld)))
      {:shiwake/status :unusable-run
       :shiwake/why (str "gross, net and income-tax-withheld must all be "
                         "non-negative numbers; got "
                         (pr-str {:gross gross :net net
                                  :income-tax-withheld income-tax-withheld}))}

      (not (every? #(or (nil? %) (amount? %))
                   (conj si-parts employment-insurance-withheld)))
      {:shiwake/status :unusable-run
       :shiwake/why (str "every 社会保険 contribution that is present must be a "
                         "non-negative number; got "
                         (pr-str {:health-insurance-withheld health-insurance-withheld
                                  :care-insurance-withheld care-insurance-withheld
                                  :employees-pension-withheld employees-pension-withheld
                                  :employment-insurance-withheld
                                  employment-insurance-withheld}))}

      ;; The governor already recomputes wages and checks net = gross −
      ;; deductions. Checking the identity the ENTRY depends on is not
      ;; duplication: this one is gross = withheld + net, and an entry built
      ;; from figures that do not satisfy it would be unbalanced -- which
      ;; 4311 would refuse, but by then the reason would be a currency-level
      ;; arithmetic message rather than "this run's figures disagree".
      (not= gross (+ income-tax-withheld si-total ei-total net))
      {:shiwake/status :unusable-run
       :shiwake/why (str "gross " gross " ≠ 所得税 " income-tax-withheld
                         " + 社会保険 " si-total
                         " + 雇用保険 " ei-total
                         " + net " net)}

      (or (blank? wages) (blank? withholding) (blank? payable))
      {:shiwake/status :no-mapping
       :shiwake/why (str "wages, withholding and payable accounts must all be "
                         "mapped; this actor does not choose them")}

      (and (pos? si-total) (blank? social-insurance))
      {:shiwake/status :no-mapping
       :shiwake/why (str "this run withheld " si-total " of 社会保険料 and no "
                         ":social-insurance account is mapped; the employer "
                         "holds that on the insurer's behalf and it cannot be "
                         "netted into the 所得税 預り金")}

      (and (pos? ei-total) (blank? employment-insurance))
      {:shiwake/status :no-mapping
       :shiwake/why (str "this run withheld " ei-total " of 雇用保険料 and no "
                         ":employment-insurance account is mapped; 労働保険料 "
                         "is collected for a 保険年度 and is a different "
                         "liability from the monthly 社会保険料")}

      (blank? contract-id)
      {:shiwake/status :unusable-run
       :shiwake/why "the run cites no contract, so the entry would have no source document"}

      :else
      {:shiwake/status :ok
       :shiwake/request
       {:op :draft-entry
        ;; The contract is the source document. 4311 refuses an entry citing
        ;; a document its own registry does not know, so a run approved here
        ;; against a contract that was never registered there is refused
        ;; rather than posted. The ledger's registry is the one that counts.
        :source-doc contract-id
        :memo (when period (str "payroll " period))
        :lines (cond-> [{:side :dr :account wages :amount gross :currency currency}]
                 ;; A zero withholding line is omitted rather than posted as
                 ;; zero: 預り金 0 asserts a liability of nothing, which is a
                 ;; different claim from having none. The entry still
                 ;; balances, because gross = 0 + net.
                 (pos? income-tax-withheld)
                 (conj {:side :cr :account withholding
                        :amount income-tax-withheld :currency currency})
                 ;; same rule, one liability over.
                 (pos? si-total)
                 (conj {:side :cr :account social-insurance
                        :amount si-total :currency currency})
                 (pos? ei-total)
                 (conj {:side :cr :account employment-insurance
                        :amount ei-total :currency currency})
                 true
                 (conj {:side :cr :account payable :amount net :currency currency}))}})))

(defn entry-requests
  "`entry-request` over many committed runs, keeping the refusals.

  `{:ok [...] :skipped [...]}`, each refusal carrying the run it refused. A
  batch that filtered would report a clean run and leave the unconvertible
  ones invisible."
  [committed mapping]
  (let [rs (map #(assoc (entry-request % mapping) :shiwake/run %) committed)]
    {:ok (vec (filter #(= :ok (:shiwake/status %)) rs))
     :skipped (vec (remove #(= :ok (:shiwake/status %)) rs))}))
