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
  借方  給料手当     gross
  貸方  預り金       income tax withheld   ← 所得税法 第百八十三条第一項
  貸方  未払金       net
  ```

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

  `mapping` is `{:wages a :withholding a :payable a}`. All three or none: a
  half-filled mapping is no mapping, since an entry missing one line balances
  by having lost it."
  [{:keys [disposition run] :as _committed} mapping]
  (let [{:keys [contract-id period gross net income-tax-withheld currency]} run
        {:keys [wages withholding payable]} mapping
        blank? #(str/blank? (str %))]
    (cond
      (not= :commit disposition)
      {:shiwake/status :not-approved :shiwake/disposition disposition}

      (not (and (amount? gross) (amount? net) (amount? income-tax-withheld)))
      {:shiwake/status :unusable-run
       :shiwake/why (str "gross, net and income-tax-withheld must all be "
                         "non-negative numbers; got "
                         (pr-str {:gross gross :net net
                                  :income-tax-withheld income-tax-withheld}))}

      ;; The governor already recomputes wages and checks net = gross −
      ;; deductions. Checking the identity the ENTRY depends on is not
      ;; duplication: this one is gross = withheld + net, and an entry built
      ;; from figures that do not satisfy it would be unbalanced -- which
      ;; 4311 would refuse, but by then the reason would be a currency-level
      ;; arithmetic message rather than "this run's figures disagree".
      (not= gross (+ income-tax-withheld net))
      {:shiwake/status :unusable-run
       :shiwake/why (str "gross " gross " ≠ withheld " income-tax-withheld
                         " + net " net)}

      (or (blank? wages) (blank? withholding) (blank? payable))
      {:shiwake/status :no-mapping
       :shiwake/why (str "wages, withholding and payable accounts must all be "
                         "mapped; this actor does not choose them")}

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
