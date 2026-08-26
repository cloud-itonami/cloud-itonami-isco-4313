(ns payroll.rates
  "Versioned rate datasets, and the calculators that read them.

  `payroll.shakai-hoken` refuses three of four premiums on the stated grounds
  that the rate is not in the statute and this repository has read no 告示 or
  rate table. This namespace is where those tables live once they have been
  read — as DATA with an effective date, a jurisdiction, a source URL and a
  version, so that `which figure applied in August` is a lookup rather than
  an archaeology problem.

  ## Datasets, not constants

  A rate typed into a function is a rate that is right until it is silently
  wrong. Every rate here is a row in a dataset keyed by
  `[jurisdiction prefecture]` and bounded by `:rate/effective-from` /
  `:rate/effective-to`, and `lookup` returns a REFUSAL for a month outside
  every row rather than the nearest one.

  ## 東京 is 東京, and generalising it is the failure this guards against

  協会けんぽ sets 健康保険料率 **per 都道府県** (健保法 第百六十条第一項). The
  2026 Tokyo table is transcribed; nothing else is. A run for an employer in
  大阪 gets `:prefecture-not-transcribed`, which is a refusal — and it must
  be, because the Tokyo rate is a plausible number that would produce a
  plausible payslip and be wrong by a few hundred yen a month, every month,
  invisibly.

  So `:employment/health-insurance-prefecture` is a REGISTERED fact. An
  employer that has not registered it does not get Tokyo by default.

  ## What is transcribed, and from where

  | dataset | figure | source |
  |---|---|---|
  | 健康保険（東京・令和8年度） | 9.85% 合計 | 協会けんぽ 東京 2026 保険料額表 |
  | 介護保険（第2号被保険者） | 1.62% 合計 | 同 |
  | 厚生年金 | 18.3% 合計 | 厚年法 第八十一条第四項（千分の百八十三・〇〇） |
  | 雇用保険（一般の事業・令和8年度） | 労働者 5/1000・事業主 8.5/1000 | 厚労省 令和8年度の雇用保険料率 |
  | 源泉徴収税額表（月額表・令和8年分） | 231 帯 + 9 しきい値 + 11 超過税率の段 | 国税庁 workbook（SHA-256 で pin） |

  The 厚生年金 row is the only one whose source is the STATUTE; the others
  are administrative publications, which is exactly why they are in a
  dataset with a date on them and the statute's rate is also in
  `payroll.shakai-hoken`.

  ## 源泉徴収税額表 is transcribed, and what remains unread is named

  `withholding-table` used to be a shape with **zero bands** and a refusal
  for every input, on the stated ground that a partially transcribed banded
  table answers for the salaries somebody happened to type. It is now
  transcribed WHOLE, from `payroll.rates.monthly-2026` — a generated file
  produced by `tools/import_nta_2026.clj` from the 国税庁 workbook, pinned
  by SHA-256. 231 bands, nine printed threshold rows, the eleven
  excess-rate segments and the 7人超 deduction, none of them typed by hand.

  So the objection that made the empty table honest is answered: there is no
  subset. What is still missing is named rather than approximated —

  - **日額表** and **賞与に対する源泉徴収税額の算出率の表** are different
    tables in the same publication and are not here. `withhold` answers
    the 月額表 and nothing else.
  - **The 端数処理 above 740,000円 (甲) and below 105,000円 (乙)** is not
    printed anywhere in what was read. In those two segments the workbook
    gives a RATE, and the fraction of a yen it produces has to be resolved
    by a rule this repository has not read. `withhold` returns the exact
    ratio and refuses `:rounding-not-transcribed` rather than choosing one.

  The second of those is the shape of every honest refusal in this
  repository: the arithmetic is reported, the rule that is missing is
  named, and no plausible yen figure is invented to fill the hole.

  ## The pin, not the URL, is what identifies the table

  国税庁 replaces these workbooks in place when a 告示 is amended, so
  `:table/provenance`'s `:source/sha256` — and not `:source/url` — is what
  lets a reader confirm the figures below came from the edition they are
  holding. `sources` therefore reads the URL, the page, the digest and the
  retrieval date OUT of the generated file rather than restating them: a
  regeneration against a new pin moves both together or neither."
  (:require [clojure.string :as str]
            [payroll.rates.monthly-2026 :as nta]))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(def sources
  {:kyokaikenpo/tokyo-2026
   {:source/title "令和8年度 都道府県単位保険料率（東京支部）保険料額表"
    :source/authority "全国健康保険協会（協会けんぽ）"
    :source/url "https://www.kyoukaikenpo.or.jp/~/media/Files/shared/hokenryouritu/r8/ippan/R8_13tokyo.pdf"
    :source/read-at "2026-08-26"
    :source/scope "東京支部のみ。他の46支部は転記していない"}
   :mhlw/employment-insurance-2026
   {:source/title "令和8年度の雇用保険料率"
    :source/authority "厚生労働省"
    :source/url "https://www.mhlw.go.jp/stf/newpage_71570.html"
    :source/read-at "2026-08-26"
    :source/scope "一般の事業のみ。農林水産・清酒製造・建設の事業は転記していない"}
   ;; Read OUT of the generated table rather than restated here. The pin and
   ;; the URL that produced it must move together; two copies of a digest is
   ;; one copy that goes stale silently.
   :nta/withholding-2026
   {:source/title (:source/title nta/provenance)
    :source/authority (:source/authority nta/provenance)
    :source/url (:source/url nta/provenance)
    :source/page (:source/page nta/provenance)
    :source/sha256 (:source/sha256 nta/provenance)
    :source/bytes (:source/bytes nta/provenance)
    :source/read-at (:source/retrieved-at nta/provenance)
    :source/scope (str "月額表のみ（"
                       (:source/sheet nta/provenance)
                       " シート）。"
                       (get-in nta/provenance
                               [:source/applicability :applicability/not-covered])
                       "。740,000円超（甲）と105,000円未満（乙）の端数処理も"
                       "印字されていないため転記していない")}
   :statute/kosei-nenkin
   {:source/title "厚生年金保険法 第八十一条第四項"
    :source/authority "日本国 / e-Gov 法令検索"
    ;; The same 法令 e-Gov id `payroll.shakai-hoken/sources` retrieved the
    ;; article text from — a statute is a document with an address, and a
    ;; source row without one is a citation the reader cannot follow.
    :source/url "https://laws.e-gov.go.jp/law/329AC0000000115"
    :source/law-id "329AC0000000115"
    :source/quote "平成二十九年九月以後の月分 千分の百八十三・〇〇"
    :source/read-at "2026-08-18"
    :source/note "payroll.shakai-hoken が e-Gov から取得した条文と同じ率"}})

;; ---------------------------------------------------------------------------
;; 保険料率
;; ---------------------------------------------------------------------------

(def insurance-rates
  "Every transcribed premium rate, as rows.

  `:rate/total` is the COMBINED rate and `:rate/employee` the employee's own
  share, both as exact ratios — never as decimals. 9.85% split in half is
  4.925%, which is not representable as a double and would print as
  0.049250000000000002 on one runtime; `payroll.shakai-hoken/
  pension-employee-share` records the same reason for the same choice.

  `:rate/employee` is written out rather than computed as `total/2` because
  雇用保険 is NOT half (徴収法 第三十一条第一項第一号), and a field that was
  usually a derivation would make that row look like an exception rather than
  like the different rule it is."
  [{:rate/scheme :scheme/health-insurance
    :rate/jurisdiction [:jp]
    :rate/prefecture "東京"
    :rate/effective-from "2026-04" :rate/effective-to "2027-03"
    :rate/total 985/10000
    :rate/employee 985/20000
    :rate/split :half
    :rate/split-provision "健康保険法 第百六十一条第一項"
    :rate/base :standard-remuneration-monthly
    :rate/source :kyokaikenpo/tokyo-2026
    :rate/label "健康保険料率（東京・介護保険第2号被保険者に該当しない場合）"}

   {:rate/scheme :scheme/long-term-care-insurance
    :rate/jurisdiction [:jp]
    :rate/prefecture "東京"
    :rate/effective-from "2026-04" :rate/effective-to "2027-03"
    :rate/total 162/10000
    :rate/employee 162/20000
    :rate/split :half
    :rate/split-provision "健康保険法 第百六十一条第一項"
    :rate/base :standard-remuneration-monthly
    :rate/applies-to :care-insurance-second-category
    :rate/source :kyokaikenpo/tokyo-2026
    :rate/label "介護保険料率（第2号被保険者のみ）"}

   {:rate/scheme :scheme/employees-pension
    :rate/jurisdiction [:jp]
    :rate/prefecture :all
    :rate/effective-from "2017-09" :rate/effective-to nil
    :rate/total 183/1000
    :rate/employee 183/2000
    :rate/split :half
    :rate/split-provision "厚生年金保険法 第八十二条第一項"
    :rate/base :standard-remuneration-monthly
    :rate/source :statute/kosei-nenkin
    :rate/label "厚生年金保険料率（条文に率がある唯一の制度）"}

   {:rate/scheme :scheme/employment-insurance
    :rate/jurisdiction [:jp]
    :rate/prefecture :all
    :rate/business :general
    :rate/effective-from "2026-04" :rate/effective-to "2027-03"
    ;; NOT half. 徴収法 第三十一条第一項第一号 gives the employee half of the
    ;; 雇用保険率 portion only; the 二事業率 portion is the employer's alone,
    ;; which is why 5 and 8.5 do not add to twice either of them.
    :rate/total 135/10000
    :rate/employee 5/1000
    :rate/employer 85/10000
    :rate/split :not-half
    :rate/split-provision "労働保険徴収法 第三十一条第一項第一号"
    :rate/base :wages
    :rate/source :mhlw/employment-insurance-2026
    :rate/label "雇用保険率（一般の事業・令和8年度）"}])

(def prefecture-key
  "Where an operator registers which 協会けんぽ 支部 applies.

  On the CONTRACT and not the employer, because 健保法 puts the 都道府県 with
  the 事業所 the 被保険者 is employed at, and one employer can have more than
  one."
  :employment/health-insurance-prefecture)

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn- in-window?
  "Is `month` (YYYY-MM) inside this row's window?

  String comparison, which is exactly chronological for a fixed-width
  YYYY-MM — `payroll.shakai-hoken/month-format` refuses any other shape for
  this reason, and this function relies on that refusal having happened."
  [{:rate/keys [effective-from effective-to]} month]
  (and (string? month)
       (>= (compare month effective-from) 0)
       (or (nil? effective-to) (<= (compare month effective-to) 0))))

(def rate-refusals
  #{:scheme-not-transcribed :prefecture-not-registered
    :prefecture-not-transcribed :month-not-covered :month-malformed})

(defn lookup
  "The rate row for one scheme, or a refusal naming which fact is missing.

    {:jurisdiction [:jp] :scheme … :month \"YYYY-MM\" :prefecture \"東京\"}

  Four different refusals and not one, because the operator's next action is
  different for each: register the 支部, wait for the table to be
  transcribed, fix the month, or accept that this scheme has no dataset."
  [{:keys [jurisdiction scheme month prefecture]}]
  (let [j (if (vector? jurisdiction) jurisdiction [jurisdiction])
        for-scheme (filterv #(and (= scheme (:rate/scheme %))
                                  (= j (:rate/jurisdiction %)))
                            insurance-rates)
        needs-prefecture? (some #(string? (:rate/prefecture %)) for-scheme)]
    (cond
      (empty? for-scheme)
      {:rate/status :refused :rate/answer :scheme-not-transcribed
       :rate/why (str "この法域のこの制度の料率表を、"
                      "この repository は転記していない")}

      (not (re-matches #"\d{4}-(0[1-9]|1[0-2])" (str month)))
      {:rate/status :refused :rate/answer :month-malformed
       :rate/why (str "対象月 " (pr-str month) " が YYYY-MM ではない。"
                      "料率は年度で変わるので、月が決まらなければ率も決まらない")}

      (and needs-prefecture? (str/blank? (str prefecture)))
      {:rate/status :refused :rate/answer :prefecture-not-registered
       :rate/why (str "健康保険料率は都道府県単位（健保法 第百六十条第一項）で"
                      "協会けんぽが定める。どの支部かが契約に登録されていない"
                      "（" prefecture-key "）。"
                      "未登録に東京を当てはめることはしない —— "
                      "もっともらしい額の給与明細が毎月少しずつ間違う")}

      :else
      (if-let [row (first (filterv (fn [r]
                                     (and (in-window? r month)
                                          (or (= :all (:rate/prefecture r))
                                              (= prefecture (:rate/prefecture r)))))
                                   for-scheme))]
        {:rate/status :ok :rate/row row :rate/source (get sources (:rate/source row))}
        (if (and needs-prefecture?
                 (not (some #(= prefecture (:rate/prefecture %)) for-scheme)))
          {:rate/status :refused :rate/answer :prefecture-not-transcribed
           :rate/why (str "支部「" prefecture "」の料率表は転記されていない。"
                          "転記済みなのは "
                          (pr-str (vec (sort (distinct (keep :rate/prefecture
                                                             for-scheme)))))
                          " のみ")}
          {:rate/status :refused :rate/answer :month-not-covered
           :rate/why (str month " に適用される行が無い。"
                          "転記済みの適用期間: "
                          (str/join "、" (for [r for-scheme]
                                           (str (:rate/effective-from r) "〜"
                                                (or (:rate/effective-to r) "（現在）")))))})))))

;; ---------------------------------------------------------------------------
;; The employee's share
;; ---------------------------------------------------------------------------

(def employee-share-rounding
  "How a fractional yen in the employee's share is resolved.

  **No default**, for `payroll.warimashi/rounding-policies`' reason and one
  more: the rule that IS commonly applied — 50銭以下切捨て・50銭を超える場合
  切上げ when the employer deducts from wages — rests on 通貨の単位及び貨幣の
  発行等に関する法律 第三条第一項 and on 労使協定 being able to specify
  otherwise, and **this repository has read neither**. Naming the shapes is
  not the same as having read the rule that picks one, so the employer
  registers which one their 労使協定 or practice uses.

  `payroll.shakai-hoken`'s `within-one-yen?` remains the check that needs NO
  rounding rule at all, and it is unchanged: whatever policy is registered,
  the result is within a yen of the exact ratio, so the two never disagree."
  {:round/floor-at-half
   {:policy/label "50銭以下切捨て・50銭を超えるとき切上げ（事業主が控除する場合の通例）"
    :policy/apply (fn [n] (let [i (int n) f (- n i)]
                            (if (> f 1/2) (inc i) i)))}
   :round/half-up
   {:policy/label "50銭未満切捨て・50銭以上切上げ（被保険者が事業主へ支払う場合の通例）"
    :policy/apply (fn [n] (let [i (int n) f (- n i)]
                            (if (>= f 1/2) (inc i) i)))}
   :round/floor {:policy/label "円未満切捨て"
                 :policy/apply (fn [n] (int n))}
   :round/none {:policy/label "丸めない（厳密な有理数）"
                :policy/apply identity}})

(defn employee-share
  "The employee's own premium for one scheme and month, or a refusal.

    {:jurisdiction :scheme :month :prefecture
     :base    標準報酬月額 for the three monthly schemes, 賃金 for 雇用保険
     :rounding one of `employee-share-rounding`'s keys}

  Returns `{:share/status :ok :share/exact ratio :share/yen n …}`.

  `:share/exact` is a RATIO and `:share/yen` is that ratio under the
  registered policy. Both are reported, because the exact value is what
  `payroll.shakai-hoken/within-one-yen?` bounds and the yen figure is what a
  payslip prints, and a caller given only one of them cannot check the
  other."
  [{:keys [base rounding] :as q}]
  (let [r (lookup q)
        policy (get employee-share-rounding rounding)]
    (cond
      (= :refused (:rate/status r)) r

      (not (and (number? base) (not (neg? base)) (zero? (mod base 1))))
      {:share/status :refused :share/answer :base-not-registered
       :share/why (str "算定の基礎（"
                       (name (:rate/base (:rate/row r)))
                       "）が非負の円単位整数として登録されていない: "
                       (pr-str base))}

      (nil? policy)
      {:share/status :refused :share/answer :rounding-policy-not-registered
       :share/why (str "端数処理の規則が登録されていない（" (pr-str rounding) "）。"
                       "被保険者負担分の端数処理は通貨法と労使協定に依存し、"
                       "この repository はどちらも読んでいないので既定を持たない。"
                       "登録できるのは "
                       (pr-str (vec (sort (keys employee-share-rounding)))))}

      :else
      (let [row (:rate/row r)
            exact (* base (:rate/employee row))]
        {:share/status :ok
         :share/scheme (:rate/scheme row)
         :share/label (:rate/label row)
         :share/base base
         :share/base-kind (:rate/base row)
         :share/rate (:rate/employee row)
         :share/total-rate (:rate/total row)
         :share/split (:rate/split row)
         :share/split-provision (:rate/split-provision row)
         :share/exact exact
         :share/yen ((:policy/apply policy) exact)
         :share/rounding {:rounding/policy rounding
                          :rounding/label (:policy/label policy)
                          :rounding/registered? true
                          :rounding/not-read
                          (str "端数処理の根拠（通貨法 第三条第一項・労使協定）を"
                               "この repository は読んでいない")}
         :share/effective (str (:rate/effective-from row) "〜"
                               (or (:rate/effective-to row) "（現在）"))
         :share/source (:rate/source r)
         :share/why (str (:rate/label row) "（"
                         (:rate/effective-from row) "〜）× "
                         (name (:rate/base row)) " " base
                         "。合計料率ではなく被保険者負担分")}))))


;; ---------------------------------------------------------------------------
;; 源泉徴収税額表 — the contract, and the rows that now fill it
;; ---------------------------------------------------------------------------

(def withholding-table
  "The 2026 月額表, wired to the importer's output.

  Every figure comes from `payroll.rates.monthly-2026`, which was GENERATED
  from the 国税庁 workbook pinned by `:table/provenance`'s SHA-256. Nothing
  in this map is typed: the bands, the nine threshold rows, the excess-rate
  segments and the 7人超 deduction are all read through, so a regeneration
  that changes a figure changes it here without this namespace being
  touched.

  ## What is transcribed is the 月額表, and only the 月額表

  `:table/not-transcribed` names the three things this table still cannot
  answer — 日額表, 賞与に対する源泉徴収税額の算出率の表, and the 端数処理 for
  the fraction of a yen the excess-rate tail produces. `withhold` refuses
  each of them by name rather than approximating one table from another.

  ## The shape carries its own edges

  `:band/to` is 未満 (exclusive), which is the workbook's convention: band
  231 ends at 740,000 and 740,000 itself is the first `:table/thresholds`
  row. `lookup-band` implements that edge and `payroll.phase2-test` walks
  every boundary in the table asserting the two do not overlap and do not
  leave a gap."
  {:table/id :nta/monthly-2026
   :table/label "令和8年分 給与所得の源泉徴収税額表（月額表）"
   :table/jurisdiction [:jp]
   :table/effective-from "2026-01" :table/effective-to "2026-12"
   :table/columns
   [{:column/key :kou :column/label "甲欄"
     :column/requires [:dependants]
     :column/when "扶養控除等申告書の提出がある"}
    {:column/key :otsu :column/label "乙欄"
     :column/requires []
     :column/when "扶養控除等申告書の提出が無い"}
    {:column/key :hei :column/label "丙欄"
     :column/requires []
     :column/when "日雇賃金（日額表の丙欄）。月額表には無い"}]
   :table/inputs
   [{:input/key :taxable-remuneration
     :input/label "その月の社会保険料等控除後の給与等の金額"
     :input/why "表の行を決める。総支給額ではない"}
    {:input/key :column :input/label "税額表の欄"
     :input/why "甲/乙。提出の有無は登録された事実である"}
    {:input/key :dependants :input/label "扶養親族等の数"
     :input/why "甲欄の列を決める。0人も登録値であって未登録ではない"}]
   :table/sub-minimum nta/sub-minimum
   :table/bands nta/bands
   :table/thresholds nta/thresholds
   :table/kou-segments nta/kou-segments
   :table/otsu-segments nta/otsu-segments
   :table/dependant-columns 8
   :table/dependants-beyond-7-deduction nta/dependants-beyond-7-deduction
   :table/source :nta/withholding-2026
   :table/provenance nta/provenance
   :table/transcribed? true
   :table/transcribed-by "tools/import_nta_2026.clj"
   :table/not-transcribed
   [{:gap/what "日額表"
     :gap/why "同じ publication の別表。月額表から日割りで作らない"}
    {:gap/what "賞与に対する源泉徴収税額の算出率の表"
     :gap/why "前月の給与から率を引く別の表であり、月額表には無い"}
    {:gap/what "740,000円超（甲）・105,000円未満（乙）の端数処理"
     :gap/why (str "workbook はそこで金額ではなく率を印字する。"
                   "率が生む1円未満をどう処理するかは印字されていないので、"
                   "withhold は厳密な有理数を返して丸めを拒否する")}]
   :table/why
   (str "月額表の帯・しきい値・超過税率の段は "
        "tools/import_nta_2026.clj が workbook から読み出したものであり、"
        "誰かが打ち込んだものではない。"
        "読めていない表と端数処理は :table/not-transcribed に名前で残る")})

(def withholding-refusals
  #{:table-not-transcribed :band-not-transcribed :column-not-registered
    :dependants-not-registered :year-not-covered :remuneration-not-registered
    :rounding-not-transcribed})

(defn lookup-band
  "The band containing `amount`, or nil.

  `:band/from` is 以上 and `:band/to` is 未満, so the comparison on the upper
  edge is STRICT. Writing it as `<=` would make 740,000円 both the last band
  and the first threshold row, and the two print different amounts."
  [table amount]
  (first (filter (fn [b]
                   (and (>= amount (:band/from b))
                        (or (nil? (:band/to b)) (< amount (:band/to b)))))
                 (:table/bands table))))

(defn- dependant-adjust
  "税額 for `dependants`, given the eight printed columns.

  0〜7 は印字された値そのもの。8人以上は「扶養親族等の数が７人の場合の税額
  から、その７人を超える１人ごとに1,610円を控除した金額」。

  The floor at zero is THIS repository's reading and not a sentence in the
  workbook: the 月額表 states the subtraction and does not state what happens
  when it exceeds the tax, and a negative 源泉徴収税額 would be a refund the
  月額表 does not provide for. It is written here, once, rather than at each
  call site, so that a reader looking for the rule finds one place."
  [kou-amounts dependants deduction]
  (if (<= dependants 7)
    (nth kou-amounts dependants)
    (max 0 (- (nth kou-amounts 7)
              (* deduction (- dependants 7))))))

(defn- segment-for
  [segments amount]
  (first (filter (fn [s]
                   (and (>= amount (:segment/from s))
                        (or (nil? (:segment/to s)) (< amount (:segment/to s)))))
                 segments)))

(defn withhold
  "The month's 源泉徴収税額, or a deterministic refusal.

    {:month \"YYYY-MM\" :taxable-remuneration n :column :kou|:otsu
     :dependants n}

  Every input is checked BEFORE the table is consulted, and each missing one
  is its own refusal — so an operator whose deployment is missing the 欄 AND
  the 扶養親族等の数 is told about both rather than about whichever the code
  happened to reach first. Those checks are unchanged from when the table
  had no rows in it.

  ## Where an amount is answerable, and where it is not

  | input | answer |
  |---|---|
  | 甲, 105,000円未満 | `0` — printed, and an answer rather than an absence |
  | 甲/乙, 105,000〜739,999円 | the band's printed amount |
  | 甲/乙, a printed threshold | that row's printed amount |
  | 甲, 740,000円超 | REFUSED `:rounding-not-transcribed` |
  | 乙, 105,000円未満 | REFUSED `:rounding-not-transcribed` |

  The two refusals are the two places the workbook prints a RATE instead of
  an amount. The exact value is computable and is returned as
  `:withhold/exact` — an exact ratio, never a double — but the 端数処理 that
  turns it into yen is not printed anywhere in the publication this
  repository read, so `withhold` reports the arithmetic and refuses the
  rounding.

  **The refusal is per SEGMENT and not per value.** 3.063% of 100,000円 is
  exactly 3,063円 and needs no rounding rule; it is refused anyway, because
  answering the amounts that happen to divide evenly and refusing the rest
  is the same failure as a partially transcribed table — the operator learns
  the calculator works and meets the refusal on a payday.

  `:withhold/exact` on those refusals already has the 7人超 deduction
  applied and is floored at zero, so a caller that later registers a
  rounding policy has the whole arithmetic and not a piece of it."
  [{:keys [month taxable-remuneration column dependants]}]
  (let [t withholding-table]
    (cond
      (not (re-matches #"\d{4}-(0[1-9]|1[0-2])" (str month)))
      {:withhold/status :refused :withhold/answer :year-not-covered
       :withhold/why (str "対象月 " (pr-str month) " が YYYY-MM ではない")}

      (not (and (>= (compare month (:table/effective-from t)) 0)
                (<= (compare month (:table/effective-to t)) 0)))
      {:withhold/status :refused :withhold/answer :year-not-covered
       :withhold/why (str month " に適用される税額表を、"
                          "この repository は持っていない。"
                          "持っているのは " (:table/label t)
                          "（" (:table/effective-from t) "〜"
                          (:table/effective-to t) "）だけであり、"
                          "隣の年の表で代用することはしない")}

      (not (and (number? taxable-remuneration)
                (not (neg? taxable-remuneration))))
      {:withhold/status :refused :withhold/answer :remuneration-not-registered
       :withhold/why (str "社会保険料等控除後の給与等の金額が登録されていない。"
                          "総支給額ではなく、控除後の額が表の行を決める")}

      (not (contains? #{:kou :otsu} column))
      {:withhold/status :refused :withhold/answer :column-not-registered
       :withhold/why (str "税額表の欄（甲/乙）が登録されていない。"
                          "扶養控除等申告書の提出の有無は"
                          "software が観測できない事実であり、"
                          "未登録は「乙」ではない")}

      (and (= :kou column) (not (and (integer? dependants) (not (neg? dependants)))))
      {:withhold/status :refused :withhold/answer :dependants-not-registered
       :withhold/why (str "甲欄は扶養親族等の数で列が決まるが、"
                          "登録されていない。0人は登録値であって未登録ではない")}

      ;; Kept although it cannot fire today. Un-transcribing the table is a
      ;; DATA change — `:table/transcribed? false` — and this branch is what
      ;; makes that change safe instead of making every lookup answer from an
      ;; empty vector.
      (not (:table/transcribed? t))
      {:withhold/status :refused :withhold/answer :table-not-transcribed
       :withhold/table (:table/label t)
       :withhold/source (get sources (:table/source t))
       :withhold/why (:table/why t)}

      :else
      (let [amount taxable-remuneration
            deduction (:table/dependants-beyond-7-deduction t)
            sub (:table/sub-minimum t)
            base {:withhold/table (:table/label t)
                  :withhold/column column
                  :withhold/remuneration amount
                  :withhold/source (get sources (:table/source t))
                  :withhold/provenance (:table/provenance t)}
            threshold (first (filter #(= amount (:threshold/at %))
                                     (:table/thresholds t)))
            band (lookup-band t amount)
            refuse-rounding
            (fn [exact basis]
              (merge base
                     {:withhold/status :refused
                      :withhold/answer :rounding-not-transcribed
                      :withhold/exact exact
                      :withhold/basis basis
                      :withhold/why
                      (str "この区間で workbook が印字しているのは金額ではなく"
                           "率であり（" basis "）、"
                           "率が生む1円未満の端数をどう処理するかは"
                           "印字されていない。"
                           "厳密な値は :withhold/exact にある —— "
                           "丸めた額をこの repository が発明することはしない")}))]
        (cond
          ;; 甲欄・105,000円未満。The workbook prints 0, for every dependant
          ;; count, and 0 is an ANSWER.
          (and (= :kou column) (< amount (:band/to sub)))
          (merge base {:withhold/status :ok
                       :withhold/yen (dependant-adjust (:band/kou sub)
                                                       dependants deduction)
                       :withhold/dependants dependants
                       :withhold/row {:row/kind :sub-minimum
                                      :row/from (:band/from sub)
                                      :row/to (:band/to sub)}
                       :withhold/why (str "105,000円未満の甲欄は"
                                          "扶養親族等の数にかかわらず0円と"
                                          "印字されている。"
                                          "答えが0であって、答えが無いのではない")})

          ;; 乙欄・105,000円未満 — a rate, not an amount.
          (= :kou column)
          (if band
            (merge base {:withhold/status :ok
                         :withhold/yen (dependant-adjust (:band/kou band)
                                                         dependants deduction)
                         :withhold/dependants dependants
                         :withhold/row (assoc band :row/kind :band)
                         :withhold/why (str (:band/from band) "円以上 "
                                            (:band/to band) "円未満の行、"
                                            "扶養親族等 " dependants "人")})
            (if threshold
              (merge base {:withhold/status :ok
                           :withhold/yen (dependant-adjust
                                          (:threshold/kou threshold)
                                          dependants deduction)
                           :withhold/dependants dependants
                           :withhold/row (assoc threshold :row/kind :threshold)
                           :withhold/why (str amount "円は表に金額が"
                                              "印字されている額である")})
              (if-let [seg (segment-for (:table/kou-segments t) amount)]
                (refuse-rounding
                 (max 0 (- (+ (nth (:segment/base seg) (min dependants 7))
                              (* (:segment/rate seg)
                                 (- amount (:segment/from seg))))
                           (* deduction (max 0 (- dependants 7)))))
                 (:segment/basis seg))
                (merge base
                       {:withhold/status :refused
                        :withhold/answer :band-not-transcribed
                        :withhold/why (str "金額 " amount
                                           " を含む行が転記されていない")}))))

          ;; 乙欄
          :else
          (cond
            (< amount (:band/to sub))
            (refuse-rounding (* amount (:band/otsu-rate sub))
                             (:band/otsu-basis sub))

            band
            (merge base {:withhold/status :ok
                         :withhold/yen (:band/otsu band)
                         :withhold/row (assoc band :row/kind :band)
                         :withhold/why (str (:band/from band) "円以上 "
                                            (:band/to band) "円未満の行の乙欄")})

            (and threshold (some? (:threshold/otsu threshold)))
            (merge base {:withhold/status :ok
                         :withhold/yen (:threshold/otsu threshold)
                         :withhold/row (assoc threshold :row/kind :threshold)
                         :withhold/why (str amount "円は乙欄にも金額が"
                                            "印字されている額である")})

            :else
            (if-let [seg (segment-for (:table/otsu-segments t) amount)]
              (refuse-rounding
               (+ (:segment/base seg)
                  (* (:segment/rate seg) (- amount (:segment/from seg))))
               (:segment/basis seg))
              (merge base
                     {:withhold/status :refused
                      :withhold/answer :band-not-transcribed
                      :withhold/why (str "金額 " amount
                                         " を含む行が転記されていない")}))))))))
