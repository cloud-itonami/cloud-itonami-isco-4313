(ns payroll.shakai-hoken
  "社会保険・労働保険 — which withholdings this jurisdiction requires beyond
  income tax, what each one's base and determination are, which inputs the
  employer must supply, and what this actor refuses to compute.

  Until this namespace existed, `payroll.governor` gated **one** withholding.
  A Japanese payslip carries four: 健康保険料, 介護保険料, 厚生年金保険料,
  雇用保険料 — and a payroll actor that accounts for one of four and reports
  the run as committed is the shape this repository exists to refuse. It was
  doing it. `nbb scripts/repo-search.cljs 社会保険` found nothing across 4,205
  repos on 2026-08-18, so nothing upstream was being ignored either.

  Pure, like `payroll.nenmatsu`, `payroll.handoff` and `payroll.shiwake`:
  data in, data out, no store, no clock, no call. The governor does the store
  reads and hands the results here.

  ## Why the reading is HERE and not in `kotoba.taxlaw`

  源泉徴収 lives in `kotoba-lang/taxlaw` because it is tax. These four are
  not: 健康保険法, 厚生年金保険法, 介護保険法 and 労働保険徴収法 are social
  insurance and labour-insurance statutes, and taxlaw's own docstring scopes
  it to `what a tax record must carry`. Putting 保険料 in a tax catalog would
  make the next reader believe this fleet had read a tax rule it has not.

  This is still the wrong long-term home, for taxlaw's own reason: the moment
  a second actor needs 社会保険 it should move to a sibling capability library
  and be read once. It has not moved yet because nothing else needs it, and
  moving it before that would be a library with one consumer.

  ## The four are FOUR, and folding them into one number loses the law

  | | 健康保険 | 介護保険 | 厚生年金 | 雇用保険 |
  |---|---|---|---|---|
  | statute | 健康保険法 | 健保法+介護保険法 | 厚生年金保険法 | 労働保険徴収法 |
  | base | 標準報酬月額 | 標準報酬月額 | 標準報酬月額 | **賃金** |
  | split | 折半 (161①) | 折半 (161①) | 折半 (82①) | **NOT 折半** (31①一) |
  | rate in statute? | **no** — 協会 sets it per 都道府県 | **no** — 保険者 sets it | **YES** — 千分の百八十三・〇〇 | partly; every part is variable by 大臣 |
  | applies to whom | 被保険者 | 40–64 (介護保険法 9②) | 被保険者 | 被保険者 |

  Two of those rows are the reason a single `:social-insurance-withheld`
  number would be a lie. 雇用保険 is computed on a **different base** from the
  other three, and the employee's share of it is **not half** — 徴収法
  第三十一条第一項第一号 gives the employee half of the 雇用保険率 portion
  *after subtracting the 二事業率 portion*, which the employer bears alone.

  ## 労災保険 is not a payslip deduction, and saying nothing would look like
  ## having forgotten it

  労災保険料 is part of the same 一般保険料 as 雇用保険料 (徴収法 第十二条
  第一項第一号), but 第三十一条第一項第一号イ gives the employee a share of
  the 雇用保険率 portion **only**, and 第三十一条第三項 leaves the rest to the
  employer. So there is no 労災 line on a payslip. This namespace reports that
  as a named non-withholding rather than by omission — an omission and a
  finding print identically.

  ## The deduction is PERMISSIVE, and that is not a licence to skip it

  Read 健保法 第百六十七条第一項 and 厚年法 第八十四条第一項 next to 所得税法
  第百八十三条第一項. The tax article says 徴収し … 納付しなければならない.
  These two say **控除することができる** — *may* deduct. 徴収法 第三十二条
  第一項 says the same.

  What is mandatory is elsewhere and is not softened by that: the employee
  **bears** half (健保法 第百六十一条第一項 / 厚年法 第八十二条第一項) and the
  employer **must pay** the whole thing to the insurer (同 第二項). So an
  employer that does not deduct has not escaped the contribution; it has
  decided to carry the employee's half itself. That is a lawful choice and a
  DIFFERENT one, so `:not-deducted-by-choice` would have to be a declaration
  somebody made, never a default — and this actor does not offer it, because
  nobody has asked for it and inventing the option would be inventing the
  declaration too.

  ## What this actor computes, and what it refuses

  **Refuses, and this is most of the work:**

  - **標準報酬月額 is not computable here.** 健保法 第四十条第一項 and 厚年法
    第二十条第一項 define it as a graded figure derived from 報酬月額, and
    健保法 第四十一条第一項 / 厚年法 第二十一条第一項 say who decides it: 保険者等
    / 実施機関, from 算定基礎届 and 月額変更届. It is an INPUT this employer
    receives, exactly as `:employment/year-end-declaration-filed?` is. Absent
    is its own answer and never a pass.
  - **健康保険料率 is not in the statute.** 健保法 第百六十条第一項 puts it
    between 千分の三十 and 千分の百三十 and hands the choice to 全国健康保険協会,
    **per 都道府県**, revised annually. This repository has fetched no rate
    table from 協会けんぽ, so it computes no 健康保険料. A rate typed from
    memory is the single most dangerous value that could be added here.
  - **介護保険料率 is not in the statute either** (健保法 第百六十条第十六項:
    保険者が定める), and 介護保険法 第九条第二号 turns the whole question on being
    四十歳以上六十五歳未満. `kotoba.labor/contract` carries **no date of
    birth** — measured, not assumed — and this actor holds no clock. Even
    given a birth date it would not derive the category: the boundary turns
    on 年齢計算ニ関スル法律, which this repository has not read, and on 住所を
    有する, which is not a payroll fact at all. So the category is an observed
    boolean an operator registers, and unobserved is a refusal.
  - **雇用保険率 is in the statute only as a default that may have moved.**
    徴収法 第十二条第四項 states 千分の八 + 千分の五 + 千分の三・五, and 第五項,
    第八項, 第十項 and 第十一項 each let 厚生労働大臣 change one of them for a
    stated period. Nothing in the statute says whether that happened for the
    保険年度 being run, and this repository has read no 告示. A figure computed
    from the statutory default would be right in most years and silently
    wrong in the others, which is worse than refusing.

  **Computes, once — and only because the rate was read from the statute
  itself:**

  - **厚生年金保険料.** 厚年法 第八十一条第四項 fixes the rate in the Act:
    「平成二十九年九月以後の月分 千分の百八十三・〇〇」. 第八十一条第三項 gives the
    formula (標準報酬月額 × 保険料率) and 第八十二条第一項 gives the employee 半額.
    Given a REGISTERED 標準報酬月額 and a contribution month at or after
    2017-09, the employee's share is exact.

    It is returned as a **ratio of two integers**, not as a number.
    srm × 183 / 2000 is exact for every integer 標準報酬月額, and
    ClojureScript has no distinct float type — a division here would hand back a value that prints
    like money and is not.

    The **yen** figure is still refused, because no 端数処理 rule for the
    employee's half was read. 国等の債権債務等の金額の端数計算に関する法律
    第二条第一項 rounds down what the employer owes the STATE; it says nothing
    about the split between employer and employee. What IS available without
    reading that rule is a bound: any rendering into whole yen, under ANY
    rounding rule, is within one yen of the exact value — so a declared whole
    yen amount a yen or more away from it is wrong under every rounding rule
    there could be, and that is the one amount check in this namespace.

  ## The contribution month is registered, never derived

  健保法 第百六十七条第一項 and 厚年法 第八十四条第一項 both authorise deducting
  **前月の**標準報酬月額に係る保険料 — the PREVIOUS month's. This actor does not
  derive that month from the run's `:period`: that is date arithmetic it does
  not do, and getting it wrong shifts a whole month of contributions onto the
  wrong payslip. The operator registers
  `:employment/standard-remuneration-month` as `\"YYYY-MM\"`, and any other
  shape is refused rather than guessed at.

  ## Non-JP

  `[:eu]` and `[:us]` have their own social insurance and this workspace has
  read none of it, so they answer `:not-catalogued` with the reason surfaced,
  exactly as `kotoba.taxlaw` does for the facets it left out. **Adding a
  jurisdiction must not widen a pass**, and `:not-catalogued` is a refusal
  here, so it cannot.")

;; ---------------------------------------------------------------------------
;; sources
;;
;; `:law/revision-id` is recorded next to `:law/id` because a social-insurance
;; statute is amended constantly — 健康保険法 was last enforced 2026-08-01 —
;; and a citation to the law id alone does not say WHICH text was read.
;; `:law/corpus-status` is the corpus's OWN `current_revision_status` string,
;; copied rather than interpreted; see 厚生年金保険法 below for why that
;; distinction is not pedantry.
;; ---------------------------------------------------------------------------

(def sources
  "Primary sources, keyed by id. Retrieved from the e-Gov law API v2 on
  2026-08-18; each `:source/retrieved-via` is the exact call."
  {:jp/kenko-hoken-ho
   {:source/title "健康保険法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "211AC0000000070"
    :law/num "大正十一年法律第七十号"
    :law/revision-id "211AC0000000070_20260801_508AC0000000031"
    :law/corpus-status "CurrentEnforced"
    :source/url "https://laws.e-gov.go.jp/law/211AC0000000070"
    :source/retrieved-at "2026-08-18"
    :source/retrieved-via
    "e-Gov law API v2 GET /api/2/law_data/211AC0000000070?response_format=json"}

   :jp/kosei-nenkin-hoken-ho
   {:source/title "厚生年金保険法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "329AC0000000115"
    :law/num "昭和二十九年法律第百十五号"
    :law/revision-id "329AC0000000115_20260525_506AC0000000052"
    ;; Copied verbatim, and it does NOT read `CurrentEnforced`. The revision
    ;; the API serves for this law id has 2026-05-25 as its enforcement date
    ;; and the next revision (20261001) is `UnEnforced`, so on 2026-08-18 this
    ;; IS the text in force — measured by listing
    ;; `/api/2/law_revisions/329AC0000000115` and finding nothing enforced
    ;; between them. The corpus's own label is kept anyway rather than
    ;; corrected: what a downstream reader needs is the string the corpus
    ;; said, plus this note, not a field we quietly improved.
    :law/corpus-status "PreviousEnforced"
    :law/corpus-status-note
    (str "corpus says PreviousEnforced; the next revision (20261001) is "
         "UnEnforced, so this is the text in force on 2026-08-18")
    :source/url "https://laws.e-gov.go.jp/law/329AC0000000115"
    :source/retrieved-at "2026-08-18"
    :source/retrieved-via
    "e-Gov law API v2 GET /api/2/law_data/329AC0000000115?response_format=json"}

   :jp/kaigo-hoken-ho
   {:source/title "介護保険法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "409AC0000000123"
    :law/num "平成九年法律第百二十三号"
    :law/revision-id "409AC0000000123_20260625_508AC0000000051"
    :source/url "https://laws.e-gov.go.jp/law/409AC0000000123"
    :source/retrieved-at "2026-08-18"
    :source/retrieved-via
    "e-Gov law API v2 GET /api/2/law_data/409AC0000000123?response_format=json"}

   :jp/rodo-hoken-chosyu-ho
   {:source/title "労働保険の保険料の徴収等に関する法律"
    :source/abbrev "労働保険徴収法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "344AC0000000084"
    :law/num "昭和四十四年法律第八十四号"
    :law/revision-id "344AC0000000084_20251001_506AC0000000026"
    :law/corpus-status "CurrentEnforced"
    :source/url "https://laws.e-gov.go.jp/law/344AC0000000084"
    :source/retrieved-at "2026-08-18"
    :source/retrieved-via
    "e-Gov law API v2 GET /api/2/law_data/344AC0000000084?response_format=json"}

   :jp/hasu-keisan-ho
   {:source/title "国等の債権債務等の金額の端数計算に関する法律"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "325AC0000000061"
    :law/num "昭和二十五年法律第六十一号"
    :law/revision-id "325AC0000000061_20101001_422AC0000000015"
    :source/url "https://laws.e-gov.go.jp/law/325AC0000000061"
    :source/retrieved-at "2026-08-18"
    :source/retrieved-via
    "e-Gov law API v2 GET /api/2/law_data/325AC0000000061?response_format=json"}})

;; ---------------------------------------------------------------------------
;; the catalog
;; ---------------------------------------------------------------------------

(def schemes
  "The four withholdings, in the order a Japanese payslip lists them.

  A vector rather than a set: the report is read by humans, and a set would
  reorder it differently on different runtimes."
  [:scheme/health-insurance
   :scheme/long-term-care-insurance
   :scheme/employees-pension
   :scheme/employment-insurance])

(def jurisdictions
  "What was READ, by jurisdiction. Absence of a jurisdiction here is
  `:not-catalogued`, which is a refusal — never a finding that no social
  insurance exists there."
  {[:jp]
   {:jurisdiction/path [:jp]
    :jurisdiction/label "日本"

    ;; -----------------------------------------------------------------
    ;; 健康保険 — 健康保険法
    ;; -----------------------------------------------------------------
    :scheme/health-insurance
    {:scheme/label "健康保険"
     :scheme/review :read-from-source
     :scheme/coverage-key :employment/health-insurance-insured?
     :scheme/amount-key :health-insurance-withheld

     ;; 第百六十七条第一項. Note 「控除することができる」 — permissive. See the
     ;; namespace docstring for why that is not a licence to skip it.
     :scheme/deduction-provision "健康保険法 第百六十七条第一項"
     :scheme/deduction-quote (str "事業主は、被保険者に対して通貨をもって報酬を支払"
                                  "う場合においては、被保険者の負担すべき前月の標準"
                                  "報酬月額に係る保険料（被保険者がその事業所に使用"
                                  "されなくなった場合においては、前月及びその月の標"
                                  "準報酬月額に係る保険料）を報酬から控除することが"
                                  "できる。")
     :scheme/deduction-permissive? true

     ;; 第百六十一条第一項 — 折半. The 但書 (任意継続被保険者 bears the whole
     ;; thing) is quoted with it rather than trimmed: this actor does not
     ;; model 任意継続被保険者, and a reader must be able to see that the
     ;; article has a case this actor is silent about.
     :scheme/split :half
     :scheme/split-provision "健康保険法 第百六十一条第一項"
     :scheme/split-quote (str "被保険者及び被保険者を使用する事業主は、それぞれ"
                              "保険料額の二分の一を負担する。ただし、任意継続被"
                              "保険者は、その全額を負担する。")
     :scheme/liability-provision "健康保険法 第百六十一条第二項"

     ;; 第四十条第一項 — the base. The grade table itself is NOT transcribed:
     ;; 第四十条第二項 lets 政令 add grades above the top one, so a table copied
     ;; here would be a second source of truth that goes stale silently. What
     ;; matters for this actor is that the figure is graded and is decided
     ;; elsewhere.
     :scheme/base :standard-remuneration-monthly
     :scheme/base-provision "健康保険法 第四十条第一項"
     :scheme/base-quote (str "標準報酬月額は、被保険者の報酬月額に基づき、次の"
                             "等級区分（次項の規定により等級区分の改定が行われ"
                             "たときは、改定後の等級区分）によって定める。")
     :scheme/base-quote-is-partial? true
     :scheme/base-quote-omits "等級区分の表（第一級から第五十級まで）"
     :scheme/base-determined-by "保険者等"
     :scheme/base-determination-provision "健康保険法 第四十一条第一項"
     :scheme/base-determination-quote (str "保険者等は、被保険者が毎年七月一日現に使用される"
                                           "事業所において同日前三月間（その事業所で継続して"
                                           "使用された期間に限るものとし、かつ、報酬支払の基"
                                           "礎となった日数が十七日（厚生労働省令で定める者に"
                                           "あっては、十一日。第四十三条第一項、第四十三条の"
                                           "二第一項及び第四十三条の三第一項において同じ。）"
                                           "未満である月があるときは、その月を除く。）に受け"
                                           "た報酬の総額をその期間の月数で除して得た額を報酬"
                                           "月額として、標準報酬月額を決定する。")

     ;; 第百六十条第一項 — the rate is NOT in the Act. The Act gives a range and
     ;; hands the choice to 協会, per 都道府県, and 第六項〜第九項 make it an
     ;; annual decision requiring 大臣認可 and 告示.
     :scheme/rate
     {:rate/in-statute? false
      :rate/provision "健康保険法 第百六十条第一項"
      :rate/quote (str "協会が管掌する健康保険の被保険者に関する一般保険"
                       "料率は、千分の三十から千分の百三十までの範囲内に"
                       "おいて、支部被保険者（各支部の都道府県に所在する"
                       "適用事業所に使用される被保険者及び当該都道府県の"
                       "区域内に住所又は居所を有する任意継続被保険者をい"
                       "う。以下同じ。）を単位として協会が決定するものと"
                       "する。")
      :rate/set-by "全国健康保険協会（都道府県単位保険料率）"
      :rate/statutory-range-per-mille {:min 30 :max 130}
      :rate/not-read
      (str "協会けんぽの都道府県単位保険料率（年度ごと・都道府県ごとの告示）。"
           "この repository は取得していないので、健康保険料の金額は計算しない")}

     :scheme/sources [:jp/kenko-hoken-ho]}

    ;; -----------------------------------------------------------------
    ;; 介護保険 — 健康保険法 + 介護保険法
    ;; -----------------------------------------------------------------
    :scheme/long-term-care-insurance
    {:scheme/label "介護保険"
     :scheme/review :read-from-source
     :scheme/coverage-key :employment/care-insurance-second-category?
     :scheme/amount-key :care-insurance-withheld

     ;; It rides on the health-insurance deduction: 第百五十六条第一項第一号
     ;; makes 介護保険料額 a named COMPONENT of the 保険料額 of a 介護保険第二号
     ;; 被保険者, and the deduction article is the same one. It is reported as
     ;; its own line because the Act names it as its own amount and because a
     ;; payslip shows it separately.
     :scheme/deduction-provision "健康保険法 第百六十七条第一項"
     :scheme/deduction-quote (str "事業主は、被保険者に対して通貨をもって報酬を支払"
                                  "う場合においては、被保険者の負担すべき前月の標準"
                                  "報酬月額に係る保険料（被保険者がその事業所に使用"
                                  "されなくなった場合においては、前月及びその月の標"
                                  "準報酬月額に係る保険料）を報酬から控除することが"
                                  "できる。")
     :scheme/deduction-permissive? true
     :scheme/component-provision "健康保険法 第百五十六条第一項第一号"
     :scheme/component-quote (str "一介護保険法第九条第二号に規定する被保険者（以下"
                                  "「介護保険第二号被保険者」という。）である被保険"
                                  "者一般保険料等額（各被保険者の標準報酬月額及び標"
                                  "準賞与額にそれぞれ一般保険料率（基本保険料率と特"
                                  "定保険料率とを合算した率をいう。）と子ども・子育"
                                  "て支援金率とを合算した率を乗じて得た額をいう。以"
                                  "下同じ。）と介護保険料額（各被保険者の標準報酬月"
                                  "額及び標準賞与額にそれぞれ介護保険料率を乗じて得"
                                  "た額をいう。以下同じ。）との合算額")

     :scheme/split :half
     :scheme/split-provision "健康保険法 第百六十一条第一項"
     :scheme/split-quote (str "被保険者及び被保険者を使用する事業主は、それぞれ"
                              "保険料額の二分の一を負担する。ただし、任意継続被"
                              "保険者は、その全額を負担する。")

     :scheme/base :standard-remuneration-monthly
     :scheme/base-provision "健康保険法 第四十条第一項"
     :scheme/base-quote (str "標準報酬月額は、被保険者の報酬月額に基づき、次の"
                             "等級区分（次項の規定により等級区分の改定が行われ"
                             "たときは、改定後の等級区分）によって定める。")
     :scheme/base-quote-is-partial? true
     :scheme/base-quote-omits "等級区分の表（第一級から第五十級まで）"
     :scheme/base-determined-by "保険者等"
     :scheme/base-determination-provision "健康保険法 第四十一条第一項"

     ;; 介護保険法 第九条第二号 — the age band, verbatim. This is the whole of
     ;; the eligibility test this actor can see, and it cannot evaluate it.
     :scheme/eligibility-provision "介護保険法 第九条第二号"
     :scheme/eligibility-quote (str "二市町村の区域内に住所を有する四十歳以上六十五歳"
                                    "未満の医療保険加入者（以下「第二号被保険者」とい"
                                    "う。）")
     :scheme/eligibility-not-derivable
     (str "kotoba.labor の契約記録に生年月日は無く、この actor は暦を持たない。"
          "仮に生年月日があっても、年齢の境界は 年齢計算ニ関スル法律 に依り、"
          "第九条第二号 は「市町村の区域内に住所を有する」ことも要求する。"
          "どちらもこの repository は読んでおらず、給与の事実でもない。"
          "したがって第二号被保険者かどうかは operator が登録する観測値であり、"
          "未登録は「該当しない」ではない")

     :scheme/rate
     {:rate/in-statute? false
      :rate/provision "健康保険法 第百六十条第十六項"
      :rate/quote (str "１６介護保険料率は、各年度において保険者が納付す"
                       "べき介護納付金（日雇特例被保険者に係るものを除く"
                       "。）の額を当該年度における当該保険者が管掌する介"
                       "護保険第二号被保険者である被保険者の総報酬額の総"
                       "額の見込額で除して得た率を基準として、保険者が定"
                       "める。")
      :rate/set-by "保険者"
      :rate/not-read
      (str "介護保険料率（保険者が年度ごとに定める率）。"
           "この repository は取得していないので、介護保険料の金額は計算しない")}

     :scheme/sources [:jp/kenko-hoken-ho :jp/kaigo-hoken-ho]}

    ;; -----------------------------------------------------------------
    ;; 厚生年金保険 — 厚生年金保険法。THE ONE WHOSE RATE IS IN THE ACT.
    ;; -----------------------------------------------------------------
    :scheme/employees-pension
    {:scheme/label "厚生年金保険"
     :scheme/review :read-from-source
     :scheme/coverage-key :employment/employees-pension-insured?
     :scheme/amount-key :employees-pension-withheld

     :scheme/deduction-provision "厚生年金保険法 第八十四条第一項"
     :scheme/deduction-quote (str "事業主は、被保険者に対して通貨をもつて報酬を支払"
                                  "う場合においては、被保険者の負担すべき前月の標準"
                                  "報酬月額に係る保険料（被保険者がその事業所又は船"
                                  "舶に使用されなくなつた場合においては、前月及びそ"
                                  "の月の標準報酬月額に係る保険料）を報酬から控除す"
                                  "ることができる。")
     :scheme/deduction-permissive? true

     :scheme/split :half
     :scheme/split-provision "厚生年金保険法 第八十二条第一項"
     :scheme/split-quote (str "被保険者及び被保険者を使用する事業主は、それぞれ"
                              "保険料の半額を負担する。")
     :scheme/liability-provision "厚生年金保険法 第八十二条第二項"

     :scheme/base :standard-remuneration-monthly
     :scheme/base-provision "厚生年金保険法 第二十条第一項"
     :scheme/base-quote (str "標準報酬月額は、被保険者の報酬月額に基づき、次の"
                             "等級区分（次項の規定により等級区分の改定が行われ"
                             "たときは、改定後の等級区分）によつて定める。")
     :scheme/base-quote-is-partial? true
     :scheme/base-quote-omits "等級区分の表"
     :scheme/base-determined-by "実施機関"
     :scheme/base-determination-provision "厚生年金保険法 第二十一条第一項"
     :scheme/base-determination-quote (str "実施機関は、被保険者が毎年七月一日現に使用される"
                                           "事業所において同日前三月間（その事業所で継続して"
                                           "使用された期間に限るものとし、かつ、報酬支払の基"
                                           "礎となつた日数が十七日（厚生労働省令で定める者に"
                                           "あつては、十一日。第二十三条第一項、第二十三条の"
                                           "二第一項及び第二十三条の三第一項において同じ。）"
                                           "未満である月があるときは、その月を除く。）に受け"
                                           "た報酬の総額をその期間の月数で除して得た額を報酬"
                                           "月額として、標準報酬月額を決定する。")

     :scheme/formula-provision "厚生年金保険法 第八十一条第三項"
     :scheme/formula-quote (str "３保険料額は、標準報酬月額及び標準賞与額にそれぞ"
                                "れ保険料率を乗じて得た額とする。")

     ;; 第八十一条第四項 — a table IN the Act. Only its final, open-ended row is
     ;; transcribed. The thirteen earlier rows are keyed by 元号 month ranges,
     ;; and converting those by hand is exactly the sort of transcription that
     ;; is wrong without anything noticing; a month before the transcribed row
     ;; is refused with `:rate-period-not-read` rather than rated at 183.
     :scheme/rate
     {:rate/in-statute? true
      :rate/provision "厚生年金保険法 第八十一条第四項"
      :rate/quote (str "４保険料率は、次の表の上欄に掲げる月分の保険料に"
                       "ついて、それぞれ同表の下欄に定める率とする。")
      :rate/row-quote "平成二十九年九月以後の月分千分の百八十三・〇〇"
      :rate/quote-is-partial? true
      :rate/quote-omits "平成十六年十月から平成二十九年八月までの十三行"
      :rate/per-mille 183
      :rate/applies-from-month "2017-09"
      :rate/applies-from-label "平成二十九年九月以後の月分"}

     :scheme/sources [:jp/kosei-nenkin-hoken-ho]}

    ;; -----------------------------------------------------------------
    ;; 雇用保険 — 労働保険徴収法。Different base, and NOT 折半.
    ;; -----------------------------------------------------------------
    :scheme/employment-insurance
    {:scheme/label "雇用保険"
     :scheme/review :read-from-source
     :scheme/coverage-key :employment/employment-insurance-insured?
     :scheme/amount-key :employment-insurance-withheld

     :scheme/deduction-provision "労働保険徴収法 第三十二条第一項"
     :scheme/deduction-quote (str "事業主は、厚生労働省令で定めるところにより、前条"
                                  "第一項又は第二項の規定による被保険者の負担すべき"
                                  "額に相当する額を当該被保険者に支払う賃金から控除"
                                  "することができる。この場合において、事業主は、労"
                                  "働保険料控除に関する計算書を作成し、その控除額を"
                                  "当該被保険者に知らせなければならない。")
     :scheme/deduction-permissive? true

     ;; NOT 折半 — and this is the row that makes a single combined
     ;; `:social-insurance-withheld` field impossible to write honestly.
     :scheme/split :half-of-the-non-nijigyo-part
     :scheme/split-provision "労働保険徴収法 第三十一条第一項第一号"
     :scheme/split-quote (str "一第十二条第一項第一号の事業に係る被保険者イに掲"
                              "げる額からロに掲げる額を減じた額の二分の一の額イ"
                              "当該事業に係る一般保険料の額のうち雇用保険率に応"
                              "ずる部分の額ロイの額に相当する額に二事業率を乗じ"
                              "て得た額")
     :scheme/employer-bears-the-rest-provision "労働保険徴収法 第三十一条第三項"
     :scheme/employer-bears-the-rest-quote (str "３事業主は、当該事業に係る労働保険料の額のうち当"
                                                "該労働保険料の額から前二項の規定による被保険者の"
                                                "負担すべき額を控除した額を負担するものとする。")

     ;; 賃金, not 標準報酬月額.
     :scheme/base :wages
     :scheme/base-note
     (str "基礎は賃金であって標準報酬月額ではない。この actor が持つ :gross が"
          "徴収法にいう賃金と一致することは確かめていないので、"
          "金額の計算に使わない")

     :scheme/rate
     {:rate/in-statute? :as-a-default-that-may-have-moved
      :rate/provision "労働保険徴収法 第十二条第四項"
      :rate/quote (str "４雇用保険率は、次の各号に掲げる率の区分に応じ、"
                       "当該各号に定める率を合計して得た率とする。")
      :rate/component-quotes [(str "一失業等給付費等充当徴収保険率（雇用保険率のうち"
                                   "雇用保険法の規定による失業等給付及び同法第六十四"
                                   "条に規定する事業に要する費用に対応する部分の率を"
                                   "いう。以下同じ。）千分の八")
                              (str "二育児休業給付費充当徴収保険率（雇用保険率のうち"
                                   "雇用保険法の規定による育児休業給付に要する費用に"
                                   "対応する部分の率をいう。以下同じ。）千分の五（第"
                                   "八項の規定により変更されたときは、その変更された"
                                   "率とする。）")
                              (str "三二事業費充当徴収保険率（雇用保険率のうち雇用保"
                                   "険法の規定による雇用安定事業及び能力開発事業（同"
                                   "法第六十三条に規定するものに限る。）に要する費用"
                                   "に対応する部分の率をいう。以下同じ。）千分の三・"
                                   "五（第一号ハに掲げる事業については、千分の四・五"
                                   "とし、第十項又は第十一項の規定により変更されたと"
                                   "きは、その変更された率とする。）")]
      ;; per TEN THOUSAND, not per mille, because 二事業費充当徴収保険率 is
      ;; 千分の三・五 and a ratio literal (7/2) does not exist in
      ;; ClojureScript — a rate that reads on one runtime and not the other
      ;; would make this catalog a `.cljc` in name only.
      :rate/statutory-default-per-ten-thousand
      {:unemployment-benefits 80          ; 失業等給付費等充当徴収保険率 千分の八
       :childcare-leave-benefits 50       ; 育児休業給付費充当徴収保険率 千分の五
       :two-programmes 35}                ; 二事業費充当徴収保険率 千分の三・五
      :rate/variable-by ["労働保険徴収法 第十二条第五項"
                         "労働保険徴収法 第十二条第八項"
                         "労働保険徴収法 第十二条第十項"
                         "労働保険徴収法 第十二条第十一項"]
      :rate/not-read
      (str "条文の率は既定値であって、第五項・第八項・第十項・第十一項 により"
           "厚生労働大臣が期間を定めて変更できる。その保険年度に変更があったか"
           "どうかは条文からは読めず、この repository は告示を読んでいない。"
           "既定値で計算すれば多くの年は正しく、残りの年は静かに間違う")}

     :scheme/sources [:jp/rodo-hoken-chosyu-ho]}

    ;; -----------------------------------------------------------------
    ;; NOT a wage deduction — recorded so that silence cannot be read as
    ;; having forgotten it.
    ;; -----------------------------------------------------------------
    :jurisdiction/not-withheld
    {:scheme/label "労災保険"
     :scheme/why
     (str "労災保険料は一般保険料の一部だが（徴収法 第十二条第一項第一号）、"
          "被保険者が負担するのは 第三十一条第一項第一号イ の「雇用保険率に"
          "応ずる部分」だけで、残りは 第三十一条第三項 により事業主が負担する。"
          "したがって賃金からの控除は無い")
     :scheme/provisions ["労働保険徴収法 第三十一条第一項第一号"
                         "労働保険徴収法 第三十一条第三項"]
     :scheme/rate-provision "労働保険徴収法 第十二条第二項"
     :scheme/rate-set-by "厚生労働大臣"
     :scheme/rate-quote-tail "厚生労働大臣が定める。"}

    ;; -----------------------------------------------------------------
    ;; The rounding law that was read, and what it does NOT settle.
    ;; -----------------------------------------------------------------
    :jurisdiction/rounding
    {:rounding/provision "国等の債権債務等の金額の端数計算に関する法律 第二条第一項"
     :rounding/quote (str "国及び公庫等の債権で金銭の給付を目的とするもの（"
                          "以下「債権」という。）又は国及び公庫等の債務で金"
                          "銭の給付を目的とするもの（以下「債務」という。）"
                          "の確定金額に一円未満の端数があるときは、その端数"
                          "金額を切り捨てるものとする。")
     :rounding/settles "事業主が国等に納付すべき確定金額（一円未満切捨て）"
     :rounding/does-not-settle
     (str "事業主と被保険者の間の折半額を円単位でどう丸めるか。"
          "この repository はその規則を読んでいないので、円建ての被保険者負担額は"
          "計算しない。ただし「どんな丸め方であっても厳密値との差は一円未満」"
          "という限界だけは、規則を読まずとも成り立つ")}}

   ;; -------------------------------------------------------------------
   ;; The two jurisdictions `kotoba.taxlaw` carries and this catalog has not
   ;; read. They are HERE, with reasons, rather than absent: an operator told
   ;; `not catalogued` with no reason cannot tell `there is no such system`
   ;; from `nobody read it`, and for social insurance those are opposite
   ;; instructions. Neither is a pass.
   ;; -------------------------------------------------------------------
   [:eu]
   {:jurisdiction/path [:eu]
    :jurisdiction/label "European Union"
    :jurisdiction/out-of-scope
    (str "社会保険は加盟国法であり、Union レベルの制度は Regulation (EC) "
         "883/2004 の調整規則（どの国の法が適用されるかを決める規則）であって"
         "保険料を課す規則ではない。この repository はどちらも読んでいない")}

   [:us]
   {:jurisdiction/path [:us]
    :jurisdiction/label "United States (federal)"
    :jurisdiction/out-of-scope
    (str "FICA（IRC §3101 の被用者負担分・§3111 の事業主負担分）と FUTA"
         "（IRC §3301）は実在し、被用者負担分は賃金から源泉徴収される。"
         "この repository はそのいずれも読んでいない。"
         "未読であって不存在ではない ── 社会保険で間違える向きとして高くつく方")}})

;; ---------------------------------------------------------------------------
;; the API
;; ---------------------------------------------------------------------------

(defn- normalize
  "Accept `[:jp]` or `:jp`, like `kotoba.taxlaw/normalize` — actors store a
  jurisdiction however their own schema does."
  [j]
  (cond (vector? j) j
        (nil? j) nil
        :else [j]))

(defn facet-of
  "The rules this catalog holds for jurisdiction `j` about `scheme`, or nil."
  [j scheme]
  (get-in jurisdictions [(normalize j) scheme]))

(defn out-of-scope
  "Why this catalog deliberately holds no rules for `j`, or nil. Still not a
  pass — `assess` answers `:not-catalogued` either way."
  [j]
  (get-in jurisdictions [(normalize j) :jurisdiction/out-of-scope]))

(defn covered?
  "Is this jurisdiction catalogued with at least one scheme? `nil` is not."
  [j]
  (boolean (some #(facet-of j %) schemes)))

(def answers
  "Per-scheme answers that ARE an answer. `payroll.governor` lets these
  commit."
  #{:accounted-for :not-covered})

(def refusals
  "Per-scheme answers that are the absence of one. `payroll.governor` HOLDS
  on every one of these.

  A set rather than `(complement answers)`, for `payroll.nenmatsu/refusals`'
  reason: an answer this namespace forgets to classify belongs to NEITHER, so
  `answerable?` is false and the governor holds. A new answer defaults to
  refused."
  #{:not-catalogued
    :coverage-not-observed
    :standard-remuneration-not-observed
    :standard-remuneration-month-not-observed
    :rate-period-not-read
    :not-accounted-for
    :malformed-amount
    :amount-contradicts-statutory-rate})

(defn- declared
  "A boolean, or nil for anything else. `\"true\"`, `:yes` and `1` are not
  declarations — `payroll.nenmatsu/declared`'s reason, and the same failure
  mode: a coverage flag carrying the STRING `\"true\"` would satisfy neither
  `nil?` nor `false?` and would fall through to `covered`, buying a pass with
  a typo."
  [x]
  (when (boolean? x) x))

(defn- whole-yen?
  "A non-negative whole number of yen.

  `(mod x 1)` rather than `integer?`: ClojureScript has no distinct float
  type, so `(integer? 3.0)` is true there and false on the JVM, and a
  predicate that answers differently on the two runtimes is worse than no
  predicate. This one answers the question actually being asked — is there a
  fraction of a yen here — identically on both."
  [x]
  (and (number? x) (not (neg? x)) (zero? (mod x 1))))

(defn- positive-yen? [x] (and (whole-yen? x) (pos? x)))

(def month-format
  "The one shape `:employment/standard-remuneration-month` may take.

  Fixed width, so lexicographic comparison against
  `:rate/applies-from-month` is exactly chronological comparison. Any other
  shape is refused rather than parsed: this actor does no date arithmetic,
  and a month it guessed at would move a whole month of contributions."
  #"^\d{4}-(0[1-9]|1[0-2])$")

(defn month? [x] (boolean (and (string? x) (re-matches month-format x))))

(defn- abs* [n] (if (neg? n) (- n) n))

(defn pension-employee-share
  "The exact employee share of 厚生年金保険料 for one month, as a ratio of two
  integers.

    保険料額        = 標準報酬月額 × 保険料率   (厚年法 第八十一条第三項)
    保険料率        = 千分の百八十三・〇〇      (厚年法 第八十一条第四項)
    被保険者の負担  = その半額                  (厚年法 第八十二条第一項)

  so the share is `srm × 183 / 1000 / 2` = `srm × 183 / 2000`, whose
  numerator is an integer for every integer 標準報酬月額.

  Returned as `{:numerator n :denominator d}` and NOT as a number. Money is
  arithmetic and ClojureScript has no distinct float type: dividing here
  would hand back a value that prints like yen, compares like a float and is
  neither."
  [srm per-mille]
  {:numerator (* srm per-mille) :denominator 2000 :unit :yen})

(defn within-one-yen?
  "Is `declared` (whole yen) within one yen of the exact ratio?

  This is the ONE amount check in this namespace, and it needs no 端数処理
  rule. Whatever rule renders the exact share into whole yen — floor, ceiling,
  half-up, or the employer absorbing the remainder — the result differs from
  the exact value by strictly less than one yen. So a declared whole-yen
  amount a yen or more away is wrong under every rounding rule there could
  be, and one within a yen is not thereby certified: this bounds the answer,
  it does not compute it.

  All integer arithmetic: `declared × denominator` against `numerator`."
  [declared {:keys [numerator denominator]}]
  (< (abs* (- (* declared denominator) numerator)) denominator))

(def ^:private why
  {:not-catalogued
   (str "宣言された法域の社会保険・労働保険をこの repository は読んでいない。"
        "未読は不存在ではない（未検査は合格ではない）")
   :coverage-not-observed
   (str "この被用者がこの保険の被保険者かどうかが契約に登録されていない。"
        "資格の有無は事業所と労働条件についての観測であって、"
        "software が推定してよい事実ではない（未観測は「該当しない」ではない）")
   :standard-remuneration-not-observed
   (str "標準報酬月額が契約に登録されていない。"
        "標準報酬月額は保険者等が算定基礎届・月額変更届に基づいて決定するもので、"
        "この actor が計算するものではない。"
        "控除できるのは「標準報酬月額に係る保険料」なので、"
        "標準報酬月額の無い控除は条文が認めた控除ではない")
   :standard-remuneration-month-not-observed
   (str "どの月分の標準報酬月額に係る保険料かが登録されていない（形式は YYYY-MM）。"
        "条文が控除を認めるのは「前月の」標準報酬月額に係る保険料であり、"
        "この actor は :period から前月を導出しない"
        "（暦を持たず、間違えれば一月分の保険料が別の給与明細に載る）")
   :rate-period-not-read
   (str "登録された月分に適用される保険料率の行を、この repository は"
        "転記していない（条文の表のうち最終行だけを読んでいる）")
   :not-accounted-for
   (str "この保険の被保険者であるのに、proposal がその保険料を一切計上していない。"
        "未計上は保険料零ではなく未回答である")
   :malformed-amount
   (str "計上額が非負の円単位整数ではない。"
        "負の額・文字列・円未満の端数は計上ではない")
   :amount-contradicts-statutory-rate
   (str "計上額が、条文の保険料率から出る厳密値をどう丸めても届かない値である"
        "（どんな端数処理規則でも差は一円未満に収まる）")
   :accounted-for
   "被保険者であり、proposal がこの保険料を計上している"
   :not-covered
   (str "この被用者はこの保険の被保険者ではないと登録されている。"
        "したがってこの保険料の控除は無い")})

(defn- amount-report
  "What can and cannot be computed about this scheme's amount.

  For the three schemes whose rate is not in the statute this is a refusal
  with the missing artefact NAMED — read off the catalog, never typed here,
  so a repository that never read the article cannot claim to know which
  table it did not read. For 厚生年金 it is the exact ratio plus the reason
  the yen figure is still refused."
  [scheme-rules srm]
  (let [{:keys [rate/in-statute? rate/provision rate/not-read rate/per-mille
                rate/applies-from-label]} (:scheme/rate scheme-rules)]
    (if (and (true? in-statute?) (positive-yen? srm))
      (let [exact (pension-employee-share srm per-mille)]
        {:amount/computable? true
         :amount/provision provision
         :amount/rate-per-mille per-mille
         :amount/rate-applies-from applies-from-label
         :amount/standard-remuneration-monthly-yen srm
         :amount/employee-share-exact exact
         :amount/employee-share-yen :not-computable
         :amount/why-no-yen-figure
         (str "厳密値は " (:numerator exact) "/" (:denominator exact) " 円。"
              "円単位に丸める規則（折半額の端数処理）をこの repository は"
              "読んでいないので、円建ての額は出さない。"
              "出せるのは「どんな丸め方でも厳密値との差は一円未満」という限界だけ")})
      {:amount/computable? false
       :amount/provision provision
       :amount/why (or not-read (:scheme/base-note scheme-rules))
       :amount/rate-in-statute? in-statute?})))

(defn- scheme-report
  "One scheme's answer. The order of the questions is deliberate and mirrors
  `payroll.nenmatsu`: what an operator must REGISTER is asked before what the
  proposal must DECLARE, because the statutes authorise deducting `保険料 on a
  標準報酬月額` — with no 標準報酬月額 registered there is no authorised
  deduction for the proposal to account for, and asking whether it accounted
  for one would be asking about a figure that has no basis yet."
  [rules contract proposal]
  (let [covered (declared (get contract (:scheme/coverage-key rules)))
        srm (:employment/standard-remuneration-monthly-yen contract)
        month (:employment/standard-remuneration-month contract)
        needs-srm? (= :standard-remuneration-monthly (:scheme/base rules))
        rate (:scheme/rate rules)
        declared-amount (get proposal (:scheme/amount-key rules))
        base {:scheme/label (:scheme/label rules)
              :scheme/provision (:scheme/deduction-provision rules)
              :scheme/base (:scheme/base rules)
              :scheme/split (:scheme/split rules)
              :scheme/coverage-key (:scheme/coverage-key rules)
              :scheme/amount-key (:scheme/amount-key rules)
              :scheme/declared declared-amount
              :scheme/covered? covered}
        answer (cond
                 (nil? covered) :coverage-not-observed
                 (false? covered) :not-covered
                 (and needs-srm? (not (positive-yen? srm)))
                 :standard-remuneration-not-observed
                 (and needs-srm? (not (month? month)))
                 :standard-remuneration-month-not-observed
                 ;; `compare`, not `<`: `<` is numeric in Clojure and these
                 ;; are strings. Fixed-width YYYY-MM makes lexicographic
                 ;; order exactly chronological order, which is why
                 ;; `month-format` refuses any other shape.
                 (and (true? (:rate/in-statute? rate))
                      (neg? (compare month (:rate/applies-from-month rate))))
                 :rate-period-not-read
                 (nil? declared-amount) :not-accounted-for
                 (not (whole-yen? declared-amount)) :malformed-amount
                 (and (true? (:rate/in-statute? rate))
                      (not (within-one-yen?
                            declared-amount
                            (pension-employee-share srm (:rate/per-mille rate)))))
                 :amount-contradicts-statutory-rate
                 :else :accounted-for)]
    (cond-> (assoc base
                   :scheme/answer answer
                   :scheme/answerable? (contains? answers answer)
                   :scheme/why (get why answer))
      (= :coverage-not-observed answer)
      (assoc :scheme/missing (:scheme/coverage-key rules))

      (= :standard-remuneration-not-observed answer)
      (assoc :scheme/missing :employment/standard-remuneration-monthly-yen)

      (= :standard-remuneration-month-not-observed answer)
      (assoc :scheme/missing :employment/standard-remuneration-month)

      (= :not-accounted-for answer)
      (assoc :scheme/missing (:scheme/amount-key rules))

      ;; the eligibility article rides along on 介護保険's refusal, because
      ;; `register a boolean` is not an instruction until the reader can see
      ;; WHICH boolean the law is asking about.
      (and (= :coverage-not-observed answer)
           (:scheme/eligibility-provision rules))
      (assoc :scheme/eligibility-provision (:scheme/eligibility-provision rules)
             :scheme/eligibility-quote (:scheme/eligibility-quote rules)
             :scheme/eligibility-not-derivable
             (:scheme/eligibility-not-derivable rules))

      ;; the amount question only arises where the deduction does.
      (contains? #{:accounted-for :amount-contradicts-statutory-rate} answer)
      (assoc :scheme/amount (amount-report rules srm)))))

(defn assess
  "Assess one payroll run's social-insurance withholdings. Pure.

    {:jurisdiction  the EMPLOYER's, never the proposal's — `payroll.governor`
                    reads it off the registered employer for the reason rule 5
                    does: an advisor that could pick a jurisdiction could pick
                    the one whose rules it satisfies
     :contract      the REGISTERED contract record, or nil. Every fact an
                    operator observes lives here: the four coverage booleans,
                    the 標準報酬月額 and its month
     :proposal      the proposal, which contributes exactly one thing per
                    scheme — the amount it claims to have withheld. Whether it
                    accounts for each of them is precisely the question}

  Returns

    {:shakai-hoken/answer      :answered | :refused | :not-catalogued
     :shakai-hoken/answerable? every scheme answered
     :shakai-hoken/schemes     {scheme report}
     :shakai-hoken/refusals    [{:scheme :answer :why :missing}]
     :shakai-hoken/not-withheld   労災保険, named rather than omitted
     :shakai-hoken/rounding       what the read rounding law does not settle}"
  [{:keys [jurisdiction contract proposal]}]
  (let [path (normalize jurisdiction)]
    (if-not (covered? path)
      {:shakai-hoken/jurisdiction path
       :shakai-hoken/answer :not-catalogued
       :shakai-hoken/answerable? false
       :shakai-hoken/schemes {}
       :shakai-hoken/out-of-scope (out-of-scope path)
       :shakai-hoken/why (or (out-of-scope path) (get why :not-catalogued))
       :shakai-hoken/refusals
       [{:scheme nil
         :answer :not-catalogued
         :why (or (out-of-scope path) (get why :not-catalogued))}]}
      (let [reports (into {}
                          (map (fn [s]
                                 [s (scheme-report (facet-of path s)
                                                   contract proposal)]))
                          schemes)
            refused (vec (for [s schemes
                               :let [r (get reports s)]
                               :when (not (:scheme/answerable? r))]
                           (cond-> {:scheme s
                                    :answer (:scheme/answer r)
                                    :label (:scheme/label r)
                                    :provision (:scheme/provision r)
                                    :why (:scheme/why r)}
                             (:scheme/missing r)
                             (assoc :missing (:scheme/missing r)))))]
        {:shakai-hoken/jurisdiction path
         :shakai-hoken/answer (if (seq refused) :refused :answered)
         :shakai-hoken/answerable? (empty? refused)
         :shakai-hoken/schemes reports
         :shakai-hoken/refusals refused
         :shakai-hoken/accounted
         (vec (for [s schemes :when (= :accounted-for
                                       (:scheme/answer (get reports s)))] s))
         :shakai-hoken/not-covered
         (vec (for [s schemes :when (= :not-covered
                                       (:scheme/answer (get reports s)))] s))
         ;; carried on every answer, refused or not: 労災 has no payslip line
         ;; and the difference between `no line` and `no thought` is not
         ;; visible in an omission.
         :shakai-hoken/not-withheld (facet-of path :jurisdiction/not-withheld)
         :shakai-hoken/rounding (facet-of path :jurisdiction/rounding)}))))

(defn withheld-total
  "The 社会保険料 a run declares, summed. `nil` when any covered scheme did not
  declare one — a sum over schemes where some declared nothing is not the
  total, and a caller handed a number cannot tell.

  Used by `payroll.shiwake` to build the 預り金 line, which is why it must
  refuse rather than under-report: an entry built from a partial total
  balances, having lost the difference."
  [assessment schemes*]
  (let [rs (map #(get-in assessment [:shakai-hoken/schemes %]) schemes*)]
    (when (every? #(= :accounted-for (:scheme/answer %)) rs)
      (reduce + 0 (map :scheme/declared rs)))))
