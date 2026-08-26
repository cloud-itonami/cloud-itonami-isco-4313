(ns payroll.meisai
  "明細 — one payroll run as itemised lines, each a `payroll.provenance`
  figure.

  This is the value every operator-facing artifact is built from: the
  payslip, the 賃金台帳 row, the monthly deduction summary and the screen an
  operator reviews before approving. There is exactly one of it, deliberately.
  Four renderers each deciding for themselves what to do about an unobserved
  健康保険料 is four chances for one of them to print a zero.

  ## It reads the verdict, it does not re-derive it

  The governor has already asked `payroll.shakai-hoken` whether each scheme is
  answerable, already read 所得税法 第百八十三条第一項 through `kotoba.taxlaw`,
  and already recorded both in the verdict's `:extra`. This namespace turns
  those answers into figures and adds nothing legal of its own. Re-running the
  assessment here would create a second opinion that can disagree with the one
  the run was actually decided on — and the screen would then show a payslip
  the governor never approved.

  What it DOES add is `payroll.chingin`: whether the gross figure accounts for
  the registered facts. That question is asked here rather than in the
  governor's `:extra` because it is asked of the same inputs and produces the
  same answer either way, and because the governor gained a rule for it
  (`:wage-basis-unaccounted`) that reads this namespace's assessment.

  ## The net line is recomputed and can refuse

  `:net` on a proposal is `gross − :deductions`, where `:deductions` is a
  single number the request supplies. The four 社会保険 amounts and the
  income tax are SEPARATE fields. Nothing forces the operator to make the two
  agree, and `payroll.shiwake` catches the disagreement when it tries to build
  a journal entry — but by then the run is committed and somebody may already
  have been paid.

  So the net line here is `gross − Σ(deduction lines)`, and where that
  disagrees with the `:net` the run committed, the line is HELD with both
  figures named. A payslip is the document the employee reads; it is the last
  place a disagreement about what they were paid should be resolved silently
  in favour of either number."
  (:require [clojure.string :as str]
            [payroll.chingin :as chingin]
            [payroll.provenance :as prov]))

(def deduction-lines
  "The five deductions a Japanese payslip carries, in the order it carries
  them, each keyed to where its amount lives on a run.

  A vector rather than a map: this is read by people and a map would reorder
  it differently on different runtimes — `payroll.shakai-hoken/schemes` makes
  the same choice for the same reason.

  `:line/scheme` is nil for income tax because it is not a social-insurance
  scheme; its answerability comes from `kotoba.taxlaw` and is read off a
  different part of the verdict."
  [{:line/key :income-tax-withheld
    :line/label "所得税（源泉徴収）"
    :line/kind :income-tax
    :line/scheme nil
    :line/provision "所得税法 第百八十三条第一項"}
   {:line/key :health-insurance-withheld
    :line/label "健康保険料"
    :line/kind :social-insurance
    :line/scheme :scheme/health-insurance
    :line/provision "健康保険法 第百六十七条第一項"}
   {:line/key :care-insurance-withheld
    :line/label "介護保険料"
    :line/kind :social-insurance
    :line/scheme :scheme/long-term-care-insurance
    :line/provision "健康保険法 第百六十七条第一項"}
   {:line/key :employees-pension-withheld
    :line/label "厚生年金保険料"
    :line/kind :social-insurance
    :line/scheme :scheme/employees-pension
    :line/provision "厚生年金保険法 第八十四条第一項"}
   {:line/key :employment-insurance-withheld
    :line/label "雇用保険料"
    :line/kind :social-insurance
    :line/scheme :scheme/employment-insurance
    :line/provision "労働保険徴収法 第三十二条第一項"}
   ;; 住民税 is SIXTH and is not a social-insurance scheme and not a tax this
   ;; actor computes. It is here because until it was, the MoneyForward
   ;; boundary mapped its column to `:mf/no-counterpart` and BLOCKED every
   ;; reconciliation carrying a value — which made `docs/maturity.md`'s G1
   ;; unmeetable by construction (its own sixth condition said so).
   ;;
   ;; The figure comes from `payroll.juminzei`, which reads a municipality's
   ;; 決定通知書 and computes nothing.
   {:line/key :resident-tax-withheld
    :line/label "住民税（特別徴収）"
    :line/kind :resident-tax
    :line/scheme nil
    :line/provision "地方税法 第三百二十一条の五（東京都の手引きが引く条文）"}])

(defn- income-tax-figure
  "The withheld income tax, as a figure.

  Four cases, and the two that print a number print it as `:declared`:

    the article was read and the run accounts for an amount
        → `:declared`. **Never `:derived`.** taxlaw records
          `:taxlaw/amount-checked? false` because 別表第二 / 別表第五 were not
          read, so the amount satisfies a gate and is certified by nothing.
    the article was read and the run accounts for nothing
        → `:held`. The governor has already held the run for it.
    no jurisdiction was declared
        → `:unknown`. No withholding law was consulted; that is not a
          finding that nothing is owed.
    the payment fell outside the one article that was read
        → `:unknown` with the reason, because the provisions governing a
          non-resident recipient or a payment made abroad were never read
          either."
  [run withholding]
  (let [amt (:income-tax-withheld run)
        coverage (:taxlaw/coverage withholding)]
    (cond
      (and (= :checked coverage) (some? amt))
      (prov/declared "所得税（源泉徴収）" amt
                     "proposal :income-tax-withheld"
                     (str "所得税法 第百八十三条第一項 は読まれているが、"
                          "別表第二・別表第五（税額表）は読まれていない。"
                          "この額が正しいことをこの repository は確かめていない"))

      (= :checked coverage)
      (prov/held "所得税（源泉徴収）"
                 (str "源泉徴収義務のある支払だが、この run は源泉所得税を"
                      "一切計上していない。未計上は税額零ではなく未回答である")
                 "所得税法 第百八十三条第一項")

      (nil? coverage)
      (prov/unknown "所得税（源泉徴収）"
                    "この op は給与等の支払を主張していないので、源泉徴収の条文に到達していない"
                    nil)

      (= :not-declared coverage)
      (prov/unknown "所得税（源泉徴収）"
                    (str "employer 記録に :jurisdiction が無い。"
                         "源泉徴収の法令は一切参照していない（適用なしの判断ではない）")
                    ":jurisdiction")

      :else
      (prov/unknown "所得税（源泉徴収）"
                    (or (:taxlaw/why withholding)
                        (str "源泉徴収の可否をこの repository は答えられない"
                             "（coverage " (pr-str coverage) "）"))
                    (or (:taxlaw/read-provision withholding)
                        (:taxlaw/provision withholding))))))

(defn- scheme-figure
  "One social-insurance deduction, as a figure, from that scheme's own report.

  `:not-covered` is the one answer that becomes `:not-applicable`: a worker
  who is not a 被保険者 of a scheme has no deduction for it, and printing 0
  would assert a lawful deduction was computed and came to nothing.

  Every refusal becomes `:held` carrying the scheme's own sentence and, where
  one exists, the exact key an operator has to register. `見ていない` alone is
  not an instruction, which is why `payroll.shakai-hoken` carries `:missing`
  and why it is repeated into the figure rather than dropped at the boundary."
  [{:line/keys [label]} report]
  (let [answer (:scheme/answer report)]
    (cond
      (nil? report)
      (prov/unknown label
                    (str "この保険についての判断が verdict に無い。"
                         "社会保険の評価に到達していない run である")
                    nil)

      (= :not-covered answer)
      (prov/not-applicable label (:scheme/why report)
                           (str (:scheme/coverage-key report) " = false"))

      (= :accounted-for answer)
      (prov/declared label (:scheme/declared report)
                     (str "proposal " (:scheme/amount-key report))
                     (if (get-in report [:scheme/amount :amount/computable?])
                       (str "条文の率（"
                            (get-in report [:scheme/amount :amount/provision])
                            "）から出る厳密値との差が一円未満であることは検査済み。"
                            "円単位の額そのものはこの repository が計算したものではない"
                            "（折半額の端数処理規則が未読）")
                       (str "料率が条文に無く、告示をこの repository は読んでいないので、"
                            "この額を検算できない")))

      :else
      (prov/held label
                 (cond-> (str (:scheme/why report))
                   (:scheme/missing report)
                   (str "。登録が要る: " (pr-str (:scheme/missing report))))
                 (:scheme/provision report)))))

(defn- resident-tax-figure
  "住民税, from `payroll.juminzei/assess` — or `:unknown` when nobody asked it.

  The absent case is `:unknown` and NOT `:not-applicable`, which is the
  distinction this whole namespace is built on: a run for which no
  municipality notice was consulted is a run with an unanswered question
  about a lawful deduction, not a run in which no 住民税 arises. `payable?`
  therefore refuses it, which is the enforcement — an operator cannot build
  a bank file for a month whose 住民税 nobody looked at.

  There is deliberately NO governor rule for 住民税 in this change. The
  governor's rules are about what a run may COMMIT, and adding one would
  hold every run for every employer whose 住民税 is handled outside this
  system — which `docs/maturity.md`'s G5 says is a lawful arrangement. The
  refusal is at the payment boundary instead, where it costs nothing to be
  wrong about an employer who never wanted the line."
  [{:line/keys [label provision]} assessment]
  (cond
    (nil? assessment)
    (prov/unknown label
                  (str "この run について住民税の評価に到達していない。"
                       "特別徴収税額の決定通知書が登録されていれば、"
                       "その月割額がここに入る。"
                       "未評価は「住民税が無い」ではない")
                  provision)

    (= :notified (:juminzei/answer assessment))
    (prov/declared label (:juminzei/amount assessment)
                   (str (:juminzei/municipality assessment) " 特別徴収税額通知")
                   (:juminzei/why assessment))

    (= :no-obligation-registered (:juminzei/answer assessment))
    (prov/not-applicable label (:juminzei/why assessment) provision)

    :else
    (prov/held label (:juminzei/why assessment) provision)))

(defn deductions
  "The six deduction lines as `[{:line/… :line/figure f} …]`, in payslip
  order.

  `social-insurance` is the verdict's `:social-insurance` assessment,
  `withholding` its `[:tax :withholding]`, and `juminzei` a
  `payroll.juminzei/assess` result. Passing them in rather than recomputing
  is the point — see the namespace docstring."
  [run {:keys [social-insurance withholding juminzei]}]
  (vec (for [line deduction-lines]
         (assoc line :line/figure
                (case (:line/kind line)
                  :social-insurance
                  (scheme-figure line (get-in social-insurance
                                              [:shakai-hoken/schemes
                                               (:line/scheme line)]))
                  :resident-tax (resident-tax-figure line juminzei)
                  (income-tax-figure run withholding))))))

(defn- net-figure
  "`gross − Σ deductions`, or a refusal.

  Recomputed rather than taken from the run, and held where the two disagree.
  See the namespace docstring: `:deductions` on a request is a single number
  nobody forces to equal the sum of the five withholding fields."
  [gross-fig deduction-figs declared-net]
  (let [total (prov/total (cons gross-fig deduction-figs))]
    (cond
      (not (:total/complete? total))
      (prov/unknown "差引支給額"
                    (str "総支給額または控除のいずれかが未確定なので、"
                         "差引支給額は計算できない: "
                         (str/join "、" (map :figure/label (:total/blocked-by total))))
                    nil)

      :else
      (let [computed (- (prov/amount gross-fig)
                        (reduce + 0 (keep prov/amount deduction-figs)))]
        (if (and (number? declared-net) (not= computed declared-net))
          (prov/held "差引支給額"
                     (str "この run が計上した差引支給額 " declared-net
                          " は、総支給額 " (prov/amount gross-fig)
                          " から控除合計 "
                          (reduce + 0 (keep prov/amount deduction-figs))
                          " を引いた " computed " と一致しない。"
                          "給与明細は食い違いを黙って一方に寄せてよい書類ではない")
                     "proposal :net")
          (prov/derived "差引支給額" computed
                        "総支給額 − 控除合計"))))))

(defn lines
  "One run as itemised lines.

    {:contract           the REGISTERED contract record, or nil
     :timesheets         the worker's REGISTERED timesheet entries
     :run                the committed payload / the proposal
     :verdict            the governor's verdict
     :disposition        :commit | :request-approval | :hold | nil}

  Returns

    {:meisai/gross            figure
     :meisai/deductions       [{:line/… :line/figure} …]
     :meisai/deduction-total  figure
     :meisai/net              figure
     :meisai/basis            `payroll.chingin/assess`
     :meisai/disposition      as given
     :meisai/coverage         `payroll.artifact.text/coverage`-shaped counts
     :meisai/figures          every figure, flat, for the coverage floor}"
  [{:keys [contract timesheets run verdict disposition juminzei]}]
  (let [basis (chingin/assess {:contract contract :timesheets timesheets})
        gross (chingin/gross-figure basis (:gross run)
                                    {:derived prov/derived :held prov/held})
        ded (deductions run {:social-insurance (:social-insurance verdict)
                             :withholding (get-in verdict [:tax :withholding])
                             ;; explicit argument first, then the verdict's —
                             ;; the governor does not evaluate 住民税 today
                             ;; and a caller that HAS the assessment must be
                             ;; able to hand it over.
                             :juminzei (or juminzei (:juminzei verdict))})
        ded-figs (mapv :line/figure ded)
        ded-total (prov/total-figure "控除合計" ded-figs "明細の控除行の合計")
        net (net-figure gross ded-figs (:net run))
        all (into [gross ded-total net] ded-figs)]
    {:meisai/gross gross
     :meisai/deductions ded
     :meisai/deduction-total ded-total
     :meisai/net net
     :meisai/basis basis
     :meisai/disposition disposition
     :meisai/figures all
     :meisai/certified (count (filter #(= :derived (:figure/provenance %)) all))
     :meisai/unverified (count (filter prov/unverified? all))}))

(defn payable?
  "May an operator pay from these lines?

  True only when the run committed AND the net line is `:derived` AND nothing
  in the lines is `:held` or `:unknown`. Deliberately not `(= :commit
  disposition)`: a run can commit with a 所得税 amount nobody certified, and
  `payable?` is the predicate a screen puts next to a payment button.

  It is also deliberately not `(not-any? unverified?)`. Every payslip in this
  repository carries at least one `:declared` figure — the income tax, whose
  amount taxlaw records as never certified — so a predicate that refused
  those would refuse every run there will ever be, which is a predicate
  nobody can use and therefore one everybody routes around."
  [{:meisai/keys [disposition net figures]}]
  (and (= :commit disposition)
       (= :derived (:figure/provenance net))
       (not-any? #(contains? #{:held :unknown} (:figure/provenance %)) figures)))
