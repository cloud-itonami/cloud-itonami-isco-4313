(ns payroll.mf.schema
  "The MoneyForward クラウド給与 export, as this repository GUESSES it.

  ## Nothing here has been verified against a real export

  No MoneyForward payroll export has been given to this repository. Every
  column name below is a **conjecture** — a plausible Japanese header, not a
  header anybody has seen in a file. `:mf/verified?` is `false` on every one
  of them and there is no code path that sets it to true.

  This is stated in the data rather than in prose because the data is what
  travels. An import report, a reconciliation and the operator screen all
  read `:mf/verified?` and all say `未検証の列名` where it is false, so an
  operator cannot come away believing the mapping was tested. The moment a
  real export exists, the honest change is to correct the names AND to flip
  the flag in the same commit, and `payroll.mf.schema-test` asserts that
  today the flag is false everywhere — so flipping it without changing
  anything else reddens.

  ## Why guess at all, rather than wait

  Because the shape of the problem is knowable without the file. An importer
  needs a column vocabulary, a validation policy, a provenance discipline and
  a reconciliation; all four can be built, tested and reviewed against a
  fixture, and none of them changes when the real header names arrive. What
  changes is a table of strings. Building the boundary now and marking it
  unverified is a different act from claiming it works — and pretending the
  latter is what this whole repository is built to refuse.

  ## 住民税 has a counterpart, and `:mf/no-counterpart` now has no members

  MoneyForward withholds 住民税 (特別徴収), and the column maps to
  `:resident-tax-withheld` — so it is compared field by field like every
  other deduction, and `no-counterpart-columns` is EMPTY. That mattered
  beyond this file: `payroll.cutover`'s third condition is *no
  `:mf/no-counterpart` column carried a value*, and while 住民税 had none the
  gate was unmeetable by construction, because every real export carries the
  column.

  `:mf/no-counterpart` stays in the vocabulary with no members, and that is
  deliberate. It is what `payroll.mf.import` maps a known-but-unrepresentable
  column to, and a column silently discarded during an import is a deduction
  that vanishes between two systems — the whole point of a reconciliation is
  to notice exactly that. The next column MoneyForward carries and this actor
  cannot represent lands there rather than nowhere.

  What is still true is that this actor COMPUTES no 住民税. The counterpart is
  a registered 決定通知書 (`payroll.juminzei`) with a payslip line
  (`payroll.meisai/deduction-lines`) whose figure is `:declared`, so a value
  this actor has no notice for is `:not-comparable` and one for an employee
  registered as 普通徴収 is `:only-in-mf`. Neither vanishes and neither is
  scored as agreement."
  (:require [clojure.string :as str]))

(def unverified-note
  (str "この列名はこの repository の推測であって、"
       "実際の MoneyForward クラウド給与のエクスポートと突き合わせたものではない。"
       "実ファイルが与えられていないので、名前が違えばこの列は読み込まれない"))

(def columns
  "The conjectured export vocabulary.

  `:mf/to` names what this repository would map the value to.
  `:mf/no-counterpart` means the concept does not exist here; the value is
  carried into the report and never into a run.

  `:mf/kind` drives validation: `:identity` must be non-blank, `:period` must
  be non-blank, `:yen` must be a non-negative whole number of yen after
  `parse-yen` strips a thousands separator."
  [{:mf/column "従業員番号" :mf/to :employee-number :mf/kind :identity
    :mf/required? true :mf/verified? false}
   {:mf/column "氏名" :mf/to :employee-name :mf/kind :identity
    :mf/required? true :mf/verified? false}
   {:mf/column "支給年月" :mf/to :period :mf/kind :period
    :mf/required? true :mf/verified? false}
   {:mf/column "総支給額" :mf/to :gross :mf/kind :yen
    :mf/required? true :mf/verified? false}
   {:mf/column "健康保険料" :mf/to :health-insurance-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "介護保険料" :mf/to :care-insurance-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "厚生年金保険料" :mf/to :employees-pension-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "雇用保険料" :mf/to :employment-insurance-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "所得税" :mf/to :income-tax-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   ;; 住民税 HAD no counterpart, and that made `docs/maturity.md`'s G1
   ;; unmeetable by construction — its sixth condition blocked any
   ;; reconciliation whose 住民税 column carried a value, so a real employer's
   ;; export could never reconcile. `payroll.juminzei` gives it one: a
   ;; municipality's 決定通知書, registered, with a payslip line
   ;; (`payroll.meisai/deduction-lines`) and a comparison field.
   ;;
   ;; What is still true is that this actor COMPUTES no 住民税. The counterpart
   ;; is a registered notice, so an export carrying a value this actor has no
   ;; notice for now differs (`:only-in-mf`) rather than vanishing.
   {:mf/column "住民税" :mf/to :resident-tax-withheld :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "控除合計" :mf/to :deduction-total :mf/kind :yen
    :mf/required? false :mf/verified? false}
   {:mf/column "差引支給額" :mf/to :net :mf/kind :yen
    :mf/required? true :mf/verified? false}])

(def by-column (into {} (map (juxt :mf/column identity)) columns))

(def required-columns
  (vec (for [c columns :when (:mf/required? c)] (:mf/column c))))

(def no-counterpart-columns
  (vec (for [c columns :when (= :mf/no-counterpart (:mf/to c))] (:mf/column c))))

(def verified-columns
  "Columns whose name has been checked against a real export. Empty, and a
  test asserts it stays empty until a real file exists."
  (vec (for [c columns :when (:mf/verified? c)] (:mf/column c))))

;; ---------------------------------------------------------------------------
;; Employee → contract, which is REGISTERED and never guessed
;; ---------------------------------------------------------------------------

(def employee-map-key
  "Where the operator registers `MoneyForward employee number → this actor's
  contract id`.

  It is a registration and not a match, for the reason
  `:bank/payee-name-kana` is: matching on a name would join two records for
  two different people who share a surname, and the consequence is one
  person's wages reconciled against another's. There is no fuzzy match here
  and there is not going to be one."
  :mf/employee-number)

(defn contract-for
  "The registered contract whose `:mf/employee-number` is `n`, or nil.

  Exact equality on a registered key. A row whose employee number is not
  registered is `:unmapped`, which is a refusal an operator resolves by
  registering it — not a row this importer attaches to whichever contract
  looks closest."
  [contracts n]
  (when (and (some? n) (not (str/blank? (str n))))
    (first (filter #(= (str n) (str (get % employee-map-key))) contracts))))

;; ---------------------------------------------------------------------------
;; Yen
;; ---------------------------------------------------------------------------

(defn parse-yen
  "`\"280,000\"` → 280000, `\"0\"` → 0, anything else → nil.

  Strips ASCII commas and surrounding whitespace and nothing else. In
  particular it does NOT accept a full-width digit, a currency mark or a
  parenthesised negative: each of those is a shape a real export might use,
  and accepting one this repository has not seen would be inventing a
  transformation on somebody's pay. An unparseable cell rejects its row with
  the raw text quoted, so the next version of this function is written
  against evidence.

  Returns nil for a blank, which callers distinguish from 0 — a blank
  健康保険料 cell is not a zero contribution."
  [s]
  (let [t (some-> s str str/trim (str/replace "," ""))]
    (cond
      (or (nil? t) (str/blank? t)) nil
      (re-matches #"\d+" t) #?(:clj (parse-long t)
                               :cljs (js/parseInt t 10))
      :else :mf/unparseable)))
