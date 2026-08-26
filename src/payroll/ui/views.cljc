(ns payroll.ui.views
  "The operator console's screens, as data.

  ## Views are a table, and the nav is generated from it

  `views` is a vector. `nav` renders it, `view-of` dispatches on it, and
  `payroll.ui.views-test` walks it. A view added to the table appears in the
  navigation, gets an accessibility check and gets a route, all without a
  second edit — which is the property CLAUDE.md's single-page rule is really
  about: *a view added to the dispatch and forgotten in the nav is dead code
  that looks live.*

  ## One document per view, and no bundle at all

  This console is SERVER-RENDERED and ships no JavaScript. That is the
  exception ADR-2608231200 records for `kagi ui` — a page that sits next to
  live credentials — and it applies here for the same reason: the cheapest
  correct answer to *what can this script do with a payroll operator's
  session* is that there is no script. What the single-page rule actually
  protects is preserved intact: one shell, one stylesheet, views generated
  from data, and no second app shell that can miss a design-system migration.

  What is given up is the mount, and that is all: a form submission is a
  round trip to a loopback socket. What is gained is that a `default-src
  'none'` content-security policy is truthful rather than aspirational.

  ## Nothing here is a dashboard

  Every screen either takes an input the actor needs, shows a figure with its
  provenance, or produces an artifact. There is no chart, no count of runs
  this week, and no green tick that is not attached to a specific figure —
  because the one thing an operator must not be able to do here is form an
  overall impression of health. `payroll.ui.state/tone-of` is worst-wins for
  the same reason."
  (:require [clojure.string :as str]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.artifact.zengin :as zengin]
            [payroll.juminzei :as juminzei]
            [payroll.meisai :as meisai]
            [payroll.mf.schema :as mf-schema]
            [payroll.touroku :as touroku]
            [payroll.ui.state :as ui]))

;; ---------------------------------------------------------------------------
;; Small pieces
;; ---------------------------------------------------------------------------

(defn- field
  "A labelled form control. The `id` is required and the label points at it —
  `payroll.ui.a11y`'s `:label-missing` and `:label-for-nothing` rules are what
  make forgetting either one a test failure rather than a screen an operator
  cannot use with a keyboard."
  [{:keys [id label type name value required? hint options rows]}]
  [:div {:class "dds-ext-stack field"}
   [:label {:for id} label
    (when required? [:span {:class "req" :aria-label "必須"} "必須"])]
   (cond
     options
     (into [:select {:id id :name name :class "dds-select"
                     :aria-describedby (when hint (str id "-hint"))}]
           (for [{:keys [value* label*]} options]
             [:option (cond-> {:value value*}
                        (= value* value) (assoc :selected "selected"))
              label*]))

     (= type :textarea)
     [:textarea {:id id :name name :rows (or rows 8) :class "dds-textarea"
                 :aria-describedby (when hint (str id "-hint"))}
      (or value "")]

     :else
     [:input (cond-> {:id id :name name :type (clojure.core/name (or type :text))
                      :class "dds-input" :value (str (or value ""))}
               hint (assoc :aria-describedby (str id "-hint"))
               required? (assoc :required "required"))])
   (when hint [:p {:id (str id "-hint") :class "hint"} hint])])

(defn- figure-row
  "One figure as a table row: label, amount, state chip, reason.

  The reason is a CELL and not a tooltip. A `title` attribute is invisible to
  touch and to the keyboard, and the reason a payroll figure is unknown is not
  supplementary information."
  [label f]
  [:tr {:class (str "prov-row prov-" (name (:figure/provenance f)))}
   [:th {:scope "row"} label]
   [:td {:class "amt"} (ui/amount-text f)]
   [:td (ui/figure-chip f)]
   [:td {:class "why"} (or (:figure/why f) (:figure/source f) "—")]])

(defn- gap-list [gaps]
  [:table {:class "gaps"}
   [:caption "未登録の事実と、その結果"]
   [:thead [:tr [:th {:scope "col"} "項目"]
            [:th {:scope "col"} "登録キー"]
            [:th {:scope "col"} "未登録だとどうなるか"]]]
   [:tbody
    (for [g gaps]
      [:tr [:th {:scope "row"} (:gap/label g)]
       [:td [:code (pr-str (:gap/key g))]]
       [:td (:gap/consequence g)]])]])

(defn- empty-note
  "What to show where a list is empty.

  Always a sentence and never a blank region. An empty table with a heading
  above it reads as `nothing is wrong`; `まだ一件も無い` reads as what it is."
  [text]
  [:p {:class "empty-note"} text])

;; ---------------------------------------------------------------------------
;; 現況 — what this deployment actually is
;; ---------------------------------------------------------------------------

(defn overview
  [{:keys [employer contracts runs store durability]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "給与 — 運用コンソール"]

   [:section {:class "dds-ext-card"}
    [:h2 "この配備について"]
    [:table
     [:caption "配備の状態"]
     [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "値"]
              [:th {:scope "col"} "意味"]]]
     [:tbody
      [:tr [:th {:scope "row"} "事業主"]
       [:td (or (:name employer) "—")]
       [:td (str "検証済みの呼び出し元から決まる（" (:client-id employer) "）")]]
      [:tr [:th {:scope "row"} "法域"]
       [:td (if (:jurisdiction employer) (pr-str (:jurisdiction employer)) "未登録")]
       [:td (if (:jurisdiction employer)
              "源泉徴収と社会保険の条文がこの法域で参照される"
              (str "未登録。源泉徴収も社会保険も一切参照されない。"
                   "それは「義務が無い」ではなく「調べていない」である"))]]
      [:tr [:th {:scope "row"} "保存先"]
       [:td (if (:mode store) (name (:mode store)) "未設定")]
       [:td (:store/what durability)]]
      [:tr {:class (str "durability-" (if (:store/survives-process-restart? durability)
                                        "yes" "no"))}
       [:th {:scope "row"} "再起動で残るか"]
       [:td (if (:store/survives-process-restart? durability) "残る" "残らない")]
       [:td (:store/why durability)]]
      [:tr [:th {:scope "row"} "登録された契約"]
       [:td (count contracts)]
       [:td "この事業主の雇用契約"]]
      [:tr [:th {:scope "row"} "記録された run"]
       [:td (count runs)]
       [:td "台帳にある給与 run（保留も含む）"]]]]]

   [:section {:class "dds-ext-card"}
    [:h2 "表示の読み方"]
    [:p (str "金額の隣にある印は、その数字を誰が出したかを表す。"
             "色だけで区別しているものは一つも無い —— 印と語が常に付く")]
    (ui/legend-hiccup)]])

;; ---------------------------------------------------------------------------
;; 従業員・契約
;; ---------------------------------------------------------------------------

(defn- contract-card [contract]
  (let [gaps (touroku/registration-gaps contract)]
    [:section {:class "dds-ext-card contract-card"}
     [:h3 (str (:contract/worker contract) "（" (:contract/id contract) "）")]
     [:dl {:class "kv"}
      [:div [:dt "職種"] [:dd (or (:contract/role contract) "—")]]
      [:div [:dt "賃金形態"]
       [:dd (case (:contract/wage-type contract)
              :monthly "月給" :hourly "時給" "—")]]
      [:div [:dt "賃金額"] [:dd (str (:contract/rate contract) " "
                                     (or (:contract/currency contract) "JPY"))]]]
     [:table {:class "coverage"}
      [:caption "被保険者資格（operator が登録する観測値）"]
      [:thead [:tr [:th {:scope "col"} "制度"] [:th {:scope "col"} "登録"]]]
      [:tbody
       (for [[k label] [[:employment/health-insurance-insured? "健康保険"]
                        [:employment/care-insurance-second-category? "介護保険 第二号"]
                        [:employment/employees-pension-insured? "厚生年金"]
                        [:employment/employment-insurance-insured? "雇用保険"]
                        [:employment/year-end-declaration-filed? "扶養控除等申告書"]]]
         [:tr [:th {:scope "row"} label]
          [:td (ui/yes-no-chip (get contract k) label)]])]]
     (if (seq gaps)
       (gap-list gaps)
       (empty-note "この契約に未登録の任意項目は無い"))]))

(defn- registration-form [{:keys [form]}]
  [:section {:class "dds-ext-card"}
   [:h3 "契約を登録する"]
   [:p (str "登録層（payroll.touroku）が受け付けたものだけが保存される。"
            "既定値の補完はしない —— 未登録は未登録のまま保存され、"
            "run はその分だけ保留される")]
   [:form {:method "post" :action "/console/contract" :class "dds-ext-stack"}
    (field {:id "f-contract-id" :name "contract-id" :label "契約 ID"
            :required? true :value (:contract-id form)
            :hint "後からこの契約を名指しするための識別子"})
    (field {:id "f-worker" :name "worker" :label "従業員" :required? true
            :value (:worker form)
            :hint "勤怠と賃金台帳を紐づけるキー"})
    (field {:id "f-role" :name "role" :label "職種" :value (:role form)})
    (field {:id "f-wage-type" :name "wage-type" :label "賃金形態"
            :required? true :value (or (:wage-type form) "monthly")
            :options [{:value* "monthly" :label* "月給"}
                      {:value* "hourly" :label* "時給"}]
            :hint (str "月給を選ぶと、賃金額は契約月額そのものになり、"
                       "勤怠は金額の計算に一切使われない")})
    (field {:id "f-rate" :name "rate" :label "賃金額（円）" :required? true
            :type :number :value (:rate form)
            :hint "月給なら月額、時給なら時給"})
    (field {:id "f-srm" :name "standard-remuneration" :label "標準報酬月額（円）"
            :type :number :value (:standard-remuneration form)
            :hint (str "保険者等が決定した額。この actor は計算しない。"
                       "未登録だと社会保険の run は保留される")})
    (field {:id "f-srm-month" :name "standard-remuneration-month"
            :label "標準報酬月額の対象月（YYYY-MM）"
            :value (:standard-remuneration-month form)
            :hint "条文が控除を認めるのは「前月の」標準報酬月額に係る保険料である"})
    (for [[id nm label] [["f-hi" "health-insurance-insured" "健康保険 被保険者"]
                         ["f-ci" "care-insurance-second-category" "介護保険 第二号被保険者"]
                         ["f-ep" "employees-pension-insured" "厚生年金 被保険者"]
                         ["f-ei" "employment-insurance-insured" "雇用保険 被保険者"]
                         ["f-yed" "year-end-declaration-filed" "扶養控除等申告書の提出"]]]
      (field {:id id :name nm :label label
              :value (get form (keyword nm))
              :options [{:value* "" :label* "未登録（観測していない）"}
                        {:value* "true" :label* "はい"}
                        {:value* "false" :label* "いいえ"}]
              :hint "「未登録」は「いいえ」ではない。観測していないなら未登録のままにする"}))
    ;; 住民税. The blank option is FIRST and says 未登録 —— a select whose
    ;; first option were 特別徴収 would classify every employee nobody has
    ;; classified, by the act of leaving the control alone.
    (field {:id "f-rto" :name "resident-tax-obligation"
            :label "住民税の特別徴収"
            :value (:resident-tax-obligation form)
            :options [{:value* "" :label* "未登録（分類していない）"}
                      {:value* "special-collection"
                       :label* "特別徴収（給与から差し引く）"}
                      {:value* "not-special-collection"
                       :label* "普通徴収（本人が納付する。普A〜普F の理由が要る）"}]
            :hint (str "「未登録」は「対象外」ではない。"
                       "誰も分類していない run の住民税の行は保留される —— "
                       "零として差し引かれるのではない。"
                       "「普通徴収」を選ぶと、その行は「該当なし」になる")})
    (field {:id "f-mf-no" :name "mf-employee-number"
            :label "MoneyForward 従業員番号" :value (:mf-employee-number form)
            :hint "突合に使う。氏名での推測照合はしない"})
    [:button {:type "submit" :class "dds-button dds-button-primary"} "登録する"]]])

(defn employees
  [{:keys [contracts employer] :as ctx}]
  [:div {:class "dds-ext-stack"}
   [:h1 "従業員・契約"]
   [:p (str "給与 run は登録された雇用契約を引用しなければならない"
            "（雇用の捏造禁止）。ここに無い従業員には給与を計算できない")]
   (let [egaps (touroku/employer-gaps employer)]
     (when (seq egaps)
       [:section {:class "dds-ext-card"}
        [:h2 "事業主の登録"]
        (gap-list egaps)]))
   [:section {:class "dds-ext-stack"}
    [:h2 (str "登録済みの契約（" (count contracts) " 件）")]
    (if (seq contracts)
      (for [c contracts] (contract-card c))
      (empty-note "契約がまだ一件も登録されていない"))]
   (registration-form ctx)])

;; ---------------------------------------------------------------------------
;; 給与計算
;; ---------------------------------------------------------------------------

(defn- run-review
  "The calculation review — the screen an operator reads before approving.

  Every deduction line is a row with its own provenance chip, and the wage
  basis gets its own panel because it is the thing that is invisible in the
  numbers: a monthly run whose timesheets were never read looks exactly like
  one where they were."
  [{:keys [contract-id period meisai]}]
  (let [basis (:meisai/basis meisai)]
    [:section {:class (str "dds-ext-card run-review tone-"
                           (name (ui/tone-of (:meisai/figures meisai))))}
     [:h2 (str "計算結果 — " contract-id " / " period)]
     [:p {:class "run-disposition"}
      (ui/disposition-chip (:meisai/disposition meisai))
      [:span {:class "payable"}
       (if (meisai/payable? meisai)
         "この run は支払える"
         "この run は支払ってはならない")]]

     [:table
      [:caption "支給と控除"]
      [:thead [:tr [:th {:scope "col"} "項目"]
               [:th {:scope "col" :class "amt"} "金額"]
               [:th {:scope "col"} "出所"]
               [:th {:scope "col"} "理由・根拠"]]]
      [:tbody
       (figure-row "総支給額" (:meisai/gross meisai))
       (for [{:line/keys [label figure]} (:meisai/deductions meisai)]
         (figure-row label figure))]
      [:tfoot
       (figure-row "控除合計" (:meisai/deduction-total meisai))
       (figure-row "差引支給額" (:meisai/net meisai))]]

     [:section {:class "wage-basis"}
      [:h3 "総支給額の基礎"]
      [:p (:chingin/why basis)]
      [:dl {:class "kv"}
       [:div [:dt "勤怠を金額計算に使ったか"]
        [:dd (ui/yes-no-chip (:chingin/reads-timesheets? basis)
                             "勤怠を金額計算に使ったか")]]
       [:div [:dt "登録されている勤怠"]
        [:dd (str (or (:chingin/timesheet-count basis) 0) " 件")]]]
      (when (seq (:chingin/unaccounted basis))
        [:table
         [:caption "この金額に含まれていない登録事実"]
         [:thead [:tr [:th {:scope "col"} "事実"] [:th {:scope "col"} "登録値"]
                  [:th {:scope "col"} "要る条文（未読）"]]]
         [:tbody
          (for [u (:chingin/unaccounted basis)]
            [:tr [:th {:scope "row"} (:premium/label u)]
             [:td (pr-str (:premium/registered u))]
             [:td (:premium/provision-not-read u)]])]])]

     [:p {:class "summary"} (ui/summarise (:meisai/figures meisai))]]))

(defn- violations-panel [violations]
  [:section {:class "dds-ext-card violations"}
   [:h2 "保留の理由"]
   [:table
    [:caption "governor が挙げた HARD 違反"]
    [:thead [:tr [:th {:scope "col"} "規則"] [:th {:scope "col"} "内容"]]]
    [:tbody
     (for [v violations]
       [:tr [:th {:scope "row"} [:code (str (:rule v))]]
        [:td (:detail v)]])]]])

(defn run
  [{:keys [contracts form latest violations]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "給与計算"]
   [:section {:class "dds-ext-card"}
    [:h2 "run を作成する"]
    [:p (str "所得税と四つの社会保険料は、この actor が計算するものではなく、"
             "operator が計上する額である。"
             "空欄は零ではなく未回答として扱われ、run は保留される")]
    [:form {:method "post" :action "/console/run" :class "dds-ext-stack"}
     (field {:id "r-contract" :name "contract-id" :label "雇用契約"
             :required? true :value (:contract-id form)
             :options (into [{:value* "" :label* "— 選択 —"}]
                            (for [c contracts]
                              {:value* (:contract/id c)
                               :label* (str (:contract/worker c)
                                            "（" (:contract/id c) "）")}))})
     (field {:id "r-period" :name "period" :label "対象期間" :required? true
             :value (:period form)
             :hint "後からこの run を引くための識別子。この actor は期間を勝手に決めない"})
     (field {:id "r-deductions" :name "deductions" :label "控除合計（円）"
             :type :number :value (:deductions form)
             :hint (str "差引支給額はこの額を引いて計算される。"
                        "下の五つの合計と一致しないと、明細と仕訳の両方が拒否する")})
     [:fieldset {:class "dds-ext-stack"}
      [:legend "控除の内訳"]
      (field {:id "r-tax" :name "income-tax-withheld" :label "所得税（源泉徴収）"
              :type :number :value (:income-tax-withheld form)
              :hint "所得税法 第百八十三条第一項。税額表は未読なので、額は検算されない"})
      (field {:id "r-hi" :name "health-insurance-withheld" :label "健康保険料"
              :type :number :value (:health-insurance-withheld form)
              :hint "料率は協会の告示にあり、未読。額は検算されない"})
      (field {:id "r-ci" :name "care-insurance-withheld" :label "介護保険料"
              :type :number :value (:care-insurance-withheld form)
              :hint "料率は保険者が定めるもので、未読"})
      (field {:id "r-ep" :name "employees-pension-withheld" :label "厚生年金保険料"
              :type :number :value (:employees-pension-withheld form)
              :hint (str "唯一、条文に料率がある（厚年法 第八十一条第四項 千分の百八十三）。"
                         "標準報酬月額から出る厳密値との差が一円以上なら拒否される")})
      (field {:id "r-ei" :name "employment-insurance-withheld" :label "雇用保険料"
              :type :number :value (:employment-insurance-withheld form)
              :hint "雇用保険率は告示で変わるもので、未読"})]
     [:button {:type "submit" :class "dds-button dds-button-primary"} "計算する"]]]

   (when (seq violations) (violations-panel violations))
   (if latest
     (run-review latest)
     (empty-note "まだ計算していない。上のフォームから run を作る"))])

;; ---------------------------------------------------------------------------
;; 出力物
;; ---------------------------------------------------------------------------

(def zengin-source
  "Read straight off `payroll.artifact.zengin` rather than restated here.

  A screen with its own copy of a document's revision date is the copy that
  drifts — `payroll.ui.views-test` asserts the rendered page carries the
  namespace's value and not a literal."
  zengin/source)

(def zengin-record-length zengin/record-length)
(def zengin-csv-discrepancy zengin/csv-sample-discrepancy)
(def zengin-origin-fields zengin/origin-fields)

(def export-path
  "Where a document is fetched from.

  One def rather than a literal in the form's `action`, in the router's cond
  and in `payroll.operations`. Three copies of a path is two copies that keep
  working after a rename and one screen whose download button 404s — and the
  screen is the copy nobody exercises, because the router's own test asks for
  the path it was given."
  "/console/export")

(def notice-path
  "Where a transcribed 住民税 通知書 is posted.

  `export-path`'s def for `export-path`'s reason, and the reason bites harder
  here: this path is in the form's `action`, in the router's cond and in
  `payroll.operations/resident-tax-registration`, which is what the operations
  screen shows an operator who is looking for the form. A rename that missed
  the report would leave the console TELLING somebody a path that 404s."
  "/console/juminzei-notice")

(def notice-confirmations
  "What `?notice=…` on the operations screen means, as data.

  One map, read by the router (which turns it into the flash at the top of
  the document) and by the resident-tax panel (which renders the banner and
  reads the registered counts back underneath it). Two copies of these two
  sentences would be two answers to `did that work`, and the second would be
  the one that stayed after the first was reworded.

  There are two of them because a registration and a RETRY of one are
  different things to be told. `payroll.juminzei/admit-registration` answers
  `:duplicate` rather than `:ok` for a resubmitted transcription, and an
  operator who clicked twice needs to know that the second click registered
  nothing — otherwise the honest reading of two 「登録した」 banners is that
  they now hold two notices."
  {"registered"
   {:confirmation/kind :registered
    :confirmation/label "通知を登録した"
    :confirmation/message
    (str "転記された通知を、この事業主の通知ストリームに追記した。"
         "既存の通知は上書きしていない")}
   "duplicate"
   {:confirmation/kind :duplicate
    :confirmation/label "同じ通知が既に登録されていた"
    :confirmation/message
    (str "同じ通知が同じ内容で既に登録されている。"
         "再送は二度目の登録ではないので、何も追記していない —— "
         "これは拒否ではなく、転記が一度で済んでいるということである")}})

(def artifacts
  "The artifacts this console can produce, as a table.

  `:artifact/statutory?` is false for every one of them and there is no code
  path that sets it true. Rendering it as a COLUMN rather than as a footnote
  is the point: an operator scanning the list sees the same answer five times
  and cannot come away thinking one of them is a filing."
  [{:artifact/key :payslip :artifact/label "給与支払明細書"
    :artifact/formats [:html :json] :artifact/statutory? false
    :artifact/why "所得税法 第二百三十一条第一項 も記載事項の省令も未読"}
   {:artifact/key :wage-ledger :artifact/label "賃金台帳"
    :artifact/formats [:csv :json] :artifact/statutory? false
    :artifact/why "労働基準法 第百八条 も施行規則 第五十四条 も未読"}
   {:artifact/key :deduction-summary :artifact/label "控除額集計"
    :artifact/formats [:csv :json] :artifact/statutory? false
    :artifact/why "納付書の様式は読んでいない。金額の集計のみ"}
   {:artifact/key :bank-transfer :artifact/label "振込データ（独自列）"
    :artifact/formats [:csv :json] :artifact/statutory? false
    :artifact/why "この repository 独自の列。どの銀行の様式でもない"}
   {:artifact/key :zengin :artifact/label "全銀 総合振込（PayPay銀行）"
    :artifact/formats [:fixed-width :csv :json] :artifact/statutory? false
    :artifact/why (str "PayPay銀行の項目説明（2025-03-06改定）から転記した"
                       "固定長120バイト（Shift_JIS・CRLF）と CSV。"
                       "銀行がこのファイルを受理したことは確かめていない —— "
                       "テスト振込は行われていない")}
   {:artifact/key :journal :artifact/label "仕訳（会計への引渡し）"
    :artifact/formats [:json] :artifact/statutory? false
    :artifact/why "勘定科目は事業主の勘定体系であり、この actor は選ばない"}])

(defn exports
  [{:keys [form contracts zengin transfer]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "出力物"]

   [:section {:class "dds-ext-card"}
    [:h2 "出せるもの"]
    [:table
     [:caption "この console が生成できる書類と、それが主張しないこと"]
     [:thead [:tr [:th {:scope "col"} "書類"] [:th {:scope "col"} "形式"]
              [:th {:scope "col"} "法定様式か"] [:th {:scope "col"} "理由"]]]
     [:tbody
      (for [a artifacts]
        [:tr [:th {:scope "row"} (:artifact/label a)]
         [:td (str/join " / " (map name (:artifact/formats a)))]
         [:td [:span {:class "state-not-statutory" :aria-label "法定様式ではない"}
               "法定様式ではない"]]
         [:td (:artifact/why a)]])]]]

   [:section {:class "dds-ext-card"}
    [:h2 "書類を取り出す"]
    [:form {:method "get" :action export-path :class "dds-ext-stack"}
     (field {:id "x-contract" :name "contract-id" :label "雇用契約"
             :value (:contract-id form)
             :options (into [{:value* "" :label* "— 全件 —"}]
                            (for [c contracts]
                              {:value* (:contract/id c)
                               :label* (:contract/id c)}))
             :hint "給与支払明細書は一件を指定したときだけ出せる"})
     (field {:id "x-period" :name "period" :label "対象期間"
             :value (:period form)})
     (field {:id "x-kind" :name "kind" :label "書類" :required? true
             :value (:kind form)
             :options (for [a artifacts]
                        {:value* (name (:artifact/key a))
                         :label* (:artifact/label a)})})
     (field {:id "x-format" :name "format" :label "形式" :required? true
             :value (or (:format form) "json")
             :options [{:value* "json" :label* "JSON"}
                       {:value* "csv" :label* "CSV"}
                       {:value* "fixed-width"
                        :label* "固定長 120バイト（Shift_JIS・全銀のみ）"}
                       {:value* "html" :label* "印刷用 HTML"}]
             :hint (str "形式は書類ごとに決まっている。"
                        "上の表に無い組み合わせは、空のファイルではなく"
                        "出せるものを名指しする 400 になる")})
     [:button {:type "submit" :class "dds-button dds-button-primary"} "取り出す"]]]

   (when transfer
     [:section {:class "dds-ext-card"}
      [:h2 "振込データの現況"]
      [:p (str "支払える run " (count (:transfer/lines transfer))
               " 件・登録が足りない run " (count (:transfer/refused transfer)) " 件")]
      (if (seq (:transfer/refused transfer))
        [:table
         [:caption "振込データに載せられない run"]
         [:thead [:tr [:th {:scope "col"} "契約"] [:th {:scope "col"} "理由"]]]
         [:tbody
          (for [r (:transfer/refused transfer)]
            [:tr [:th {:scope "row"} (:payee/contract-id r)]
             [:td (:payee/why r)]])]]
        (empty-note "載せられない run は無い"))])

   [:section {:class "dds-ext-card zengin"}
    [:h2 "全銀 総合振込 — 出力できる。ただし銀行に通したことは無い"]
    [:p {:class "state-unverified" :aria-label "銀行での受理は未検証"}
     (str "PayPay銀行の項目説明（" (:source/revised zengin-source)
          " 改定）から転記した固定長 " zengin-record-length
          " バイト（Shift_JIS・CRLF）と、その CSV 版を出力する。"
          (:source/what-it-does-not-establish zengin-source))]
    [:table
     [:caption "この様式について確かめたことと、確かめていないこと"]
     [:thead [:tr [:th {:scope "col"} "事項"] [:th {:scope "col"} "状態"]
              [:th {:scope "col"} "根拠"]]]
     [:tbody
      [:tr [:th {:scope "row"} "レコード長"]
       [:td [:span {:class "state-verified" :aria-label "転記済み"} "転記済み"]]
       [:td (str "1レコード " zengin-record-length
                 " バイト。許容文字はすべて Shift_JIS で1バイトなので"
                 "文字数と一致する")]]
      [:tr [:th {:scope "row"} "使用許容文字"]
       [:td [:span {:class "state-verified" :aria-label "転記済み"} "転記済み"]]
       [:td (str "小文字カナ・長音・中黒は使えない。"
                 "この actor は置き換えず、拒否して仕様の文言を示す —— "
                 "口座名義は operator が通帳を見て登録する")]]
      [:tr [:th {:scope "row"} "改行コード"]
       [:td [:span {:class "state-unverified" :aria-label "未検証"} "未検証"]]
       [:td "CRLF。仕様書の本文は終端子を明示していない（全銀の慣行による）"]]
      [:tr [:th {:scope "row"} "CSV 見本の読点"]
       [:td [:span {:class "state-unverified" :aria-label "未解決"} "未解決"]]
       [:td (:discrepancy/what zengin-csv-discrepancy)]]
      [:tr [:th {:scope "row"} "銀行がこのファイルを受理すること"]
       [:td [:span {:class "state-unverified" :aria-label "未実施"} "未実施"]]
       [:td "テスト振込は行われていない"]]]]
    [:table
     [:caption "契約ごとの振込先登録状況"]
     [:thead [:tr [:th {:scope "col"} "契約"] [:th {:scope "col"} "登録済み"]
              [:th {:scope "col"} "未登録"]]]
     [:tbody
      (for [c (:zengin/per-contract zengin)]
        [:tr [:th {:scope "row"} (:contract-id c)]
         [:td (if (seq (:zengin/registered c))
                (str/join "、" (map #(:field/label
                                      (first (filter (fn [f] (= % (:field/key f)))
                                                     bank/payee-fields)))
                                    (:zengin/registered c)))
                "なし")]
         [:td (if (seq (:zengin/missing c))
                (str/join "、" (map #(:field/label
                                      (first (filter (fn [f] (= % (:field/key f)))
                                                     bank/payee-fields)))
                                    (:zengin/missing c)))
                "なし")]])]]
    [:table
     [:caption "依頼人（事業主）側に登録が要る項目"]
     [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "必須"]
              [:th {:scope "col"} "理由"]]]
     [:tbody
      (for [f zengin-origin-fields]
        [:tr [:th {:scope "row"} (:field/label f)]
         [:td (if (:field/required? f) "必須" "任意")]
         [:td (or (:field/why f) "")]])
      [:tr [:th {:scope "row"} "振込指定日（MMDD）"]
       [:td "必須"]
       [:td (str "この actor は暦を持たず、期間から日付を導出しない。"
                 "アップロード画面で入力する日付と一致させるのは operator の仕事")]]]]]])

;; ---------------------------------------------------------------------------
;; MoneyForward 取込・突合
;; ---------------------------------------------------------------------------

(defn mf-import
  [{:keys [form reconciliation]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "MoneyForward 取込・突合"]

   [:section {:class "dds-ext-card unverified-source"}
    [:h2 "この取込について"]
    [:p {:class "state-unverified" :aria-label "取込元は未検証"}
     (str "実際の MoneyForward クラウド給与のエクスポートを、"
          "この repository は一度も読んでいない。"
          "下の列名はすべて推測であり、名前が違えばその列は読み込まれない")]
    [:table
     [:caption "この repository が想定している列（すべて未検証）"]
     [:thead [:tr [:th {:scope "col"} "列名（推測）"]
              [:th {:scope "col"} "対応する項目"]
              [:th {:scope "col"} "必須"]
              [:th {:scope "col"} "検証済み"]]]
     [:tbody
      (for [c mf-schema/columns]
        [:tr [:th {:scope "row"} (:mf/column c)]
         [:td (if (= :mf/no-counterpart (:mf/to c))
                [:span {:class "state-no-counterpart"
                        :aria-label "この actor に対応する概念が無い"}
                 "対応する概念が無い"]
                (str (:mf/to c)))]
         [:td (if (:mf/required? c) "必須" "任意")]
         [:td [:span {:class "state-unverified" :aria-label "未検証の列名"}
               "未検証"]]])]]]

   [:section {:class "dds-ext-card"}
    [:h2 "エクスポートを貼り付ける"]
    [:p (str "ファイルは保存されない。取り込まれた金額が run になることも無い —— "
             "他システムが出した数字をこの actor が給与として採用することは"
             "設計上あり得ない。ここでやるのは突合だけである")]
    [:form {:method "post" :action "/console/mf" :class "dds-ext-stack"}
     (field {:id "m-period" :name "period" :label "対象期間" :required? true
             :value (:period form)})
     (field {:id "m-csv" :name "csv" :label "CSV の中身" :type :textarea
             :rows 12 :value (:csv form)
             :hint "先頭行が見出し。RFC 4180（引用符・改行を含む欄も可）"})
     [:button {:type "submit" :class "dds-button dds-button-primary"} "突合する"]]]

   (if reconciliation
     [:section {:class (str "dds-ext-card reconciliation "
                            (if (:reconcile/reconciled? reconciliation)
                              "tone-ok" "tone-stop"))}
      [:h2 "突合結果"]
      [:p {:class (str "verdict-" (if (:reconcile/reconciled? reconciliation)
                                    "agree" "differ"))
           :aria-label (if (:reconcile/reconciled? reconciliation)
                         "一致" "不一致または比較不能")}
       (if (:reconcile/reconciled? reconciliation) "一致" "一致していない")
       " — " (:reconcile/why reconciliation)]
      [:dl {:class "kv"}
       [:div [:dt "ファイルの行数"] [:dd (:reconcile/rows reconciliation)]]
       [:div [:dt "突合できた run"] [:dd (:reconcile/compared reconciliation)]]]

      (if (seq (:reconcile/runs reconciliation))
        (for [r (:reconcile/runs reconciliation)]
          [:table {:class "recon-run"}
           [:caption (str (:run/contract-id r) " / " (:run/period r))]
           [:thead [:tr [:th {:scope "col"} "項目"]
                    [:th {:scope "col" :class "amt"} "当 actor"]
                    [:th {:scope "col" :class "amt"} "MoneyForward"]
                    [:th {:scope "col"} "判定"]
                    [:th {:scope "col"} "理由"]]]
           [:tbody
            (if (seq (:run/fields r))
              (for [f (:run/fields r)]
                [:tr [:th {:scope "row"} (:field/label f)]
                 [:td {:class "amt"} (if (:field/ours f)
                                       (ui/amount-text (:field/ours f)) "—")]
                 [:td {:class "amt"} (if (:field/theirs f)
                                       (ui/amount-text (:field/theirs f)) "—")]
                 [:td (ui/verdict-chip (:field/verdict f) (:field/why f))]
                 [:td {:class "why"} (or (:field/why f) "—")]])
              (for [b (:run/blocking r)]
                [:tr [:th {:scope "row"} "—"] [:td "—"] [:td "—"]
                 [:td (ui/verdict-chip (:field/verdict b) (:field/why b))]
                 [:td {:class "why"} (:field/why b)]]))]])
        (empty-note "突合できた run が無い（差分が無いことではない）"))

      (when (seq (:reconcile/unmapped reconciliation))
        [:table
         [:caption "契約に紐づかない行"]
         [:thead [:tr [:th {:scope "col"} "行"] [:th {:scope "col"} "従業員番号"]
                  [:th {:scope "col"} "理由"]]]
         [:tbody
          (for [u (:reconcile/unmapped reconciliation)]
            [:tr [:th {:scope "row"} (:row/number u)]
             [:td (str (:row/employee-number u))]
             [:td (:row/why u)]])]])

      (when (seq (:reconcile/no-counterpart reconciliation))
        [:table
         [:caption "この actor に対応する概念が無い控除"]
         [:thead [:tr [:th {:scope "col"} "列"] [:th {:scope "col"} "見つかった値"]
                  [:th {:scope "col"} "なぜ扱えないか"]]]
         [:tbody
          (for [c (:reconcile/no-counterpart reconciliation)]
            [:tr [:th {:scope "row"} (:column c)]
             [:td (str/join "、" (:values-seen c))]
             [:td (:why c)]])]])

      (when (seq (:reconcile/rejected-rows reconciliation))
        [:table
         [:caption "読み取れずに拒否した行"]
         [:thead [:tr [:th {:scope "col"} "行"] [:th {:scope "col"} "理由"]]]
         [:tbody
          (for [r (:reconcile/rejected-rows reconciliation)]
            [:tr [:th {:scope "row"} (:row/number r)] [:td (:row/why r)]])]])]
     (empty-note "まだ突合していない"))])

;; ---------------------------------------------------------------------------
;; 監査台帳
;; ---------------------------------------------------------------------------

(defn ledger
  [{:keys [ledger-entries]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "監査台帳"]
   [:p (str "この actor が決めたことすべて —— 承認も、署名待ちも、保留も。"
            "古い順。呼び出し元の事業主の分だけが見える")]
   (if (seq ledger-entries)
     [:table {:class "ledger"}
      [:caption (str "台帳（" (count ledger-entries) " 件）")]
      [:thead [:tr [:th {:scope "col"} "#"] [:th {:scope "col"} "op"]
               [:th {:scope "col"} "契約"] [:th {:scope "col"} "期間"]
               [:th {:scope "col"} "処理"] [:th {:scope "col"} "違反"]]]
      [:tbody
       (for [[i e] (map-indexed vector ledger-entries)]
         [:tr
          [:th {:scope "row"} (inc i)]
          [:td (str (or (get-in e [:record :op])
                        (when (:handoff/outcome e) :handoff)
                        "—"))]
          [:td (or (:contract-id e) "—")]
          [:td (or (:period e) (:year e) "—")]
          [:td (ui/disposition-chip (:disposition e))]
          [:td (let [vs (get-in e [:verdict :violations])]
                 (if (seq vs)
                   [:ul (for [v vs] [:li [:code (str (:rule v))]])]
                   "—"))]])]]
     (empty-note "台帳がまだ空である（この事業主の記録が一件も無い）"))])

;; ---------------------------------------------------------------------------
;; 運用の現況
;; ---------------------------------------------------------------------------

(defn- ops-section
  "One section of a `payroll.operations/report`, by id.

  The report is a VECTOR of sections and this screen looks each one up rather
  than rendering them positionally: a section added, removed or reordered in
  `payroll.operations` must not silently shift what this screen calls
  `保存先`."
  [report id]
  (first (filter #(= id (:section/id %)) (:report/sections report))))

(defn- ops-heading
  "A section's `<h2>` with its one-word answer beside it.

  The answer is a chip and the chip carries a mark, a word and an
  `aria-label` — so `未設定` survives being screenshotted, printed, and read
  by somebody who cannot see the colour. A section with no `:section/answer`
  gets a heading and no chip rather than a chip saying nothing."
  [{:section/keys [label answer]}]
  [:h2 label
   (when answer [:span {:class "ops-answer"} " " (ui/answer-chip answer)])])

(defn- ops-why [s] [:p {:class "why"} (:section/why s)])

(defn- ops-blockers
  "The flat list an operator works down, computed by
  `payroll.operations/blockers` and passed in.

  A list and not a score. There is no `n 件中 m 件 完了` anywhere on this
  screen, because a percentage is exactly the overall impression of health
  this console's namespace docstring refuses to let anybody form."
  [blockers]
  [:section {:class "dds-ext-card"}
   [:h2 (str "未了 — いま何が足りないか（" (count blockers) " 件）")]
   (if (seq blockers)
     [:table
      [:caption "未了の項目と、それが未了である理由"]
      [:thead [:tr [:th {:scope "col"} "区分"] [:th {:scope "col"} "項目"]
               [:th {:scope "col"} "理由"]]]
      [:tbody
       (for [b blockers]
         [:tr [:th {:scope "row"} (name (:blocker/section b))]
          [:td (:blocker/what b)]
          [:td {:class "why"} (:blocker/why b)]])]]
     (empty-note (str "未了の項目は無い。"
                      "これは「切り替えてよい」ではない —— "
                      "この一覧はこの配備が読んだことだけを述べる")))])

(defn- ops-notice-confirmation
  "The banner shown after a registration, and what it reads BACK.

  It reads back the counts and the coverage out of the section — that is, out
  of the store, on the request that followed the redirect — rather than
  echoing what was submitted. An echo of the form proves the form was
  submitted; reading the store back proves it was REGISTERED, which is the
  claim an operator actually needs before they file the paper away.

  And still no amount. The confirmation for a registration whose whole
  content is twelve figures says how many months are now covered and not what
  any of them is: this banner is on the screen most likely to be screenshotted
  into a ticket, and the figures are on the municipality's paper in the
  operator's hand."
  [s c]
  [:div {:class (str "state-" (name (:confirmation/kind c)))
         :role "status"
         :aria-label (:confirmation/label c)}
   [:h3 (:confirmation/label c)]
   [:p (:confirmation/message c)]
   [:p (str "いまこの事業主に登録されている通知は "
            (or (:section/registered s) "—") " 件"
            "（有効 " (or (:section/effective s) "—") " 件、"
            "差し替え済み " (or (:section/superseded s) "—") " 件）。"
            (if (seq (:section/coverage s))
              (str "年度ごとの充足は "
                   (str/join "、"
                             (for [cv (:section/coverage s)]
                               (str (:coverage/tax-year cv) " 年度 "
                                    (:coverage/months-covered cv) " / "
                                    (:coverage/months-required cv) " か月")))
                   "。")
              "")
            "金額はここには出さない —— "
            "額は区市町村の紙に印字されている")]])

(defn- ops-notice-form
  "The form that registers a 決定通知書 or a 変更通知書.

  Every control goes through `field`, which is what makes forgetting a label
  a test failure (`payroll.ui.a11y`'s `:label-missing` / `:label-for-nothing`)
  rather than a screen an operator cannot use with a keyboard. The twelve
  月割額 are twelve separate labelled number inputs inside a `<fieldset>` with
  a `<legend>`, and not one box each with a bare `6` next to it: a screen
  reader announces the legend when focus enters the group, so `翌年1月の月割額`
  is what is read out rather than `1`.

  ## The select has an empty option and it is selected by default

  A `<select>` whose first option is 決定通知書 SUBMITS 決定通知書 from a form
  nobody touched. That is a default with legal consequences — a 変更通知書
  registered as a 決定 would be checked against a 年税額 it does not carry and
  would establish twelve months it does not establish — so the empty option
  exists, it is first, and `payroll.juminzei/admit-notice` refuses it. This is
  the same rule the five tri-state facts on the 従業員 screen keep, for the
  same reason: 未登録 is not 「いいえ」.

  Values are re-filled from `form` on a refusal. Twelve transcribed figures
  are five minutes of somebody reading off a piece of paper, and a refusal
  that cleared them would teach an operator to write them somewhere else
  first."
  [s form]
  (let [r (:section/registration s)]
    [:div {:class "dds-ext-stack"}
     [:h3 "決定通知書・変更通知書を登録する"]
     [:p {:class (str "state-" (if (:registration/available? r)
                                 "available" "unavailable"))
          :aria-label (if (:registration/available? r)
                        "登録できる" "登録できない")}
      (str (if (:registration/available? r) "登録できる。" "登録できない。")
           (:registration/why r))]
     [:p (:registration/action r)]
     [:p {:class "why"}
      (str "登録しても、この actor が住民税を計算するようになるわけではない。"
           "額は区市町村が決定して紙で通知したものであり、"
           "ここに転記するのはその写しにすぎない。"
           "転記しなかった月は「零円」ではなく未登録であって、"
           "その月の run は住民税の行が「未確定」のままになる")]
     [:form {:method "post" :action notice-path :class "dds-ext-stack"}
      (field {:id "f-jn-municipality" :name "municipality" :label "区市町村"
              :required? true :value (:municipality form)
              :hint "通知を出した区市町村の名称。納入先はここで決まる"})
      (field {:id "f-jn-kind" :name "kind" :label "通知の種類"
              :required? true :value (:kind form)
              :options [{:value* "" :label* "選択されていない"}
                        {:value* "decision" :label* "特別徴収税額の決定通知書"}
                        {:value* "revision" :label* "特別徴収税額の変更通知書"}]
              :hint (str "既定値は無い。選ばないまま送ると、"
                         "決定通知書として扱われるのではなく拒否される")})
      (field {:id "f-jn-tax-year" :name "tax-year" :label "年度（YYYY）"
              :required? true :value (:tax-year form)
              :hint "徴収が6月に始まる年度"})
      (field {:id "f-jn-designated" :name "designated-number" :label "指定番号"
              :value (:designated-number form)
              :hint "区市町村が特別徴収義務者に付す番号。任意"})
      (field {:id "f-jn-reference" :name "reference" :label "通知書番号"
              :required? true :value (:reference form)
              :hint (str "訂正・再交付のときに"
                         "どの紙を差し替えるのかを名指しするため。"
                         "「/」は入れられない")})
      (field {:id "f-jn-revision" :name "revision" :label "改訂番号"
              :required? true :type :number :value (:revision form)
              :hint "初回は 0。訂正・再交付のたびに 1 つ上げる"})
      (field {:id "f-jn-replaces" :name "replaces" :label "差し替える通知の ID"
              :value (:replaces form)
              :hint (str "改訂番号が 1 以上のときは必須。"
                         "登録済みの通知の ID をそのまま入れる")})
      (field {:id "f-jn-effective-from" :name "effective-from"
              :label "変更の適用開始月" :value (:effective-from form)
              :options (into [{:value* "" :label* "指定しない（決定通知書）"}]
                             (for [[k m] (map vector juminzei/month-keys
                                              juminzei/collection-months)]
                               {:value* (name k) :label* (:month/label m)}))
              :hint (str "変更通知のみ。"
                         "通知された変更月から徴収金額を変更する")})
      (field {:id "f-jn-annual-total" :name "annual-total" :label "年税額（円）"
              :type :number :value (:annual-total form)
              :hint (str "決定通知書には必須。"
                         "12か月の月割額の合計と一致しない転記は"
                         "「安い税」ではなく書き写しの誤りであり、拒否される。"
                         "変更通知書は適用開始月以降しか載せていないので空でよい")})
      (field {:id "f-jn-registered-at" :name "registered-at"
              :label "受領・転記した日" :required? true
              :value (:registered-at form)
              :hint (str "この actor は暦を持たないので、"
                         "日付は登録された値そのものである")})
      [:fieldset
       [:legend "月割額（6月から翌年5月までの12か月）"]
       [:p {:id "f-jn-months-hint" :class "hint"}
        (str "決定通知書は12か月すべて、"
             "変更通知書は適用開始月から翌年5月までを転記する。"
             "空欄は零円ではなく未登録であり、"
             "足りないまま送ると登録そのものが拒否される")]
       (for [[k m] (map vector juminzei/month-keys juminzei/collection-months)]
         (field {:id (str "f-jn-" (name k)) :name (name k)
                 :label (str (:month/label m) "の月割額（円）")
                 :type :number :value (get form (keyword (name k)))}))]
      [:button {:type "submit" :class "dds-button dds-button-primary"}
       "この通知を登録する"]]
     [:table
      [:caption "通知書から転記する項目と、その意味"]
      [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "登録キー"]
               [:th {:scope "col"} "内容"]]]
      [:tbody
       (for [f (:registration/fields r)]
         [:tr [:th {:scope "row"} (:field/label f)]
          [:td [:code (pr-str (:field/id f))]]
          [:td (:field/why f)]])]]]))

(defn- ops-resident-tax
  "住民税 — the schedule, what is registered, what of it is still in force,
  and the form that registers one.

  This panel used to end with the sentence 「この画面にフォームは無い」, and
  the sentence was true: the store seam existed and nothing an operator could
  reach from a browser did. Now the form is here, so the panel reads back what
  is registered — the counts, which of the notices a later one replaced, and
  how many of each tax year's twelve months an effective notice covers — and
  then offers the form.

  **No amount is on this panel.** Not a 月割額, not the 年税額, not on the
  confirmation. `payroll.operations/resident-tax` is where that constraint is
  written down and this screen has nothing to add to it: it renders the
  section it is handed, and the section carries no figures to render."
  [s form confirmation]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   (when confirmation (ops-notice-confirmation s confirmation))
   [:table
    [:caption "徴収と納入の期日（登録された通知書の有無とは無関係に決まっている）"]
    [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "内容"]]]
    [:tbody
     [:tr [:th {:scope "row"} "徴収する月"]
      [:td (str/join "、" (map :month/label (:section/collection-months s)))]]
     [:tr [:th {:scope "row"} "納入期限"]
      [:td (get-in s [:section/remittance :rule/deadline])]]
     [:tr [:th {:scope "row"} "期限が休日のとき"]
      [:td (get-in s [:section/remittance :rule/holiday])]]
     [:tr [:th {:scope "row"} "納期の特例"]
      [:td (get-in s [:section/remittance :rule/special])]]
     [:tr [:th {:scope "row"} "出典"]
      [:td (str (get-in s [:section/source :source/title]) "（"
                (get-in s [:section/source :source/authority]) "・"
                (get-in s [:section/source :source/edition]) "、"
                (get-in s [:section/source :source/read-at]) " 取得）")]]]]
   ;; `—（数えていない）` and never `0 件`. On an unreadable history
   ;; `payroll.operations/resident-tax` answers nil rather than zero, and a
   ;; screen that rendered the nil as a zero would put the measurement back.
   (let [n (fn [v] (if (nil? v) "—（数えていない）" (str v " 件")))]
     [:table
      [:caption "登録されている通知の件数（金額はここには出さない）"]
      [:thead [:tr [:th {:scope "col"} "区分"] [:th {:scope "col"} "件数"]]]
      [:tbody
       [:tr [:th {:scope "row"} "登録済み（差し替えられたものを含む）"]
        [:td {:class "amt"} (n (:section/registered s))]]
       [:tr [:th {:scope "row"} "有効（他の通知に差し替えられていない）"]
        [:td {:class "amt"} (n (:section/effective s))]]
       [:tr [:th {:scope "row"} "差し替え済み（訂正されて、なお残してある）"]
        [:td {:class "amt"} (n (:section/superseded s))]]]])
   (if (seq (:section/notices s))
     [:table
      [:caption "登録されている決定通知書・変更通知書"]
      [:thead [:tr [:th {:scope "col"} "区市町村"] [:th {:scope "col"} "年度"]
               [:th {:scope "col"} "種類"] [:th {:scope "col"} "改訂番号"]
               [:th {:scope "col"} "登録月数"]
               [:th {:scope "col"} "変更の適用開始月"]
               [:th {:scope "col"} "差し替えられたか"]]]
      [:tbody
       (for [n (:section/notices s)]
         [:tr [:th {:scope "row"} (:notice/municipality n)]
          [:td (:notice/tax-year n)]
          [:td (str (:notice/kind n))]
          [:td {:class "amt"} (str (:notice/revision n))]
          [:td (str (:notice/months-registered n) " / "
                    (:notice/months-required n) " か月")]
          [:td (if (:notice/effective-from n)
                 (name (:notice/effective-from n))
                 "—")]
          ;; a word and not a colour, and not an empty cell for `no`: an
          ;; operator reading this table is deciding which paper is current
          [:td {:class (str "state-" (if (:notice/superseded? n)
                                       "superseded" "effective"))}
           (if (:notice/superseded? n)
             "差し替えられた（記録として残してある）"
             "有効")]])]]
     (empty-note (str "決定通知書が一件も登録されていない。"
                      "通知が無い月は「住民税ゼロ」ではなく「わからない」である")))
   (when (seq (:section/coverage s))
     [:table
      [:caption "年度ごとに、通知が月割額を与えている月数（金額ではない）"]
      [:thead [:tr [:th {:scope "col"} "年度"] [:th {:scope "col"} "通知のある月"]
               [:th {:scope "col"} "通知の無い月"] [:th {:scope "col"} "理由"]]]
      [:tbody
       (for [c (:section/coverage s)]
         [:tr [:th {:scope "row"} (:coverage/tax-year c)]
          [:td {:class "amt"} (str (:coverage/months-covered c) " / "
                                   (:coverage/months-required c) " か月")]
          [:td (if (seq (:coverage/uncovered-months c))
                 (str/join "、" (map name (:coverage/uncovered-months c)))
                 "—")]
          [:td {:class "why"} (:coverage/why c)]])]])
   (ops-notice-form s form)])

(defn- ops-overtime [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   [:table
    [:caption "割増率と、その条文"]
    [:thead [:tr [:th {:scope "col"} "区分"] [:th {:scope "col"} "割増率"]
             [:th {:scope "col"} "条文"]]]
    [:tbody
     (for [c (:section/categories s)]
       [:tr [:th {:scope "row"} (:category/label c)]
        [:td {:class "amt"} (str (:category/rate c))]
        [:td (:category/provision c)]])]]
   [:table
    [:caption "時間単価の基礎から除外する手当（限定列挙。どれに当たるかは事業主が登録する）"]
    [:thead [:tr [:th {:scope "col"} "手当"]]]
    [:tbody
     ;; `:excluded/label` and never `:excluded/key`: the key does not survive
     ;; the report. `payroll.sensitive` blocks a key whose tail is `key` — it
     ;; is matching `:envelope/key` — so the redactor drops it, and a cell
     ;; reading it would render blank for every row.
     (for [a (:section/excluded-allowances s)]
       [:tr [:th {:scope "row"} (:excluded/label a)]])]]])

(defn- ops-rates [s]
  (let [t (:section/withholding-table s)]
    [:section {:class "dds-ext-card"}
     (ops-heading s)
     (ops-why s)
     [:table
      [:caption "この配備が読んだ出典"]
      [:thead [:tr [:th {:scope "col"} "資料"] [:th {:scope "col"} "発行"]
               [:th {:scope "col"} "取得日"]]]
      [:tbody
       (for [src (:section/sources s)]
         [:tr [:th {:scope "row"} (:source/title src)]
          [:td (:source/authority src)]
          [:td (or (:source/read-at src) (:source/retrieved-at src) "—")]])]]
     [:table
      [:caption "源泉徴収税額表（月額表）— 転記した量と、その版"]
      [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "値"]]]
      [:tbody
       [:tr [:th {:scope "row"} "転記済みか"]
        [:td (ui/yes-no-chip (:table/transcribed? t) "月額表の転記")]]
       [:tr [:th {:scope "row"} "帯の数"] [:td {:class "amt"} (str (:table/bands t))]]
       [:tr [:th {:scope "row"} "印字されている閾値行"]
        [:td {:class "amt"} (str (:table/thresholds t))]]
       [:tr [:th {:scope "row"} "適用期間"]
        [:td (str (:table/effective-from t) " 〜 " (:table/effective-to t))]]
       [:tr [:th {:scope "row"} "元となった資料の SHA-256"]
        [:td [:code (str (:table/sha256 t))]]]
       [:tr [:th {:scope "row"} "取得日"] [:td (str (:table/retrieved-at t))]]]]
     (if (seq (:table/not-transcribed t))
       [:table
        [:caption "同じ資料のうち、まだ読めていない部分"]
        [:thead [:tr [:th {:scope "col"} "読めていないもの"]
                 [:th {:scope "col"} "その結果"]]]
        [:tbody
         (for [g (:table/not-transcribed t)]
           [:tr [:th {:scope "row"} (:gap/what g)]
            [:td {:class "why"} (:gap/why g)]])]]
       (empty-note "この資料に未転記の部分は無い"))
     [:table
      [:caption "年末調整の年税額に要る表"]
      [:thead [:tr [:th {:scope "col"} "表"] [:th {:scope "col"} "読んだか"]
               [:th {:scope "col"} "理由"]]]
      [:tbody
       (for [a (:section/annual-tables s)]
         [:tr [:th {:scope "row"} (:table/label a)]
          [:td (ui/yes-no-chip (:table/read? a) (:table/label a))]
          [:td {:class "why"} (:table/why a)]])]]
     [:table
      [:caption "拒否になる答え（近い値で代用されることはない）"]
      [:thead [:tr [:th {:scope "col"} "拒否"]]]
      [:tbody
       (for [r (:section/refusals s)]
         [:tr [:th {:scope "row"} [:code (pr-str r)]]])]]]))

(defn- ops-artifacts
  "The artifacts, each with a link that actually fetches it.

  The 全銀 row is the reason this table has a download column at all: the
  fixed-width file is the one artifact an operator has to be able to reach
  from the screen that tells them the console can produce it, and until this
  change the only place it could be reached was the exports form. A screen
  that says a file exists and cannot hand it over is a screen that says it
  twice.

  The link is a GET to `payroll.ui.views/export-path`, which is the path the
  console's router serves and `payroll.operations` reports — one def, three
  readers. Every format the artifact declares gets its own link, because a
  format is not a rendering of another one: 全銀's fixed-width is Shift_JIS
  bytes and its CSV is text with a different trailer."
  [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   [:table
    [:caption "出力できる書類と、その取り出し先"]
    [:thead [:tr [:th {:scope "col"} "書類"] [:th {:scope "col"} "法定様式か"]
             [:th {:scope "col"} "取り出す"] [:th {:scope "col"} "理由"]]]
    [:tbody
     (for [a (:section/artifacts s)]
       [:tr [:th {:scope "row"} (:artifact/label a)]
        [:td [:span {:class "state-not-statutory" :aria-label "法定様式ではない"}
              "法定様式ではない"]]
        [:td (for [fmt (:artifact/formats a)]
               [:a {:class "dds-button dds-button-text"
                    :href (str (:section/export-path s)
                               "?kind=" (name (:artifact/id a))
                               "&format=" (name fmt))}
                (name fmt)])]
        [:td {:class "why"} (:artifact/why a)]])]]
   [:p {:class "hint"}
    (str "取り出せるかどうかは run と登録の状態で決まる。"
         "登録が足りない run がある書類は、"
         "一部だけのファイルではなく理由を述べる 400 を返す")]])

(defn- ops-moneyforward [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   [:table
    [:caption "取込の境界"]
    [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "値"]]]
    [:tbody
     [:tr [:th {:scope "row"} "読む列の数"]
      [:td {:class "amt"} (str (:section/columns s))]]
     [:tr [:th {:scope "row"} "実際のエクスポートで確認済みの列"]
      [:td {:class "amt"} (str (:section/columns-verified s))]]
     [:tr [:th {:scope "row"} "対応する概念が無い列"]
      [:td {:class "amt"} (str (count (:section/no-counterpart s)))]]]]
   (if (seq (:section/cycles s))
     [:table
      [:caption "記録された並行運用サイクル"]
      [:thead [:tr [:th {:scope "col"} "期間"] [:th {:scope "col"} "月区分"]
               [:th {:scope "col"} "突合"] [:th {:scope "col"} "比較した run"]
               [:th {:scope "col"} "差分"] [:th {:scope "col"} "承認"]]]
      [:tbody
       (for [c (:section/cycles s)]
         [:tr [:th {:scope "row"} (:cycle/period c)]
          [:td (str (name (or (:cycle/month-kind c) :unknown))
                    (when (:cycle/month-reason c)
                      (str "（" (:cycle/month-reason c) "）")))]
          [:td (ui/yes-no-chip (:cycle/reconciled? c)
                               (str (:cycle/period c) " の突合"))]
          [:td {:class "amt"} (str (:cycle/compared c))]
          [:td {:class "amt"} (str (:cycle/differences c))]
          [:td (or (:cycle/approved-by c) "—")]])]]
     (empty-note (str "実際のエクスポートから記録されたサイクルが一件も無い。"
                      "突合できた run が 0 件のレポートは「差分が無い」ではない")))
   (if-let [l (:section/latest s)]
     [:table
      [:caption "直近の突合（この画面を開いた時点の入力に対するもの）"]
      [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "値"]]]
      [:tbody
       [:tr [:th {:scope "row"} "比較できた run"]
        [:td {:class "amt"} (str (:reconcile/compared l))]]
       [:tr [:th {:scope "row"} "読んだ行"]
        [:td {:class "amt"} (str (count (:reconcile/rows l)))]]
       [:tr [:th {:scope "row"} "一致したか"]
        [:td (ui/yes-no-chip (:reconcile/reconciled? l) "直近の突合")]]
       [:tr [:th {:scope "row"} "理由"] [:td {:class "why"} (:reconcile/why l)]]]]
     (empty-note "この画面を開いた時点で、突合の入力は与えられていない"))])

(defn- ops-cutover [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   [:p (str "連続して突合できているサイクル: "
            (get-in s [:section/progress :progress/text])
            "。" (:section/why s))]
   [:table
    [:caption "並行運用を終える条件と、その現況"]
    [:thead [:tr [:th {:scope "col"} "条件"] [:th {:scope "col"} "状態"]
             [:th {:scope "col"} "理由"]]]
    [:tbody
     (for [c (:section/conditions s)]
       [:tr [:th {:scope "row"} (:gate/label c)]
        [:td (ui/met-chip (:gate/met? c) (:gate/label c))]
        [:td {:class "why"} (:gate/why c)]])]]
   (if (seq (:section/blockers s))
     [:p (str "止めているのは: "
              (str/join "、" (map name (:section/blockers s))))]
     (empty-note (str "この gate を止めている条件は無い。"
                      "これは切り替えてよいという判断そのものではない —— "
                      "その判断は事業主のものである")))])

(defn- ops-store [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   (if (seq (:section/streams s))
     [:table
      [:caption "chain ごとの読み出し（件数は読めた分であって、届け出た件数ではない）"]
      [:thead [:tr [:th {:scope "col"} "chain"] [:th {:scope "col"} "読めた件数"]
               [:th {:scope "col"} "末尾まで辿れたか"]]]
      [:tbody
       (for [c (:section/streams s)]
         [:tr [:th {:scope "row"} (str (name (:stream c)))]
          [:td {:class "amt"} (str (:entries c))]
          [:td (ui/yes-no-chip (:complete? c) (str (name (:stream c)) " の走査"))]])]]
     (empty-note (str "この配備の store は chain を報告しない。"
                      "報告が無いことは「健全である」ではない")))
   [:table
    [:caption "保存先について述べられていること"]
    [:thead [:tr [:th {:scope "col"} "項目"] [:th {:scope "col"} "値"]]]
    [:tbody
     [:tr [:th {:scope "row"} "backend"]
      [:td (if (:section/mode s) (name (:section/mode s)) "—")]]
     [:tr [:th {:scope "row"} "再起動を越えるか"]
      [:td (ui/yes-no-chip (:section/survives-restart? s) "再起動後の残存")]]
     [:tr [:th {:scope "row"} "件数は下限か"]
      [:td (ui/yes-no-chip (:section/entries-are-a-floor? s) "件数が下限であること")]]
     [:tr [:th {:scope "row"} "封筒と blind index の鍵"]
      [:td (if (:section/keys-separated s)
             (str (name (:section/keys-separated s)))
             "—")]]]]])

(defn- ops-projection [s]
  [:section {:class "dds-ext-card"}
   (ops-heading s)
   (ops-why s)
   (if (seq (:section/tables s))
     [:table
      [:caption "投影先の表"]
      [:thead [:tr [:th {:scope "col"} "表"] [:th {:scope "col"} "状態"]
               [:th {:scope "col"} "理由"]]]
      [:tbody
       (for [t (:section/tables s)]
         [:tr [:th {:scope "row"} (str (:table t))]
          [:td (str (name (or (:status t) :unknown)))]
          [:td {:class "why"} (or (:why t) "—")]])]]
     (empty-note (str "この配備は catalog を持っていないので、"
                      "表の状態を報告できない")))
   (if-let [pf (:section/preflight s)]
     [:div {:class "dds-ext-stack"}
      [:h3 "投影を作れる状態か（要求は一切送っていない）"]
      [:p {:class (str "state-" (if (:preflight/ready? pf) "ready" "not-ready"))
           :aria-label (if (:preflight/ready? pf) "作れる" "作れない")}
       (str (if (:preflight/ready? pf) "作れる。" "作れない。")
            (:preflight/why pf))]
      (if (seq (:preflight/missing pf))
        [:table
         [:caption "足りない設定"]
         [:thead [:tr [:th {:scope "col"} "環境変数"] [:th {:scope "col"} "内容"]
                  [:th {:scope "col"} "秘密か"]]]
         [:tbody
          (for [c (:preflight/missing pf)]
            [:tr [:th {:scope "row"} [:code (:config/env c)]]
             [:td (:config/label c)]
             [:td (ui/yes-no-chip (:config/secret? c) (:config/label c))]])]]
        (empty-note "設定は揃っている（揃っていることは、作れることではない）"))
      [:table
       [:caption "トークンに要る権限と、実測した結果"]
       [:thead [:tr [:th {:scope "col"} "対象"] [:th {:scope "col"} "権限"]
                [:th {:scope "col"} "実測"] [:th {:scope "col"} "理由"]]]
       [:tbody
        (for [r (:preflight/permissions pf)]
          [:tr [:th {:scope "row"} (:permission/scope r)]
           [:td (:permission/level r)]
           [:td (str (name (or (:permission/observed r) :unknown)))]
           [:td {:class "why"} (:permission/why r)]])]]
      (when-let [b (:preflight/blocker pf)]
        [:p {:class "why"} (str (:blocker/diagnosis b) "。"
                                (:blocker/resolution b))])]
     (empty-note (str "この配備は投影の事前確認を行っていない。"
                      "未実施は合格ではない")))])

(defn operations
  "運用の現況 — the whole report as a screen.

  The same `payroll.operations/report` `GET /api/operations` serves, rendered
  rather than re-derived. This screen holds no list of its own: the artifacts
  come from the report, the cutover conditions come from the report, and the
  未了 list is `payroll.operations/blockers` over the same value. A screen
  with its own copy of any of those is the copy that drifts, which is the
  failure `payroll.operations`' own docstring exists to prevent.

  A missing report is a STATE and is rendered as one. It happens when the
  caller reaches this view without a store to read — and a blank screen there
  would be indistinguishable from a deployment with nothing wrong."
  [{:keys [operations operations-blockers form notice-confirmation]}]
  [:div {:class "dds-ext-stack"}
   [:h1 "運用の現況"]
   (if (nil? operations)
     [:div {:class "dds-ext-card"}
      (empty-note (str "この配備の現況を組み立てられなかった。"
                       "画面が空であることは「問題が無い」ではない —— "
                       "GET /api/operations が同じ答えを返す"))]
     [:div {:class "dds-ext-stack"}
      ;; Whose current state this is. Named on the screen and not only in the
      ;; nav, because this is the page that gets screenshotted into a ticket
      ;; and a report with no employer on it is one nobody can file against.
      [:p (str "事業主: " (:report/employer operations))]
      [:p (:report/why operations)]
      [:p {:class "hint"}
       (str "この画面は GET /api/operations と同じ値を描いている。"
            "画面に出せない鍵は落としてあり、落とした数は "
            (:report/redacted-keys operations) " 件である")]
      (ops-blockers (or operations-blockers []))
      (ops-resident-tax (ops-section operations :resident-tax)
                        (or form {})
                        notice-confirmation)
      (ops-overtime (ops-section operations :overtime))
      (ops-rates (ops-section operations :rates))
      (ops-artifacts (ops-section operations :artifacts))
      (ops-moneyforward (ops-section operations :moneyforward))
      (ops-cutover (ops-section operations :cutover))
      (ops-store (ops-section operations :store))
      (ops-projection (ops-section operations :projection))])])

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(def views
  "Every screen. The nav, the dispatch and the accessibility suite all read
  this vector — see the namespace docstring."
  [{:view/key :overview :view/path "/console" :view/label "現況"
    :view/render overview}
   {:view/key :employees :view/path "/console/employees" :view/label "従業員・契約"
    :view/render employees}
   {:view/key :run :view/path "/console/run" :view/label "給与計算"
    :view/render run}
   {:view/key :exports :view/path "/console/exports" :view/label "出力物"
    :view/render exports}
   {:view/key :import :view/path "/console/mf" :view/label "MoneyForward 突合"
    :view/render mf-import}
   {:view/key :ledger :view/path "/console/ledger" :view/label "監査台帳"
    :view/render ledger}
   {:view/key :operations :view/path "/console/operations"
    :view/label "運用の現況" :view/render operations}])

(def by-path (into {} (map (juxt :view/path identity)) views))
(def by-key (into {} (map (juxt :view/key identity)) views))

(defn nav
  "The navigation, generated from `views`.

  `aria-current=\"page\"` rather than a class alone: which screen you are on
  is a state, and this repository's rule about state is that it must not be
  colour."
  [current]
  [:nav {:class "console-nav" :aria-label "コンソールの画面"}
   [:ul
    (for [{:view/keys [key path label]} views]
      [:li [:a (cond-> {:href path :class "dds-button dds-button-text"}
                 (= key current) (assoc :aria-current "page"
                                        :class "dds-button dds-button-text is-current"))
            label]])]])

(defn render
  "One view's `<main>` content. An unknown view key is an error page and never
  a blank one — a console that rendered nothing for a route it does not have
  would look like a screen with no data on it."
  [view-key ctx]
  (if-let [v (get by-key view-key)]
    ((:view/render v) ctx)
    [:div [:h1 "その画面は無い"]
     [:p (str "画面 " (pr-str view-key) " はこの console に存在しない")]]))
