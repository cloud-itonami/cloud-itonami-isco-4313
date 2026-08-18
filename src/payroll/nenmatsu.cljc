(ns payroll.nenmatsu
  "年末調整 — is a year-end adjustment owed for one employee and one year,
  and what can this actor compute about it?

  所得税法 第百九十条 is read from source and catalogued in
  `kotoba-lang/taxlaw`, which exposes `requires-year-end-adjustment?`,
  `year-end-adjustment` and `adjusted?`. Until this namespace existed **no
  actor in this workspace called any of them** — the law was catalogued and
  nothing acted on it, which is a capability that exists only as a citation.
  `payroll.governor` used to record that fact in every draft verdict
  (`[:tax :year-end-adjustment :taxlaw/coverage] :not-evaluated`); that record
  is still correct for a payroll-run draft and is deliberately untouched. What
  changed is that there is now an op that DOES evaluate it.

  Pure, like `payroll.handoff` and `payroll.shiwake`: data in, data out, no
  store, no clock, no call. The governor does the store reads and hands the
  results here.

  ## The answer is nine-valued, and four of the nine are refusals

  `:nenmatsu/answer` is one of:

  | answer | means | `:nenmatsu/answerable?` |
  |---|---|---|
  | `:owed`                      | all three conditions hold and nothing says the over/under was applied | true |
  | `:settled`                   | all three hold and the request says it was applied | true |
  | `:year-not-finished`         | this is declared NOT the year's final payment of 給与等. **Ask again after it is** — this is not a finding about the employee | true |
  | `:declaration-not-filed`     | 給与所得者の扶養控除等申告書 was declared not filed via this payer, so 第百九十条 does not reach this employee | true |
  | `:above-ceiling`             | the wages this actor recorded for the year already exceed 二千万円 | true |
  | `:jurisdiction-not-declared` | the employer record names no jurisdiction. **No year-end law was consulted** | false |
  | `:not-catalogued`            | the declared jurisdiction has no 年末調整 facet in `kotoba.taxlaw`. Not read is not absent | false |
  | `:declaration-not-observed`  | nobody registered whether the 申告書 was filed. Software cannot see a piece of paper | false |
  | `:final-payment-not-declared`| nobody said whether the year's final payment has been made | false |

  The four `answerable? false` answers are the ones `payroll.governor` turns
  into HARD violations. They are refusals, never passes — the discipline
  `:yuryo-chobo-declared?` keeps in the sibling bookkeeping actor, and the
  same one rule 5 keeps for an unchecked withholding jurisdiction.

  ## Why this de-aliases `kotoba.taxlaw`'s `:out-of-scope`

  taxlaw returns ONE `:out-of-scope` for three different facts: not the
  year's final payment, no declaration filed, and over the ceiling. Two of
  those are permanent for the year and one resolves itself in December. An
  operator told `out of scope` cannot tell `this employee never qualifies`
  from `come back after the last payslip`, and only the second is an
  instruction. So the three are separated here, and taxlaw's own verdict is
  carried verbatim in `:nenmatsu/taxlaw` so the split is auditable.

  ## The order of the questions is deliberate

  Terminal answers are decided before transient ones. `:declaration-not-filed`
  and `:above-ceiling` will not change before the year ends; `:year-not-finished`
  will. Reporting the transient one first would let a permanent gap hide
  behind a `come back later`.

  This makes one answer diverge from taxlaw's, on purpose: with no declaration
  filed AND no statement about the final payment, taxlaw answers
  `:not-declared` (its first question is the final payment) and this answers
  `:declaration-not-filed`. Both are true of their own question. The
  divergence is visible because `:nenmatsu/taxlaw` carries taxlaw's answer
  next to this one.

  ## What is NOT computed, and why that is the point

  第百九十条 applies the excess against, and collects the shortfall with, the
  year's final payment. **The over/under is not computable from anything this
  repo has read.** The year's correct tax comes from 別表 (税額表), which
  taxlaw explicitly records as unread under
  `:rule/amount-source-not-read`; `withholding-obligation` already carries
  `:taxlaw/amount-checked? false` for the same reason and the governor's
  rule 6 already gates presence rather than amount.

  So `:nenmatsu/amount` reports `:not-computable` for both the year's tax and
  the over/under, names the table nobody read (read OFF taxlaw, never typed
  here), and reports only figures this actor actually holds: the wages and
  the withheld income tax **it itself committed** for that year. A figure
  invented here would be the most dangerous value in the repository — it
  would arrive stamped with an article of the Income Tax Act.

  ## Every recorded figure states the limit of its own record

  `:wages-recorded` and `:withheld-recorded` sum the payroll runs THIS ACTOR
  committed. That is not the same as 「その年中に支払うべきことが確定した
  給与等の金額」: wages paid before this actor was deployed, or through
  another system, are not here and cannot be seen from here. So the ceiling
  test runs in one direction only —

    recorded > 二千万円   ⇒ definitely above (unseen wages only add)
    recorded ≤ 二千万円   ⇒ **does not establish** the employee is inside

  — and `:establishes-inside?` is false even when `:inside?` is true.
  二千万円**以下** is inclusive: at exactly 20,000,000 the employee is inside.
  The ceiling itself is read from taxlaw's `:rule/income-ceiling-yen`, not
  typed in this repository."
  (:require [clojure.string :as str]
            [kotoba.taxlaw :as taxlaw]))

(def answers
  "Answers that ARE an answer. `payroll.governor` lets these commit."
  #{:owed :settled :year-not-finished :declaration-not-filed :above-ceiling})

(def refusals
  "Answers that are the absence of one. `payroll.governor` HOLDS on these.

  Kept as a set rather than as `(complement answers)` so that an answer this
  namespace forgot to classify belongs to NEITHER — `answerable?` is then
  false and the governor holds. A new answer defaults to refused."
  #{:jurisdiction-not-declared :not-catalogued
    :declaration-not-observed :final-payment-not-declared})

(def facet
  "The `kotoba.taxlaw` facet this namespace reads. Named once so the governor
  can report WHICH facet was missing without spelling it again."
  :jurisdiction/year-end-adjustment)

(defn named?
  "Is `x` a non-blank name? Used for the year and the contract id, both of
  which an assessment must have: an assessment nobody can look up afterwards
  is not a record of anything."
  [x]
  (and (some? x) (not (str/blank? (str x)))))

(defn- declared
  "A boolean, or nil for anything else.

  `\"true\"`, `:yes` and `1` are not declarations. Without this, a request
  carrying the STRING `\"true\"` for `:final-payment-of-year?` would satisfy
  neither `nil?` nor `false?` and would fall through to `:owed` — a pass
  bought with a typo. Normalising here rather than at the edge means the
  guarantee holds for every caller of this function, not only the HTTP one."
  [x]
  (when (boolean? x) x))

(defn year-runs
  "The payroll runs this actor committed for `contract-id` in `year`, oldest
  first.

  Three filters and each one matters:

  - `:op` must be `:draft-payroll-run`. **An assessment record is not a
    payroll run** — this op commits records too, and counting one as wages
    would inflate the very figure the ceiling is tested against.
  - the record's contract must be the one being assessed.
  - the period must start with the year.

  Periods are opaque operator-chosen strings and this actor parses no dates,
  so the only relation asserted is prefix. An operator who writes periods as
  `\"07/2026\"` gets zero matches — which is why the caller is told
  `:no-runs-recorded?` rather than being handed a zero that reads as a fact
  about wages."
  [records contract-id year]
  (filterv (fn [r]
             (and (= :draft-payroll-run (:op r))
                  (= contract-id (:contract-id r))
                  (named? year)
                  (str/starts-with? (str (get-in r [:payload :period])) (str year))))
           records))

(defn- totals
  "What this actor's own records add up to.

  `:runs-missing-a-withheld-amount` is carried because a sum over runs where
  some recorded no withholding is not the year's withholding, and a reader
  given only the sum cannot tell. The governor's rule 6 makes such a run a
  HOLD, so the count should be 0 for any run that committed — a non-zero here
  is a record from before that rule, or from a jurisdiction it does not
  reach, and either way the reader should see it."
  [runs]
  (reduce (fn [acc r]
            (let [g (get-in r [:payload :gross])
                  w (get-in r [:payload :income-tax-withheld])]
              (-> acc
                  (update :wages-recorded + (if (number? g) g 0))
                  (update :withheld-recorded + (if (number? w) w 0))
                  (update :runs-missing-a-withheld-amount + (if (number? w) 0 1)))))
          {:wages-recorded 0 :withheld-recorded 0
           :runs-missing-a-withheld-amount 0}
          runs))

(def ^:private why
  {:jurisdiction-not-declared
   (str "employer 記録に :jurisdiction が無い。どこで支払われる給与かが"
        "宣言されていないので、年末調整の法令は一切参照していない"
        "（適用なしの判断ではない）")
   :declaration-not-observed
   (str "給与所得者の扶養控除等申告書を提出したかどうかが契約に登録されていない。"
        "これは software が観測できない事実であり、"
        "未登録は「提出済み」でも「未提出」でもない（未観測は合格ではない）")
   :final-payment-not-declared
   (str "その年最後に給与等の支払をしたかどうかが要求に宣言されていない。"
        "第百九十条 自身の trigger なので、未宣言のままでは判断できない"
        "（この actor は暦を持たず、次の支払が来るかどうかを観測できない）")
   :declaration-not-filed
   (str "給与所得者の扶養控除等申告書を提出していないと宣言されている。"
        "第百九十条 はこの被用者に及ばない。"
        "確定申告その他の経路については何も述べていない")
   :above-ceiling
   (str "この actor が記録した その年の給与等が 二千万円 を超える。"
        "第百九十条 の対象外")
   :year-not-finished
   (str "その年最後の給与等の支払ではないと宣言されている。"
        "被用者が対象外なのではなく、まだ時期ではない"
        "（最後の支払の後に改めて評価する）")
   :owed
   (str "第百九十条 の三要件が揃っている。その年最後の給与等の支払の際に"
        "過不足を精算する義務があり、精算した記録は無い")
   :settled
   (str "第百九十条 の三要件が揃っており、要求は精算済みと宣言している。"
        "この actor は精算額を検証していない（できない）")})

(defn- amount-report
  "What can and cannot be computed about the over/under.

  Both computable-looking fields are `:not-computable` and the table that
  would make them computable is named — read OFF taxlaw's withholding facet
  (`:rule/amount-source-not-read`) rather than typed here, so a repository
  that never read the article cannot claim to know which table it did not
  read."
  [jurisdiction {:keys [wages-recorded withheld-recorded
                        runs-missing-a-withheld-amount]} run-count]
  {:nenmatsu/annual-tax :not-computable
   :nenmatsu/over-or-under :not-computable
   :nenmatsu/amount-source-not-read
   (:rule/amount-source-not-read
    (taxlaw/facet-of jurisdiction :jurisdiction/wage-withholding))
   :nenmatsu/why-not-computable
   (str "第百九十条 は過不足を その年最後の給与等の支払 に充当・徴収せよと"
        "定めるが、その年の正しい税額は 別表（税額表）から来る。"
        "kotoba.taxlaw はそれを読んでいないので、この actor は金額を計算しない。"
        "以下は計算値ではなく、この actor 自身が commit した記録の合計である")
   :nenmatsu/wages-recorded wages-recorded
   :nenmatsu/withheld-recorded withheld-recorded
   :nenmatsu/runs-recorded run-count
   :nenmatsu/runs-missing-a-withheld-amount runs-missing-a-withheld-amount
   :nenmatsu/records-are-this-actors-only? true})

(defn- ceiling-report [ceiling wages run-count]
  (let [seen? (pos? run-count)]
    {:nenmatsu/ceiling-yen ceiling
     :nenmatsu/basis :wages-this-actor-recorded
     :nenmatsu/recorded (when seen? wages)
     ;; 二千万円以下 — inclusive. At exactly the ceiling the employee is IN.
     :nenmatsu/inside? (when (and (number? ceiling) seen?) (<= wages ceiling))
     ;; ...and being inside the recorded figure never establishes being
     ;; inside the real one, because unseen wages only add.
     :nenmatsu/establishes-inside? false
     :nenmatsu/why (str "二千万円以下 は 二千万円 を含む。この actor が持つのは"
                        "自分が commit した run だけなので、超えているという判定は"
                        "確実だが、超えていないという判定は確実ではない")}))

(defn assess
  "Assess one employee-year. Pure.

    {:jurisdiction  the EMPLOYER's, never the proposal's — an advisor that
                    could pick one could pick the one whose rules it satisfies
     :contract      the REGISTERED contract record, or nil
     :year          the year being assessed
     :request       the operation request (`:contract-id`,
                    `:final-payment-of-year?`, `:year-end-adjustment-settled?`)
     :records       this employer's committed records}

  The 申告書 comes off the CONTRACT (`:employment/year-end-declaration-filed?`)
  because it is a piece of paper an operator registers, exactly as
  `:employment/recipient-residency` is. The two facts about the moment — was
  this the year's final payment, and was the over/under applied — come off the
  REQUEST, which is where the verified employer speaks. Neither comes off the
  proposal, so nothing a model writes can move this answer; the governor
  passes the request through and a test pins that an advisor emitting all
  three keys changes nothing."
  [{:keys [jurisdiction contract year request records]}]
  (let [contract-id (:contract-id request)
        declaration (declared (:employment/year-end-declaration-filed? contract))
        final? (declared (:final-payment-of-year? request))
        settled? (declared (:year-end-adjustment-settled? request))
        runs (year-runs records contract-id year)
        run-count (count runs)
        t (totals runs)
        wages (:wages-recorded t)
        ceiling (:rule/income-ceiling-yen (taxlaw/facet-of jurisdiction facet))
        ;; taxlaw is asked ONCE, with everything this actor actually knows.
        ;; The annual amount is passed only when there is at least one
        ;; recorded run: passing 0 would assert that the year's confirmed
        ;; 給与等 was zero, which is a claim, not an absence.
        law (taxlaw/year-end-adjustment
             jurisdiction
             {:final-payment-of-year? final?
              :declaration-filed? declaration
              :annual-employment-income (when (pos? run-count) wages)
              :year-end-adjustment-settled? settled?})
        answer (cond
                 (nil? jurisdiction) :jurisdiction-not-declared
                 (= :none (:taxlaw/coverage law)) :not-catalogued
                 (nil? declaration) :declaration-not-observed
                 (false? declaration) :declaration-not-filed
                 (and (number? ceiling) (pos? run-count) (> wages ceiling)) :above-ceiling
                 (nil? final?) :final-payment-not-declared
                 (false? final?) :year-not-finished
                 (true? settled?) :settled
                 :else :owed)]
    (cond-> {:nenmatsu/answer answer
             :nenmatsu/answerable? (contains? answers answer)
             :nenmatsu/jurisdiction jurisdiction
             :nenmatsu/provision (:rule/provision (taxlaw/facet-of jurisdiction facet))
             :nenmatsu/why (if (= :not-catalogued answer)
                             (or (:taxlaw/why law)
                                 (str "宣言された法域 " (pr-str jurisdiction)
                                      " の年末調整は kotoba.taxlaw に無い"
                                      "（未検査は合格ではない）"))
                             (get why answer))
             ;; taxlaw's own verdict, verbatim, so the de-aliasing above is
             ;; auditable rather than merely asserted.
             :nenmatsu/taxlaw law
             :nenmatsu/evidence
             {:contract-id contract-id
              :year year
              :declaration-filed? declaration
              :final-payment-of-year? final?
              :settled-claimed? settled?
              :runs-recorded run-count
              :no-runs-recorded? (zero? run-count)
              :ceiling (ceiling-report ceiling wages run-count)}}

      ;; the over/under question only arises where the article reaches.
      (contains? #{:owed :settled} answer)
      (assoc :nenmatsu/amount (amount-report jurisdiction t run-count))

      ;; and `settled` is a claim this actor recorded, not one it verified.
      (= :settled answer)
      (assoc :nenmatsu/settled-claim
             {:source :request-declaration
              :verified? false
              :why (str "精算されたという宣言は要求から来ており、"
                        "この actor は充当・徴収された金額を検証していない。"
                        "その年の正しい税額を読んでいないので検証できない")}))))
