(ns payroll.provenance
  "Where a figure on a payslip came from, as a value.

  Every namespace in this repository already refuses to invent an amount. What
  did not exist until now is a way to **carry that refusal into an artifact**.
  A payslip, a 賃金台帳 row and an operator screen are all places where a
  number and the absence of a number have to be told apart by a person, and
  the mechanism this repository uses everywhere else — a keyword answer next
  to the value — stops working the moment somebody renders it, because
  `nil` renders as the empty string and the empty string renders as zero in
  the reader's head.

  So a figure is not a number here. It is

      {:figure/amount      n or nil
       :figure/provenance  one of `provenances`
       :figure/label       what this line is called on a payslip
       :figure/why         why it is what it is, in Japanese
       :figure/source      the article, the registered key, or the export it
                           came from — never a guess}

  ## Six provenances, and the two that may never carry a number

  | provenance | means | may carry an amount |
  |---|---|---|
  | `:derived` | THIS repository computed it from a registered fact and a rule it READ | yes |
  | `:declared` | the operator or the proposal supplied it; nothing here certifies it | yes |
  | `:imported` | it came from another system's export that this repository has NOT verified | yes, marked |
  | `:not-applicable` | a registered fact says this line does not arise (e.g. not a 被保険者) | no — the line is absent, not zero |
  | `:unknown` | nobody supplied it and nothing computed it | **no** |
  | `:held` | the run is held on this figure; it exists as a question | **no** |

  `figure` REFUSES to construct the last three carrying a number. That is the
  whole point of the namespace and it is enforced rather than documented: a
  `:unknown` figure that also carried `1234` would render as 1234 in every
  view that reads `:figure/amount` first, and the provenance would be a field
  nobody looked at.

  ## Why `:not-applicable` is not zero

  `payroll.shakai-hoken` answers `:not-covered` for a scheme this worker is
  not a 被保険者 of. On a payslip that is not a 0-yen 健康保険料 line; it is
  the absence of a 健康保険料 line. Printing 0 asserts that a lawful
  deduction was computed and came to nothing, which is a different claim and
  a wrong one. `payroll.shiwake` already makes the same distinction on the
  journal side ('預り金 0 asserts a liability of nothing'); this is that rule
  moved to where a person reads it.

  ## Why `:declared` is not `:derived`

  The single most dangerous number in this repository is the withheld income
  tax. `kotoba.taxlaw` read 所得税法 第百八十三条第一項 and did NOT read
  別表第二 / 別表第五, so every result it returns carries
  `:taxlaw/amount-checked? false`: a run accounting for 1 yen on 280,000 of
  wages satisfies the gate. A payslip that printed that 1 yen indistinguishably
  from a 厚生年金 figure derived from a rate in the Act would be laundering an
  unchecked figure through the presentation layer. `:declared` is what stops
  that, and `unverified?` is what a view keys the marker off."
  (:require [clojure.string :as str]))

(def provenances
  "In the order a reader should weigh them: what this repository worked out,
  then what it was told, then what it read out of somebody else's file, then
  the three that are not values at all."
  [:derived :declared :imported :not-applicable :unknown :held])

(def numberless
  "Provenances that may never carry an amount. `figure` refuses rather than
  drops, because a caller that handed a number to `:unknown` has a bug and a
  silently dropped number would hide it."
  #{:not-applicable :unknown :held})

(def unverified
  "Provenances a view must mark as not certified by this repository.

  `:derived` is absent because it is the only one this repository computed
  from a rule it read. Everything else — including a figure an operator typed
  with total confidence — is somebody's claim."
  #{:declared :imported :unknown :held})

(defn provenance?
  "Is `x` one of the six? Used by the artifact writers, which refuse to emit a
  figure whose provenance they do not recognise rather than print it as
  though it were fine."
  [x]
  (boolean (some #{x} provenances)))

(defn figure
  "Build a figure, or throw.

  Throwing rather than returning a refusal value is deliberate and is the one
  place in this repository that does it. Everywhere else a refusal is an
  answer somebody has to read; here the caller has written code that cannot be
  correct — a `:unknown` figure carrying 1234 is not a state of the world, it
  is a mistake at the call site — and a refusal value would be rendered, in the
  slot where the number goes, by the very view this namespace exists to
  protect."
  [{:keys [amount provenance label why source] :as m}]
  (when-not (provenance? provenance)
    (throw (ex-info "unknown provenance" {:provenance provenance :figure m})))
  (when (and (some? amount) (contains? numberless provenance))
    (throw (ex-info (str "a " provenance " figure may not carry an amount")
                    {:provenance provenance :amount amount :figure m})))
  (when (and (some? amount) (not (number? amount)))
    (throw (ex-info "a figure's amount must be a number or nil"
                    {:amount amount :figure m})))
  (cond-> {:figure/amount amount
           :figure/provenance provenance
           :figure/label label}
    (some? why) (assoc :figure/why why)
    (some? source) (assoc :figure/source source)))

(defn derived
  "A figure THIS repository computed from a registered fact and a rule it read.
  `source` names the rule — an article, never a description of one."
  [label amount source & [why]]
  (figure {:amount amount :provenance :derived :label label
           :source source :why why}))

(defn declared
  "A figure the operator or the proposal supplied. `source` names where it was
  supplied — the request key, the contract key — so a reader can go and look."
  [label amount source & [why]]
  (figure {:amount amount :provenance :declared :label label
           :source source :why why}))

(defn imported
  "A figure read out of another system's export that this repository has not
  verified. `source` names the file and column."
  [label amount source & [why]]
  (figure {:amount amount :provenance :imported :label label
           :source source :why why}))

(defn not-applicable
  "This line does not arise, and a registered fact is why. Never zero."
  [label why & [source]]
  (figure {:amount nil :provenance :not-applicable :label label
           :why why :source source}))

(defn unknown
  "Nobody supplied it and nothing computed it. `why` is required, because
  `不明` on its own is not something an operator can act on — the reason has
  to name the key that is missing or the table that was not read."
  [label why & [source]]
  (figure {:amount nil :provenance :unknown :label label
           :why why :source source}))

(defn held
  "The run is held on this figure. `why` carries the governor's own sentence,
  so the screen and the 409 body say the same thing."
  [label why & [source]]
  (figure {:amount nil :provenance :held :label label
           :why why :source source}))

;; ---------------------------------------------------------------------------
;; Reading a figure
;; ---------------------------------------------------------------------------

(defn amount
  "The number, or nil. Callers that sum figures must use `total`, not this —
  see there."
  [f]
  (:figure/amount f))

(defn unverified?
  "Must a view mark this figure as not certified by this repository?"
  [f]
  (contains? unverified (:figure/provenance f)))

(defn numeric?
  "Does this figure carry a number at all?"
  [f]
  (some? (:figure/amount f)))

(defn total
  "Sum a collection of figures, or refuse.

  Returns `{:total/amount n :total/complete? true}` only when every figure
  either carries a number or is `:not-applicable` (a line that does not
  arise contributes nothing and is not a gap). One `:unknown` or `:held`
  figure and the answer is

      {:total/amount nil :total/complete? false :total/blocked-by [figure ...]}

  because a total over figures where some are unknown is not the total, and
  a caller handed a number cannot tell. This is `shakai-hoken/withheld-total`'s
  rule, generalised: an artifact built from a partial total balances, having
  lost the difference.

  The provenance of the total is the WEAKEST of its parts — a total of one
  derived and one declared figure is declared. A total that inherited
  `:derived` from its strongest part would let a certified-looking 控除合計
  be produced from an uncertified 所得税."
  [figures]
  (let [blocked (filterv #(and (not (numeric? %))
                               (not= :not-applicable (:figure/provenance %)))
                         figures)]
    (if (seq blocked)
      {:total/amount nil :total/complete? false :total/blocked-by blocked}
      (let [contributing (filterv numeric? figures)
            provs (set (map :figure/provenance contributing))]
        {:total/amount (reduce + 0 (map :figure/amount contributing))
         :total/complete? true
         :total/provenance (cond
                             (contains? provs :imported) :imported
                             (contains? provs :declared) :declared
                             (seq provs) :derived
                             ;; every part was :not-applicable. The total is a
                             ;; real zero — nothing arose — and saying it is
                             ;; `:derived` from a rule would be wrong, so it
                             ;; keeps the provenance of its parts.
                             :else :not-applicable)}))))

(defn total-figure
  "`total` as a figure, so a 控除合計 row is the same kind of value as the rows
  above it. An incomplete total becomes `:unknown` with the blocked lines
  named, which is what stops a summary printing a smaller number than the
  payslip it summarises.

  A total over nothing but `:not-applicable` lines — or over no lines at all —
  is itself `:not-applicable` and carries NO amount. It would otherwise be a
  `0`, and a 控除合計 of 0 asserts that deductions were computed and came to
  nothing, which is a different claim from none having arisen. `payroll.shiwake`
  makes the same distinction about a 預り金 line; this is it at the point where
  a person reads the total.

  Measured 2026-08-25: before this branch existed, `figure` threw — an
  `:not-applicable` provenance carrying the `0` that `total` returns. The
  constructor's refusal caught what would otherwise have been a printed zero."
  [label figures & [source]]
  (let [{:total/keys [amount complete? provenance blocked-by]} (total figures)]
    (cond
      (and complete? (= :not-applicable provenance))
      (not-applicable label
                      (if (empty? figures)
                        "合計する内訳が一件も無い。零ではなく、集計対象が無い"
                        (str "内訳 " (count figures)
                             " 件がすべて該当なし。合計は零ではなく、発生していない"))
                      source)

      complete?
      (figure {:amount amount :provenance provenance :label label :source source})

      :else
      (unknown label
               (str "合計できない: "
                    (str/join "、"
                              (for [f blocked-by]
                                (str (:figure/label f) "が"
                                     (case (:figure/provenance f)
                                       :held "保留"
                                       "未確定"))))
                    "。未確定の項目を含む合計は合計ではない")
               source))))
