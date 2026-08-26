(ns payroll.artifact.gensen
  "源泉徴収票・給与支払報告書・法定調書合計表 — generated data artifacts, and
  the amount that is still refused.

  `docs/maturity.md`'s G6 says: *nothing here produces 源泉徴収票,
  給与支払報告書, 法定調書合計表 or the 納付書. A December cutover without
  these is a cutover into a month this system cannot complete.* Three of the
  four now have a data contract, a completeness check and a preview. The
  納付書 does not, because its 様式 has not been read.

  ## These are data, not filings

  Nothing here submits anything. `:artifact/statutory-form? false` is on
  every one and there is no code path that sets it true — the same rule
  `payroll.ui.views/artifacts` keeps for the four older artifacts, and for
  the same reason: an operator scanning a list must not come away thinking
  one of them is a filing. The 様式 (form layouts) published by 国税庁 have
  not been read; what has been read is what the documents must CARRY.

    国税庁「令和8年分 給与所得の源泉徴収票等の法定調書の作成と提出の手引」
    https://www.nta.go.jp/publication/pamph/hotei/tebiki2026/index.htm

  **Reading the guide made that flag MORE true, not less.** 第1章 4(4) says
  the 様式 changed in 令和8年9月 with the 国税システム's renewal, so what was
  「読んでいない様式」 is now 「読んでいない、しかも新しい様式」 — a repository
  that had transcribed the old layout would today be emitting a stale one and
  calling it statutory. `amendments-2026` carries the fact; `source` carries
  exactly which pages were opened, because 「この手引を読んだ」 and
  「この手引の第1章を読んだ」 are different claims and only the second is true.

  ## The amount is refused, and the refusal is the deliverable

  年末調整 settles the year's over- or under-withholding against the final
  payment (所得税法 第百九十条). The year's correct tax comes from 別表第五
  （年末調整等のための給与所得控除後の給与等の金額の表）and the 所得税額の
  速算表, and **this repository has read neither** — so `year-end-amount`
  refuses, deterministically, naming the table it does not have.

  ### The 月額表 being transcribed does not make this answerable

  `payroll.rates/withholding-table` now holds all 231 bands of the 月額表.
  That is a DIFFERENT table and it must not be borrowed here. The 月額表
  answers *what to withhold from one month's pay*; 年末調整 answers *what
  the year's tax actually was*, which needs 給与所得控除, the 所得控除 the
  employee declared, and a progressive 速算表 — none of which the monthly
  bands contain.

  Feeding a year's gross into the 月額表 produces a number. It is a plausible
  number, it is not the year's tax, and it would appear on a 源泉徴収票 next
  to a 過不足額 an employer would actually pay or recover. So the refusal
  here is on the tables that were not read and does not consult the monthly
  one at all — `payroll.phase2-test` walks a range of grosses and dependant
  counts either side of the monthly table's floor and ceiling and asserts
  that not one of them returns a number.

  `payroll.nenmatsu` already answers WHETHER an adjustment is owed. This adds
  the calculator BOUNDARY: a versioned input contract, a single refusal, and
  a preview that shows every figure the artifact would carry with the tax
  figures marked `:unknown` rather than blank. What it does not add is a
  number.

  ## My Number and address are required at the SUBMISSION boundary only

  Two boundaries, deliberately:

    preview   — for the operator to check the figures. Carries NO My Number
                and NO address, and refuses to include them even if
                registered.
    submission-export
              — what would be handed to a 税務署 or a 区市町村. REFUSES
                without 個人番号 and 住所, because a 法定調書 without them is
                not one.

  A single artifact that carried them always would put a My Number on every
  screen an operator looks at. `payroll.sensitive` is the vocabulary and
  `payroll.projection.schema` uses the same one to keep both out of R2."
  (:require [clojure.string :as str]
            [payroll.artifact.text :as text]
            [payroll.provenance :as prov]
            [payroll.rates :as rates]
            [payroll.sensitive :as sensitive]))

(def source
  "The guide, and exactly how much of it has been read.

  `:source/read` is a list of what was actually opened, and it exists because
  「この手引を読んだ」 and 「この手引の第1章を読んだ」 are different claims and
  only the second one is true. A `:source/url` with a `:source/read-at` beside
  it reads as the first. The PDF is NOT vendored into this repository — its
  URL and its SHA-256 are recorded so that a later reader can fetch the same
  bytes and check they got the same document, which is what a citation is
  for."
  {:source/title "令和8年分 給与所得の源泉徴収票等の法定調書の作成と提出の手引"
   :source/authority "国税庁"
   :source/edition "令和8年分"
   :source/url "https://www.nta.go.jp/publication/pamph/hotei/tebiki2026/index.htm"
   :source/read-at "2026-08-26"
   :source/read
   [{:read/what "索引ページ（目次）"
     :read/url "https://www.nta.go.jp/publication/pamph/hotei/tebiki2026/index.htm"
     :read/at "2026-08-26"
     :read/note (str "第1章から第9章までの章立てとページ範囲、"
                     "および参考・特例のページ。"
                     "目次には 別表第五 も 速算表 も無い")}
    {:read/what "第1章 法定調書の提出期限等について（1〜2ページ）"
     :read/url "https://www.nta.go.jp/publication/pamph/hotei/tebiki2026/PDF/01.pdf"
     :read/sha256 "45640296d54a1a4d17e6dc7bc4fa09b211e2dc08c30ac4795a5fd7fcbc6b5b4c"
     :read/bytes 856461
     :read/at "2026-08-26"
     :read/note (str "提出期限・提出先・四つの提出方法・"
                     "e-Tax 等による提出義務の30枚基準・"
                     "令和8年分から適用される主な改正事項。"
                     "この repository は PDF を取り込んでいない —— "
                     "URL と SHA-256 だけを記録する")}]
   :source/not-read ["第2章 給与所得の源泉徴収票（給与支払報告書）の様式と記載要領"
                     "第3章 退職所得の源泉徴収票・特別徴収票の様式と記載要領"
                     "第4章 報酬、料金、契約金及び賞金の支払調書の様式と記載要領"
                     "第5章 不動産の使用料等の支払調書の様式と記載要領"
                     "第6章 不動産等の譲受けの対価の支払調書の様式と記載要領"
                     "第7章 不動産等の売買又は貸付けのあっせん手数料の支払調書の様式と記載要領"
                     "第8章 法定調書合計表の書き方"
                     "第9章 法定調書の訂正・追加について"
                     "各法定調書の様式（レイアウト）" "納付書の様式"
                     "源泉徴収税額表（別表）"]})

(def submission-rules
  "提出期限・提出先・提出方法 — 第1章 から、読んだとおりに。

  Recorded and not acted on. Nothing here submits anything, and nothing here
  resolves a date: 令和9年2月1日 is what the guide PRINTS, and this actor does
  not re-derive it from 1月31日 because it holds no calendar and cannot say
  which day of the week a date falls on."
  [{:rule/key :deadline
    :rule/label "提出期限"
    :rule/text (str "この手引で示す法定調書は、令和９年２月１日（月）までに"
                    "所轄税務署に提出しなければなりません"
                    "（給与支払報告書と退職所得の特別徴収票の提出先は、"
                    "各市区町村となります。）")
    :rule/chapter "第1章 法定調書の提出期限等について（1〜2ページ）"
    :rule/why (str "令和9年2月1日は手引が印字している日付である。"
                   "この actor は暦を持たないので、"
                   "1月31日が土曜であることからこの日を導き直したりはしない")}
   {:rule/key :destination
    :rule/label "提出先"
    :rule/text (str "所轄税務署。ただし給与支払報告書と"
                    "退職所得の特別徴収票の提出先は各市区町村")
    :rule/chapter "第1章"
    :rule/why "一つの年分の書類が二つの役所に分かれて出ていく"}
   {:rule/key :methods
    :rule/label "提出方法は四つ"
    :rule/text "①e-Tax ②認定クラウド等 ③光ディスク等（CD・DVD など）④書面"
    :rule/chapter "第1章"
    :rule/why "この repository はそのいずれの経路も持たない"}
   {:rule/key :optical-disc-format
    :rule/label "光ディスク等の形式"
    :rule/text (str "CSV 形式で作成し、ファイル拡張子は「.txt」、"
                    "圧縮する場合は「.zip」")
    :rule/chapter "第1章"
    :rule/why (str "拡張子まで指定されている。"
                   "様式（レイアウト）を読んでいないので、"
                   "この repository はその CSV を組み立てられない")}
   {:rule/key :e-tax-threshold
    :rule/label "e-Tax 等による提出義務の基準"
    :rule/text (str "前々年（基準年）に提出すべきであった法定調書の枚数が"
                    "30枚以上である法定調書については、"
                    "e-Tax 等による提出が必要です。"
                    "なお、提出義務の判定は法定調書の種類ごとに"
                    "行いますのでご注意ください。")
    :rule/chapter "第1章"
    :rule/why (str "令和９年１月以降に提出する分から、"
                   "基準が「100枚以上」から「30枚以上」に引き下げられている。"
                   "判定は種類ごとなので、"
                   "全体の枚数を数えて一つの答えを出すことはできない")}])

(def amendments-2026
  "令和８年分の法定調書の提出から適用される主な改正事項 — 第1章 から四つ。

  Two of these change what a COMPLETE submission is, which is a field-
  readiness fact and not a piece of trivia; the other two are recorded
  because a reader of this namespace should not have to open the PDF to find
  out that the 様式 moved under it."
  [{:amendment/key :deemed-submission
    :amendment/label "源泉徴収票のみなし提出の特例"
    :amendment/text (str "令和９年１月１日以降、市区町村に給与支払報告書を"
                         "提出した場合には、税務署に給与所得の源泉徴収票を"
                         "提出したものとみなされるため、令和８年分以降は"
                         "税務署提出用の給与所得の源泉徴収票を作成・提出する"
                         "必要がなくなります")
    :amendment/chapter "第1章 4(1)"
    :amendment/changes-completeness? true
    :amendment/why (str "「何を出せば出したことになるか」が変わる。"
                        "この repository が提出できるようになるわけではない")}
   {:amendment/key :life-insurance-deduction-under-23
    :amendment/label "年齢23歳未満の扶養親族を有する場合の生命保険料控除の特例"
    :amendment/text (str "令和７年度税制改正により、"
                         "年齢23歳未満の扶養親族を有する場合の"
                         "生命保険料控除の特例が創設された"
                         "（子育て世帯に対する生命保険料控除の拡充）。"
                         "令和８年分及び令和９年分の所得税について適用される")
    :amendment/chapter "第1章 4(2)"
    :amendment/changes-completeness? false
    :amendment/why (str "所得控除が一つ増えている。"
                        "この actor はこの特例を符号化していないので、"
                        "年税額の表を転記しただけでは年末調整はまだ計算できない"
                        "（annual-tables 参照）")}
   {:amendment/key :retirement-income-statement
    :amendment/label "退職所得の源泉徴収票の提出範囲"
    :amendment/text (str "退職所得の源泉徴収票の提出省略範囲を定める規定が廃止され、"
                         "すべての居住者に対して支払う退職手当等に係る"
                         "退職所得の源泉徴収票について税務署に提出しなければ"
                         "ならないこととされた。eLTAX による提出方法が"
                         "整備されるまでの当面の間、特別徴収票の市区町村への"
                         "提出が不要。【番号】欄が創設された")
    :amendment/chapter "第1章 4(3)"
    :amendment/changes-completeness? false
    :amendment/why (str "退職手当等についての法定調書であり、"
                        "この repository は退職所得を扱わない。"
                        "住民税の特別徴収税額の決定通知書とは別の文書である"
                        "（payroll.juminzei の注記を参照）")}
   {:amendment/key :form-layout-changed
    :amendment/label "書面提出時の法定調書の様式変更"
    :amendment/text (str "令和８年９月の国税システムの更改に伴い、"
                         "書面で税務署に提出する際の法定調書の様式が"
                         "変更されている")
    :amendment/chapter "第1章 4(4)"
    :amendment/changes-completeness? false
    :amendment/why (str "様式は読んでいなかったが、"
                        "いまは「読んでいない新しい様式」がある。"
                        "artifact/statutory-form? が false である理由は"
                        "この改正で強くなった —— 弱くはならない")}])

(def artifacts
  "The three, and what a COMPLETE submission of each one now is.

  `:artifact/deemed-submission` is 第1章 4(1) landing on this list. It does
  not make this repository able to submit anything — every
  `:artifact/statutory-form?` here is still false and no code path sets it
  true — but it changes what a complete submission IS, and that is a
  field-readiness fact rather than a piece of trivia: an operator who filed
  the 給与支払報告書 with the 市区町村 and then went looking for the
  税務署提出用 源泉徴収票 is looking for a document 令和8年分以降 does not
  need."
  [{:artifact/key :gensen-choshu-hyo
    :artifact/label "源泉徴収票"
    :artifact/statutory-form? false
    :artifact/what "1 従業員・1 年分。本人交付と税務署提出で内容は同じ"
    :artifact/why-not-statutory (str "様式（レイアウト）を読んでいない。項目だけを持つ。"
                                     "しかもその様式は令和8年9月の"
                                     "国税システム更改で変更されている"
                                     "（手引 第1章 4(4)）")
    :artifact/deemed-submission
    {:deemed/by :kyuyo-shiharai-hokokusho
     :deemed/from "令和9年1月1日"
     :deemed/applies-to "令和8年分以降"
     :deemed/what (str "市区町村に給与支払報告書を提出した場合には、"
                       "税務署に給与所得の源泉徴収票を提出したものと"
                       "みなされるため、令和8年分以降は税務署提出用の"
                       "給与所得の源泉徴収票を作成・提出する必要が"
                       "なくなります")
     :deemed/source "手引 第1章 4(1) 源泉徴収票のみなし提出の特例"
     :deemed/why (str "本人交付の義務は残る —— 消えたのは税務署提出用の一枚であって、"
                      "従業員に渡す源泉徴収票ではない。"
                      "この repository はどちらも提出しない")}}
   {:artifact/key :kyuyo-shiharai-hokokusho
    :artifact/label "給与支払報告書（個人別明細書）"
    :artifact/statutory-form? false
    :artifact/what "1 従業員・1 年分。1月31日までに 区市町村 へ提出するもの"
    :artifact/why-not-statutory (str "同上。提出も eLTAX 連携も行わない。"
                                     "様式は令和8年9月に変更されている"
                                     "（手引 第1章 4(4)）")
    :artifact/deems :gensen-choshu-hyo
    :artifact/deems-why (str "令和9年1月1日以降、これを市区町村に提出すれば"
                             "税務署提出用の源泉徴収票を提出したものと"
                             "みなされる（手引 第1章 4(1)）。"
                             "つまりこの一枚が、以前は二つの役所に"
                             "分かれていた提出を担う")}
   {:artifact/key :hotei-chosho-gokei
    :artifact/label "法定調書合計表"
    :artifact/statutory-form? false
    :artifact/what "1 事業主・1 年分の集計"
    :artifact/why-not-statutory (str "同上。書き方は手引 第8章 にあるが、"
                                     "その章は読んでいない")}])

(def declaration-fields
  "What the employee must have declared before any of these can be complete.

  Absent is its own answer everywhere in this repository and it is here too:
  an unregistered 扶養親族等の数 is not zero."
  [{:field/key :employment/year-end-declaration-filed?
    :field/label "扶養控除等申告書の提出" :field/admits boolean?}
   {:field/key :employment/dependants
    :field/label "扶養親族等の数" :field/admits #(and (integer? %) (not (neg? %)))}
   {:field/key :employment/withholding-column
    :field/label "税額表の欄（甲/乙）" :field/admits #{:kou :otsu}}])

(def submission-fields
  "What a SUBMISSION additionally needs. Never in a preview."
  [{:field/key :employment/my-number :field/label "個人番号（マイナンバー）"
    :field/admits #(and (string? %) (re-matches #"\d{12}" %))
    :field/why "法定調書には個人番号の記載が要る"}
   {:field/key :employment/address :field/label "住所"
    :field/admits #(and (string? %) (not (str/blank? %)))
    :field/why "給与支払報告書の提出先は1月1日時点の住所地の区市町村である"}
   {:field/key :employer/corporate-number :field/label "法人番号"
    :field/admits #(or (nil? %) (and (string? %) (re-matches #"\d{13}" %)))
    :field/why "事業主側。未登録は拒否しないが、記載欄は空になる"}])

;; ---------------------------------------------------------------------------
;; Year-to-date facts, from this actor's own committed records
;; ---------------------------------------------------------------------------

(defn year-to-date
  "What this actor committed for one contract in one year.

  Same limit `payroll.nenmatsu` states and for the same reason: wages paid
  before this actor was deployed, or through another system, are not here and
  cannot be seen from here. `:ytd/this-actors-records-only? true` travels on
  every result."
  [{:keys [records contract-id year]}]
  (let [runs (filterv (fn [r]
                        (and (= :draft-payroll-run (:op r))
                             (= contract-id (:contract-id r))
                             (str/starts-with? (str (get-in r [:payload :period]))
                                               (str year))))
                      records)
        sum (fn [k] (reduce + 0 (keep #(get-in % [:payload k]) runs)))
        missing (fn [k] (count (remove #(number? (get-in % [:payload k])) runs)))]
    {:ytd/contract-id contract-id
     :ytd/year (str year)
     :ytd/runs (count runs)
     :ytd/gross (sum :gross)
     :ytd/income-tax-withheld (sum :income-tax-withheld)
     :ytd/social-insurance
     (reduce + 0 (for [k [:health-insurance-withheld :care-insurance-withheld
                          :employees-pension-withheld :employment-insurance-withheld]]
                   (sum k)))
     :ytd/runs-missing-a-figure
     (into {} (for [k [:gross :income-tax-withheld :health-insurance-withheld
                       :care-insurance-withheld :employees-pension-withheld
                       :employment-insurance-withheld]
                    :let [n (missing k)] :when (pos? n)]
                [k n]))
     :ytd/complete? (and (pos? (count runs))
                         (every? zero? (for [k [:gross :income-tax-withheld]]
                                         (missing k))))
     :ytd/this-actors-records-only? true
     :ytd/why (str "この actor が commit した run " (count runs) " 件の合計。"
                   "この actor の配備前に支払われた給与や、"
                   "他システムを通した支払はここに無く、"
                   "ここからは見えない")}))

;; ---------------------------------------------------------------------------
;; The amount — the versioned calculator boundary
;; ---------------------------------------------------------------------------

(def annual-tables
  "The two tables 年末調整 needs, and neither of them is the 月額表.

  Held as data so that reading one is a change HERE and not a change to the
  branch in `year-end-amount` — and so that a reader can see at a glance
  that the monthly table, which IS transcribed, is not on this list."
  [{:table/label "年末調整等のための給与所得控除後の給与等の金額の表（別表第五）"
    :table/why (str "その年の給与総額から給与所得控除後の金額を出す表。"
                    "月額表はこれを含まない。"
                    ;; measured 2026-08-26, reading the guide's own 目次
                    "令和8年分の法定調書の手引の目次には 別表第五 も 速算表 も"
                    "載っていないので、この手引はそれが見つかる場所ではない —— "
                    "読むべき文書は別にある。"
                    ;; measured 2026-08-26, 第1章 4(2)
                    "さらに令和7年度税制改正で、年齢23歳未満の扶養親族を有する"
                    "場合の生命保険料控除の特例が創設されており"
                    "（令和8年分・令和9年分に適用）、"
                    "この actor はそれを符号化していない —— "
                    "表を転記しただけでは足りない")
    :table/read? false}
   {:table/label "所得税額の速算表"
    :table/why (str "課税所得金額に累進税率を当てて年税額を出す表。"
                    "月額表の帯は1か月分の徴収額であって年税額ではない。"
                    "この表も法定調書の手引の目次には無い —— "
                    "手引を読んだことは、この表を読んだことにはならない。"
                    "そして令和8年分から適用される生命保険料控除の特例"
                    "（23歳未満の扶養親族）を符号化していない以上、"
                    "課税所得そのものがまだ出せない")
    :table/read? false}])

(defn year-end-amount
  "The year's tax and the over/under, or a deterministic refusal.

  Every input is checked first and each missing one is its own refusal; the
  annual tables are consulted last and always refuse today, because neither
  has been read. Reading one is a change to `annual-tables` — this function
  branches on `:table/read?` and is unchanged by it.

  ## It does not consult the 月額表, on purpose

  The monthly table is fully transcribed and would happily return a band for
  a year's gross. That number is not the year's tax (see the namespace
  docstring), so this function never asks it. A version of this that fell
  through to `payroll.rates/withhold` would have started answering the moment
  the monthly bands landed, with nothing in the diff that said so.

  `payroll.phase2-test/no-input-produces-a-year-end-amount` walks a range of
  remunerations and dependant counts and asserts that not one of them returns
  a number."
  [{:keys [contract ytd]}]
  (let [missing (vec (for [{:field/keys [key label admits]} declaration-fields
                           :when (not (admits (get contract key)))]
                       {:missing/key key :missing/label label}))]
    (cond
      (seq missing)
      {:amount/status :refused :amount/answer :declaration-incomplete
       :amount/missing missing
       :amount/why (str "従業員の申告が揃っていない: "
                        (str/join "、" (map :missing/label missing))
                        "。未登録は零ではない")}

      (not (:ytd/complete? ytd))
      {:amount/status :refused :amount/answer :year-to-date-incomplete
       :amount/why (str "その年の給与の記録が揃っていない（run "
                        (:ytd/runs ytd) " 件、"
                        "金額の無い run: " (pr-str (:ytd/runs-missing-a-figure ytd))
                        "）。揃っていない記録からの年税額は年税額ではない")}

      :else
      (let [unread (filterv (complement :table/read?) annual-tables)]
        (if (seq unread)
          {:amount/status :refused
           :amount/answer :annual-table-not-transcribed
           :amount/table (str/join "・" (map :table/label unread))
           :amount/unread-tables (mapv #(select-keys % [:table/label :table/why])
                                       unread)
           :amount/source (get rates/sources :nta/withholding-2026)
           :amount/why
           (str "その年の正しい税額は "
                (str/join "・" (map :table/label unread))
                " から出る。この repository はどちらも読んでいない。"
                "月額表（" (:table/label rates/withholding-table)
                "）は転記済みだが、それは1か月分の徴収額の表であって"
                "年税額の表ではない —— "
                "年間の給与をそこに通せば数字は出るが、"
                "それは年税額ではなく、"
                "源泉徴収票の過不足額として事業主が実際に精算する額になる")}
          ;; Unreachable until a table above is marked read. Left as the
          ;; shape the calculator will have, so that reading a table is a
          ;; change to `annual-tables` and to the arithmetic here, and not a
          ;; change to the refusal above.
          {:amount/status :refused
           :amount/answer :annual-calculation-not-implemented
           :amount/table (str/join "・" (map :table/label annual-tables))
           :amount/why (str "年税額の表は読まれているが、"
                            "給与所得控除・所得控除・速算表を通す計算は"
                            "まだ書かれていない。"
                            "読んだことと計算できることは別である")})))))

;; ---------------------------------------------------------------------------
;; The three artifacts
;; ---------------------------------------------------------------------------

(defn- figures
  "The figures every one of the three carries, as `payroll.provenance` values.

  `:declared` for what an operator supplied and `:unknown` for the two the
  table would produce. Never blank and never zero — `payroll.artifact.text`'s
  whole argument, at the place it matters most: a 源泉徴収票 with an empty
  年税額 cell is read as a 年税額 of nothing."
  [{:keys [ytd amount]}]
  (let [tax-known? (= :ok (:amount/status amount))]
    [[:gross (prov/declared "支払金額" (:ytd/gross ytd)
                            "この actor が commit した run の合計"
                            (:ytd/why ytd))]
     [:social_insurance (prov/declared "社会保険料等の金額"
                                       (:ytd/social-insurance ytd)
                                       "同上"
                                       "各保険料は operator の計上額であり、検算されていない")]
     [:income_tax_withheld (prov/declared "源泉徴収税額（実際に徴収した額）"
                                          (:ytd/income-tax-withheld ytd)
                                          "同上")]
     [:annual_tax (if tax-known?
                    (prov/derived "年税額" (:amount/annual-tax amount)
                                  (:amount/table amount))
                    (prov/unknown "年税額" (:amount/why amount)
                                  (:amount/table amount)))]
     [:over_or_under (if tax-known?
                       (prov/derived "過不足額" (:amount/over-or-under amount)
                                     "実際に徴収した額 − 年税額")
                       (prov/unknown "過不足額" (:amount/why amount)
                                     (:amount/table amount)))]]))

(defn preview
  "One employee-year as any of the three artifacts, WITHOUT the identifiers.

    {:kind :gensen-choshu-hyo | :kyuyo-shiharai-hokokusho
     :employer :contract :ytd :amount}

  Refuses to carry a My Number or an address even when they are registered,
  and `payroll.artifact.gensen-test` asserts that by registering both and
  scanning the result with `payroll.sensitive`."
  [{:keys [kind employer contract ytd amount]}]
  (let [a (first (filter #(= kind (:artifact/key %)) artifacts))
        figs (figures {:ytd ytd :amount amount})]
    (if-not a
      {:preview/status :refused
       :preview/why (str "その書類は無い。出せるのは "
                         (str/join "、" (map #(name (:artifact/key %)) artifacts)))}
      {:preview/status :ok
       :preview/kind kind
       :preview/label (:artifact/label a)
       :preview/statutory-form? false
       :preview/why-not-statutory (:artifact/why-not-statutory a)
       :preview/year (:ytd/year ytd)
       ;; contract id and NOT the worker's name — the same pseudonym rule the
       ;; R2 projection keeps, at the surface an operator reads.
       :preview/contract-id (:ytd/contract-id ytd)
       :preview/employer-id (:client-id employer)
       :preview/employer-name (:name employer)
       :preview/figures figs
       :preview/coverage (text/coverage (map second figs))
       :preview/dependants (:employment/dependants contract)
       :preview/withholding-column (:employment/withholding-column contract)
       :preview/carries-identifiers? false
       :preview/why (str "figures は operator の計上額の合計であり、"
                         "年税額と過不足額は税額表が未転記のため未確定である。"
                         "この画面には個人番号も住所も載らない")})))

(defn submission-export
  "What would be handed to a 税務署 or a 区市町村, or a refusal.

  This is the ONLY function here that carries a 個人番号 and an address, and
  it refuses without them: 「法定調書」without the identifiers is not one.

  It is also the only one whose output must never be logged.
  `payroll.sensitive/log-violations` over the returned map is non-empty by
  construction, which is the point — a caller that prints this is printing a
  My Number, and the check that catches it is the same one the health
  surfaces use."
  [{:keys [kind employer contract ytd amount]}]
  (let [missing (vec (for [{:field/keys [key label admits why]} submission-fields
                           :when (not (admits (get (merge employer contract) key)))]
                       {:missing/key key :missing/label label :missing/why why}))
        base (preview {:kind kind :employer employer :contract contract
                       :ytd ytd :amount amount})]
    (cond
      (= :refused (:preview/status base)) base

      (seq missing)
      {:export/status :refused
       :export/missing missing
       :export/why (str "提出用の出力には "
                        (str/join "、" (map :missing/label missing))
                        " が要る。"
                        "個人番号と住所の無い法定調書は法定調書ではない")}

      :else
      {:export/status :ok
       :export/kind kind
       :export/artifact (dissoc base :preview/status)
       ;; present ONLY here.
       :employment/my-number (:employment/my-number contract)
       :employment/address (:employment/address contract)
       :employer/corporate-number (:employer/corporate-number employer)
       :export/never-log? true
       :export/why (str "この値には個人番号と住所が入っている。"
                        "ログ・健全性応答・R2 投影のいずれにも出してはならない"
                        "（payroll.sensitive）")})))

(defn statutory-summary
  "法定調書合計表 — one employer, one year, over the employees given.

  `:summary/complete?` is false when any employee's year-to-date was
  incomplete, and the total is then withheld. A 合計表 built from the
  employees whose records happened to be complete is a total that balances,
  having lost the others."
  [{:keys [employer year employees]}]
  (let [ytds (mapv :ytd employees)
        complete (filterv :ytd/complete? ytds)
        all? (and (seq ytds) (= (count ytds) (count complete)))]
    {:summary/employer-id (:client-id employer)
     :summary/employer-name (:name employer)
     :summary/year (str year)
     :summary/statutory-form? false
     :summary/employees (count ytds)
     :summary/employees-complete (count complete)
     :summary/complete? all?
     :summary/total-gross (when all? (reduce + 0 (map :ytd/gross ytds)))
     :summary/total-withheld (when all?
                               (reduce + 0 (map :ytd/income-tax-withheld ytds)))
     :summary/incomplete
     (vec (for [y ytds :when (not (:ytd/complete? y))]
            {:contract-id (:ytd/contract-id y)
             :why (str "その年の記録が揃っていない（run " (:ytd/runs y) " 件）")}))
     :summary/why
     (if all?
       (str (count ytds) " 名分の合計。"
            "様式は読んでいないので、これは集計であって提出物ではない")
       (str (count ytds) " 名のうち " (count complete)
            " 名しか記録が揃っていない。"
            "揃った分だけの合計は合計表ではない"))}))

(defn ->json
  "A preview as JSON. Carries the coverage counts and every figure's
  provenance, and asserts `statutory_form false` in the document itself."
  [{:preview/keys [kind label year contract-id employer-id employer-name
                   figures coverage why]}]
  (text/json-document
   [[:document_type (name (or kind :unknown))]
    [:label label]
    [:statutory_form false]
    [:why_not_statutory why]
    [:source (text/json-object-of [[:title (:source/title source)]
                                   [:url (:source/url source)]
                                   [:not_read (vec (:source/not-read source))]])]
    [:year year]
    [:employer_id employer-id]
    [:employer_name employer-name]
    [:contract_id contract-id]
    [:carries_my_number false]
    [:carries_address false]
    [:figures (text/figures->json figures)]
    [:coverage (text/json-object-of
                [[:figures (:coverage/figures coverage)]
                 [:certified (:coverage/certified coverage)]
                 [:unverified (:coverage/unverified coverage)]])]]))

(defn loggable?
  "May this value go in a log line? False for anything `submission-export`
  produced. Exported so the host and the console can ask rather than
  remember."
  [m]
  (empty? (sensitive/log-violations m)))
