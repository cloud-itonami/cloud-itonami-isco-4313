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
   {:artifact/key :bank-transfer :artifact/label "振込データ"
    :artifact/formats [:csv :json] :artifact/statutory? false
    :artifact/why "全銀協の総合振込フォーマットは未読。独自列の CSV のみ"}
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
    [:form {:method "get" :action "/console/export" :class "dds-ext-stack"}
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
                       {:value* "html" :label* "印刷用 HTML"}]})
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
    [:h2 "全銀フォーマット — 出力しない"]
    [:p {:class "state-unsupported" :aria-label "全銀フォーマットは未対応"}
     (:zengin/why zengin)]
    [:table
     [:caption "出力するために、この repository がまだ読んでいないもの"]
     [:thead [:tr [:th {:scope "col"} "必要なもの"] [:th {:scope "col"} "なぜ"]]]
     [:tbody
      (for [n (:zengin/also-needed zengin)]
        [:tr [:th {:scope "row"} (:needed/what n)] [:td (:needed/why n)]])]]
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
                "なし")]])]]]])

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
    :view/render ledger}])

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
