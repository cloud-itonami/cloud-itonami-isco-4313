(ns payroll.artifact.payslip
  "給与支払明細書 — one run as a document an employee reads, and as JSON.

  ## What this document does NOT claim to be

  所得税法 第二百三十一条第一項 obliges the payer of 給与等 to give the
  recipient a 支払明細書, and a ministerial rule prescribes what it must
  contain. **This repository has read neither.** So this artifact does not
  claim to be a 法定の支払明細書, and says so on its face — `disclaimer`
  below is rendered into the document itself and into the JSON, not left in a
  docstring where the person handed the paper will never see it.

  That is the same discipline `payroll.shakai-hoken` applies to 協会けんぽ's
  rate: name the instrument, record that it was not read, and refuse the
  claim that depends on it. The alternative — printing a plausible-looking
  明細書 with a title that asserts statutory compliance — would be the most
  expensive kind of wrong this repository can be, because the recipient has
  no way to check and the title tells them they do not have to.

  ## Every line says how it is known

  A payslip built from `payroll.meisai` carries a `payroll.provenance` figure
  per line, and this renderer never reduces one to its number. A line whose
  figure is `:held` prints 未確定 and its reason **in the amount column**, so
  the reason is where the number would have been rather than in a footnote.
  A line that is `:not-applicable` prints 該当なし and is not a zero.

  ## It is hiccup, not HTML

  `->hiccup` returns a hiccup tree with no dependency on any renderer or
  design system. `payroll.ui.render` puts it in a document; the accessibility
  invariants in `payroll.ui.a11y` are asserted against the tree; a test can
  read the structure without parsing markup. A namespace that emitted a
  string would make all three of those into string matching."
  (:require [clojure.string :as str]
            [payroll.artifact.text :as text]
            [payroll.provenance :as prov]))

(def disclaimer
  "What this document is not, in the words that go on the document.

  Two separate refusals, because they fail in different directions: the FORM
  is not certified (nobody here read what the rule requires it to contain),
  and the AMOUNTS are mostly not certified (nobody here read the tax tables
  or the insurance rates). An operator who fixed only one would still be
  handing out a document this repository cannot stand behind."
  {:disclaimer/form-not-statutory
   (str "この書面は法定の給与支払明細書ではない。"
        "交付義務を定める 所得税法 第二百三十一条第一項 も、"
        "記載事項を定める省令も、この repository は読んでいない。"
        "様式が法令の要件を満たすかどうかは未検査である")
   :disclaimer/amounts-not-certified
   (str "金額の多くはこの repository が計算したものではない。"
        "源泉所得税の税額表（所得税法 別表第二・別表第五）、"
        "健康保険・介護保険の保険料率、雇用保険率の告示は、いずれも未読である。"
        "各行の「根拠」欄がその行を誰が出した数字かを示す")
   :disclaimer/statutes-named-as-unread
   ["所得税法 第二百三十一条第一項（給与等の支払明細書の交付義務）"
    "所得税法 別表第二・別表第五（源泉徴収税額表）"
    "健康保険法 第百六十条第一項（都道府県単位保険料率の告示）"
    "健康保険法 第百六十条第十六項（介護保険料率）"
    "労働保険徴収法 第十二条第四項（雇用保険率の変更告示）"]})

(defn record
  "One run as a payslip value.

    {:employer   {:client-id :name}
     :contract   the REGISTERED contract record
     :period     the pay period string
     :meisai     `payroll.meisai/lines`}

  Pure data. `->hiccup` and `->json` both read this, so the document and the
  machine-readable file cannot drift into disagreeing about a figure."
  [{:keys [employer contract period meisai]}]
  {:payslip/employer-id (:client-id employer)
   :payslip/employer-name (:name employer)
   :payslip/worker (:contract/worker contract)
   :payslip/contract-id (:contract/id contract)
   :payslip/role (:contract/role contract)
   :payslip/period period
   :payslip/currency (or (:contract/currency contract) "JPY")
   :payslip/gross (:meisai/gross meisai)
   :payslip/deductions (:meisai/deductions meisai)
   :payslip/deduction-total (:meisai/deduction-total meisai)
   :payslip/net (:meisai/net meisai)
   :payslip/basis (:meisai/basis meisai)
   :payslip/disposition (:meisai/disposition meisai)
   :payslip/coverage (text/coverage (:meisai/figures meisai))
   :payslip/disclaimer disclaimer})

;; ---------------------------------------------------------------------------
;; The document
;; ---------------------------------------------------------------------------

(defn- yen
  "An amount as it appears in a Japanese money column, or the marker.

  Grouped by thousands with a plain comma, which is the separator every
  Japanese payslip uses and which `payroll.artifact.text/csv-cell` will quote
  when the same figure reaches a CSV — so the document and the export can use
  the same function without the export corrupting a row."
  [n]
  (if-not (number? n)
    n
    (let [neg? (neg? n)
          digits (str (if neg? (- n) n))
          grouped (->> (reverse digits)
                       (partition-all 3)
                       (map #(apply str (reverse %)))
                       reverse
                       (str/join ","))]
      (str (when neg? "-") grouped " 円"))))

(defn amount-cell
  "The amount column for one figure, as hiccup.

  The three ways a figure can fail to be a number are three different
  sentences and each one goes HERE, in the column where the amount would
  have been. A payslip that printed a blank and explained it at the bottom
  is a payslip whose blank is read as zero on the way past.

  `:aria-label` restates the state in words for a screen reader, because the
  visual difference between 未確定 and a number is also carried by weight and
  colour, and neither of those reaches a screen reader."
  [f]
  (let [p (:figure/provenance f)]
    (case p
      (:derived :declared :imported)
      [:td {:class (str "amt prov-" (name p))
            :aria-label (str (:figure/label f) " "
                             (yen (:figure/amount f))
                             (when (prov/unverified? f) "（当リポジトリ未検算）"))}
       (yen (:figure/amount f))
       (when (prov/unverified? f)
         [:span {:class "prov-mark" :title (or (:figure/why f) "")} "※未検算"])]

      :not-applicable
      [:td {:class "amt prov-not-applicable"
            :aria-label (str (:figure/label f) " 該当なし。" (:figure/why f))}
       [:span {:class "state-word"} text/not-applicable-cell]]

      ;; :unknown and :held. The reason is in the cell, not in a footnote.
      [:td {:class (str "amt prov-" (name p))
            :aria-label (str (:figure/label f) " "
                             (if (= :held p) "保留" "未確定")
                             "。" (:figure/why f))}
       [:span {:class "state-word"} text/unknown-cell]
       [:p {:class "state-why"} (:figure/why f)]])))

(defn- line-row [label figure provision]
  [:tr
   [:th {:scope "row"} label
    (when provision [:span {:class "provision"} provision])]
   (amount-cell figure)
   [:td {:class "source"} (or (:figure/source figure) "—")]])

(defn ->hiccup
  "The payslip as a hiccup tree. No design system, no renderer, no strings of
  markup — see the namespace docstring.

  Structure is semantic and is what `payroll.ui.a11y` asserts against: one
  `h1`, a `caption` on every table, `scope` on every header cell, and no
  state conveyed by class alone."
  [{:payslip/keys [employer-name worker contract-id period gross deductions
                   deduction-total net disposition basis disclaimer
                   coverage] :as _slip}]
  [:article {:class "payslip" :lang "ja"}
   [:h1 "給与支払明細書（法定様式ではない）"]
   [:dl {:class "payslip-head"}
    [:div [:dt "支払者"] [:dd (or employer-name "—")]]
    [:div [:dt "従業員"] [:dd (or worker "—")]]
    [:div [:dt "雇用契約"] [:dd (or contract-id "—")]]
    [:div [:dt "対象期間"] [:dd (or period "—")]]
    [:div [:dt "この run の処理"]
     [:dd {:class (str "disposition disposition-" (name (or disposition :unknown)))}
      (case disposition
        :commit "承認（記録済み）"
        :request-approval "人の署名待ち — 未払"
        :hold "保留 — 支払ってはならない"
        "未処理")]]]

   [:table {:class "payslip-lines"}
    [:caption "支給"]
    [:thead [:tr [:th {:scope "col"} "項目"]
             [:th {:scope "col" :class "amt"} "金額"]
             [:th {:scope "col"} "根拠"]]]
    [:tbody (line-row "総支給額" gross nil)]]

   [:table {:class "payslip-lines"}
    [:caption "控除"]
    [:thead [:tr [:th {:scope "col"} "項目"]
             [:th {:scope "col" :class "amt"} "金額"]
             [:th {:scope "col"} "根拠"]]]
    [:tbody
     (for [{:line/keys [label figure provision]} deductions]
       (line-row label figure provision))]
    [:tfoot [:tr {:class "total"}
             [:th {:scope "row"} "控除合計"]
             (amount-cell deduction-total)
             [:td {:class "source"} (or (:figure/source deduction-total) "—")]]]]

   [:table {:class "payslip-lines payslip-net"}
    [:caption "差引支給額"]
    [:thead [:tr [:th {:scope "col"} "項目"]
             [:th {:scope "col" :class "amt"} "金額"]
             [:th {:scope "col"} "根拠"]]]
    [:tbody (line-row "差引支給額" net nil)]]

   [:section {:class "payslip-basis"}
    [:h2 "総支給額の基礎"]
    [:p (:chingin/why basis)]
    (when (seq (:chingin/unaccounted basis))
      [:table
       [:caption "この金額に含まれていない登録事実"]
       [:thead [:tr [:th {:scope "col"} "事実"]
                [:th {:scope "col"} "登録値"]
                [:th {:scope "col"} "要る条文（未読）"]]]
       [:tbody
        (for [u (:chingin/unaccounted basis)]
          [:tr [:th {:scope "row"} (:premium/label u)]
           [:td (pr-str (:premium/registered u))]
           [:td (:premium/provision-not-read u)]])]])]

   [:section {:class "payslip-disclaimer"}
    [:h2 "この書面について"]
    [:p (:disclaimer/form-not-statutory disclaimer)]
    [:p (:disclaimer/amounts-not-certified disclaimer)]
    [:details
     [:summary "この repository が読んでいない法令・告示"]
     [:ul (for [s (:disclaimer/statutes-named-as-unread disclaimer)]
            [:li s])]]
    [:p {:class "coverage"}
     (str "この明細の項目数 " (:coverage/figures coverage)
          "、うち当リポジトリが条文から計算したもの "
          (:coverage/certified coverage)
          "、検算していないもの " (:coverage/unverified coverage))]]])

;; ---------------------------------------------------------------------------
;; The machine-readable form
;; ---------------------------------------------------------------------------

(def json-line-order
  "The order deduction lines appear in the JSON. Fixed here rather than taken
  from the data, so a reordering of `payroll.meisai/deduction-lines` shows up
  as a failing test rather than as a silently different file."
  [:income-tax-withheld
   :health-insurance-withheld
   :care-insurance-withheld
   :employees-pension-withheld
   :employment-insurance-withheld])

(defn ->json
  "The payslip as deterministic JSON.

  Key order is this function's, top to bottom, and the deduction lines are
  emitted in `json-line-order` rather than in whatever order the input
  happened to hold — see there."
  [{:payslip/keys [employer-id employer-name worker contract-id period currency
                   gross deductions deduction-total net disposition basis
                   coverage disclaimer]}]
  (let [by-key (into {} (map (juxt :line/key identity)) deductions)]
    (text/json-document
     [[:document_type "payslip"]
      [:statutory_form false]
      [:statutory_form_why (:disclaimer/form-not-statutory disclaimer)]
      [:employer_id employer-id]
      [:employer_name employer-name]
      [:worker worker]
      [:contract_id contract-id]
      [:period period]
      [:currency currency]
      [:disposition disposition]
      [:gross (text/json-object-of (text/figure->json-pairs gross))]
      [:deductions
       (text/json-object-of
        (for [k json-line-order
              :let [line (get by-key k)]]
          [k (text/json-object-of
              (text/figure->json-pairs (:line/figure line)))]))]
      [:deduction_total (text/json-object-of
                         (text/figure->json-pairs deduction-total))]
      [:net (text/json-object-of (text/figure->json-pairs net))]
      [:wage_basis
       (text/json-object-of
        [[:answer (:chingin/answer basis)]
         [:certifiable (boolean (:chingin/certifiable? basis))]
         [:wage_type (:chingin/wage-type basis)]
         [:reads_timesheets (:chingin/reads-timesheets? basis)]
         [:why (:chingin/why basis)]
         [:unaccounted (vec (for [u (:chingin/unaccounted basis)]
                              (text/json-object-of
                               [[:key (:premium/key u)]
                                [:label (:premium/label u)]
                                [:registered (str (:premium/registered u))]
                                [:provision_not_read
                                 (:premium/provision-not-read u)]])))]])]
      [:coverage (text/json-object-of
                  [[:figures (:coverage/figures coverage)]
                   [:certified_by_this_repository (:coverage/certified coverage)]
                   [:unverified (:coverage/unverified coverage)]])]
      [:statutes_named_as_unread
       (vec (:disclaimer/statutes-named-as-unread disclaimer))]])))
