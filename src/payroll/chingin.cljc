(ns payroll.chingin
  "賃金 — what this repository's `gross` figure is, and what it demonstrably
  is not.

  The governor's rule 4 recomputes `:gross` with `kotoba.labor/wages-for` and
  holds any proposal that disagrees. That rule is exact and it is about
  AGREEMENT, not about correctness: it proves the advisor did not invent a
  number. It says nothing about whether the number it agreed with is the wage.

  `kotoba.labor/wages-for` is four lines and this is the whole of it:

      hourly   :contract/rate × Σ :ts/hours over the worker's timesheets
      monthly  :contract/rate                       ← timesheets are IGNORED

  Both are arithmetic this repository can check. Neither is 賃金 as a
  Japanese payslip means it. What is missing is not an oversight in `labor`;
  it is a library for household employment being asked to be a payroll engine.

  ## The monthly case is the one that matters here, and it is the quiet one

  For a monthly contract `wages-for` returns the contracted rate and does not
  read the timesheets at all. For a company with one monthly salaried
  employee — the case this repository is being pointed at — that is right
  almost every month and silently wrong in the months that are not almost
  every month: a mid-month start, a mid-month leaver, unpaid leave, 欠勤.
  The figure does not become uncertain in those months. **It stays confident
  and becomes wrong**, and nothing downstream can tell, because the governor's
  recomputation agrees with itself.

  ## What this namespace does about it

  It refuses to certify the figure whenever a registered fact exists that the
  formula provably does not read. The vocabulary of such facts is `premiums`
  below; each entry names the key an operator registers, what it is called on
  a payslip, and the provision this repository has **NOT read** and would need
  to price it.

  Naming an unread article is not enforcing it. This namespace asserts nothing
  about what 労働基準法 第三十七条 requires — it says that the article exists,
  that pricing these hours is what it is about, and that nobody here has read
  it, so a figure that ignores the hours is not being offered as the wage.
  That is the same shape `payroll.shakai-hoken` uses for 協会けんぽ's rate
  (`:rate/not-read`), and the opposite of guessing a 1.25 multiplier.

  ## The `ignores` list is derived, not typed

  `formula` declares the keys each wage type READS. Everything in `premiums`
  that is not in that list is ignored, by subtraction. A future `labor` that
  started reading `:ts/overtime-hours` would be picked up by adding the key to
  `formula`, in one place, rather than by remembering to delete a line from a
  hand-written list of things that are ignored — which is the list that goes
  stale silently.

  `payroll.chingin-test/wages-for-really-does-ignore-what-this-namespace-says-it-ignores`
  feeds `kotoba.labor/wages-for` two contracts differing only in each ignored
  key and asserts the output does not move. The claim is measured against the
  dependency rather than asserted about it."
  (:require [clojure.string :as str]
            [kotoba.labor :as labor]))

(def formula
  "What `kotoba.labor/wages-for` reads, by wage type. Read off the function,
  which is four lines long, and pinned by a test that feeds it perturbed
  inputs rather than by this comment."
  {:hourly {:formula/expression ":contract/rate × Σ :ts/hours"
            :formula/reads #{:contract/rate :ts/hours}
            :formula/label "時給 × 集計時間数"}
   :monthly {:formula/expression ":contract/rate"
             :formula/reads #{:contract/rate}
             :formula/label "契約月額そのもの（勤怠は読まれない）"}})

(def premiums
  "Facts an operator may register that change what somebody is owed, together
  with the provision this repository would have to read to price them.

  Every `:premium/provision-not-read` is exactly that: the article is NAMED as
  unread. Nothing here enforces a rule out of any of them, and no multiplier
  appears anywhere in this namespace.

  `:premium/on` says where the key lives, because the two are registered by
  different acts: a timesheet key arrives with the month's attendance, a
  contract key when the employment terms are agreed."
  [{:premium/key :ts/overtime-hours
    :premium/on :timesheet
    :premium/label "時間外労働"
    :premium/provision-not-read "労働基準法 第三十七条第一項（時間外労働の割増賃金）"
    :premium/why (str "法定時間外の労働時間が登録されているが、"
                      "kotoba.labor/wages-for は :ts/hours しか読まない。"
                      "割増率はこの repository が読んでいない条文にあるので、"
                      "この gross は時間外分を含んでいないし、含める術も無い")}
   {:premium/key :ts/night-hours
    :premium/on :timesheet
    :premium/label "深夜労働"
    :premium/provision-not-read "労働基準法 第三十七条第四項（深夜業の割増賃金）"
    :premium/why (str "深夜の労働時間が登録されているが、割増率は未読である。"
                      "深夜割増は時間外割増とは別に加算されるものなので、"
                      "時間外を無視したのと同じ誤りが二重になる")}
   {:premium/key :ts/holiday-hours
    :premium/on :timesheet
    :premium/label "休日労働"
    :premium/provision-not-read "労働基準法 第三十七条第一項（休日労働の割増賃金）"
    :premium/why "法定休日の労働時間が登録されているが、割増率は未読である"}
   {:premium/key :ts/absence-hours
    :premium/on :timesheet
    :premium/label "欠勤・不就労"
    :premium/provision-not-read
    (str "欠勤控除の算定方法（就業規則・労働契約の定めによる。"
         "労働基準法にも施行規則にも計算式は無い）")
    :premium/why (str "不就労時間が登録されているが、月給契約の gross は"
                      "契約月額そのものなので控除されていない。"
                      "控除の計算式は法令ではなく就業規則にあり、"
                      "この repository はその就業規則を持っていない")}
   {:premium/key :contract/allowances
    :premium/on :contract
    :premium/label "諸手当"
    :premium/provision-not-read
    (str "労働基準法 第三十七条第五項（割増賃金の基礎から除外される賃金）"
         "および同法施行規則 第二十一条")
    :premium/why (str "手当が登録されているが、:contract/rate には含まれていない。"
                      "手当は支給額に加わるだけでなく、割増賃金の算定基礎に"
                      "入るものと入らないものが条文で分かれる。どちらの規則も未読である")}
   {:premium/key :contract/commuting-allowance
    :premium/on :contract
    :premium/label "通勤手当"
    :premium/provision-not-read
    (str "所得税法 第九条第一項第五号（非課税となる通勤手当）"
         "および所得税法施行令 第二十条の二の限度額")
    :premium/why (str "通勤手当が登録されているが、:contract/rate には含まれていない。"
                      "通勤手当は非課税限度額までは所得税の課税対象から外れ、"
                      "一方で社会保険の報酬には含まれる。どちらの扱いも未読である")}])

(def premium-keys (into #{} (map :premium/key) premiums))

(defn- registered-value
  "The value an operator registered for a premium fact, from wherever it
  lives. Timesheet facts are summed across the worker's entries — one
  overtime hour on one day is overtime for the month."
  [{:premium/keys [key on]} contract timesheets]
  (case on
    :contract (get contract key)
    :timesheet (let [vs (keep #(get % key) timesheets)]
                 (when (seq vs)
                   (if (every? number? vs) (reduce + 0 vs) (vec vs))))))

(defn- engages?
  "Does this registered value put the run inside the premium?

  Three-valued on purpose. A number that is zero does NOT engage: an operator
  who registered `:ts/overtime-hours 0` has answered the question, and holding
  a run for an answered question would train them to stop answering it. A
  positive number engages. Anything else that is present — a string, a
  negative, a vector of mixed values — engages too, because a malformed
  premium fact is not the same as no premium fact and must not read as one."
  [v]
  (cond (nil? v) false
        (number? v) (pos? v)
        :else true))

(def answers
  "Answers that ARE an answer about the wage basis."
  #{:accounted-for})

(def refusals
  "Answers that are the absence of one. `payroll.governor` HOLDS on every one.

  A set rather than `(complement answers)`, for `payroll.shakai-hoken/refusals`'
  reason: an answer this namespace adds and forgets to classify belongs to
  neither, `certifiable?` is false, and the governor holds. A new answer
  defaults to refused."
  #{:premium-not-priced :unknown-wage-type})

(defn assess
  "What the gross figure for this run accounts for. Pure.

    {:contract    the REGISTERED contract record, or nil
     :timesheets  the worker's REGISTERED timesheet entries}

  Returns

    {:chingin/answer      :accounted-for | :premium-not-priced | :unknown-wage-type
     :chingin/certifiable? bool
     :chingin/wage-type   :hourly | :monthly | nil
     :chingin/formula     what `wages-for` computes and what it reads
     :chingin/reads-timesheets? whether the formula looks at them at all
     :chingin/unaccounted [{:premium/key :premium/label :premium/registered
                            :premium/provision-not-read :premium/why} …]
     :chingin/why         one sentence a person can act on}

  `:chingin/reads-timesheets?` is reported on EVERY answer, including the
  accounted-for one. A monthly run whose timesheets exist and are not read is
  not held — that is normal for a salaried employee, and 労働基準法 第百八条
  wants the attendance recorded whether or not it moves the pay — but an
  operator looking at a screen must be able to see that the hours in front of
  them did not produce the figure next to them. Omitting it would make
  `the hours were read and agreed` and `the hours were never read` print the
  same, which is this repository's recurring defect stated in one line."
  [{:keys [contract timesheets]}]
  (let [wage-type (:contract/wage-type contract)
        f (get formula wage-type)]
    (if-not f
      {:chingin/answer :unknown-wage-type
       :chingin/certifiable? false
       :chingin/wage-type wage-type
       :chingin/reads-timesheets? nil
       :chingin/unaccounted []
       :chingin/why (str "契約の :contract/wage-type が "
                         (pr-str wage-type)
                         " で、kotoba.labor/wages-for が計算できる型ではない。"
                         "計算できない基礎から出た gross は賃金ではない")}
      (let [reads (:formula/reads f)
            unaccounted
            (vec (for [p premiums
                       :when (not (contains? reads (:premium/key p)))
                       :let [v (registered-value p contract timesheets)]
                       :when (engages? v)]
                   (assoc (select-keys p [:premium/key :premium/label
                                          :premium/on :premium/provision-not-read
                                          :premium/why])
                          :premium/registered v)))]
        {:chingin/answer (if (seq unaccounted) :premium-not-priced :accounted-for)
         :chingin/certifiable? (empty? unaccounted)
         :chingin/wage-type wage-type
         :chingin/formula f
         :chingin/reads-timesheets? (contains? reads :ts/hours)
         :chingin/timesheet-count (count timesheets)
         :chingin/unaccounted unaccounted
         :chingin/why
         (if (seq unaccounted)
           (str "gross は " (:formula/label f)
                " であって、登録されている "
                (str/join "・" (map :premium/label unaccounted))
                " を含んでいない。含めるのに要る条文をこの repository は読んでいない")
           (str "gross は " (:formula/label f)
                " として再計算でき、この repository が知る限り"
                "計上漏れの登録事実は無い。"
                (if (contains? reads :ts/hours)
                  "勤怠は読まれている"
                  (str "ただし月給契約なので勤怠は一切読まれていない"
                       "（登録されている勤怠 " (count timesheets) " 件は"
                       "この金額に影響しない）"))))}))))

(defn gross-figure
  "This run's gross as a `payroll.provenance` figure.

  `:derived` only when the basis is certifiable. Otherwise the amount is
  withheld entirely and the figure is `:held` — not `:declared` with a note.
  A payslip that printed the figure with a footnote is a payslip somebody
  pays from; the footnote is read by the person who wrote it and by nobody
  else.

  `provenance-fns` is `payroll.provenance`'s constructors, passed in rather
  than required, so this namespace stays free of a dependency it would use
  for exactly one call — and so a caller that wants figures shaped its own
  way is not forced through this one."
  [assessment gross {:keys [derived held]}]
  (if (and (:chingin/certifiable? assessment) (number? gross))
    (derived "総支給額" gross
             (str "kotoba.labor/wages-for（"
                  (get-in assessment [:chingin/formula :formula/expression]) "）"))
    (held "総支給額" (:chingin/why assessment)
          (str "kotoba.labor/wages-for（"
               (or (get-in assessment [:chingin/formula :formula/expression])
                   "計算不能")
               "）"))))

(defn wages-for
  "`kotoba.labor/wages-for`, re-exported so a caller can recompute the figure
  without also taking a direct dependency on the shape of `labor`'s API.

  This is not a wrapper that adds anything and deliberately is not: the
  governor's rule 4 depends on this repository computing the SAME number the
  advisor did, and a wrapper that adjusted it would break that agreement
  while looking like an improvement."
  [contract timesheets]
  (labor/wages-for contract timesheets))
