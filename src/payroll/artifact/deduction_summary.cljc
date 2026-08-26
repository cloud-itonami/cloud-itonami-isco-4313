(ns payroll.artifact.deduction-summary
  "控除額集計 — what one employer withheld in one period, per scheme.

  This is the file an operator reads before remitting. Every line is money
  the employer is holding on somebody else's behalf: 所得税法 第百八十三条第一項
  obliges the payer to remit the income tax 徴収の日の属する月の翌月十日まで,
  and 健保法 第百六十一条第二項 / 厚年法 第八十二条第二項 make the employer
  liable to the insurer for the contributions. A summary that under-reported
  would be an under-remittance.

  ## A total refuses rather than under-reports

  `payroll.provenance/total` refuses when any contributing figure is unknown
  or held, and this namespace does nothing to soften that. A period where one
  of three employees' 健康保険料 was never observed produces a 健康保険料 total
  of `未確定` and NOT the sum of the two that were.

  That is the same rule `payroll.shakai-hoken/withheld-total` states for the
  journal side — 'a sum over schemes where some declared nothing is not the
  total, and a caller handed a number cannot tell'. Here it matters more,
  because the journal entry is refused outright while a summary is a number
  somebody types into a payment.

  ## Held runs are counted, and are not in the totals

  A run that was held withheld nothing, because it was never paid. It appears
  in `:summary/excluded` with its disposition, so the count of runs in the
  period and the count of runs in the totals are both visible. A summary that
  silently dropped them would answer `we withheld this much` for a month in
  which somebody was not paid at all, and nothing in the file would say so."
  (:require [payroll.artifact.text :as text]
            [payroll.meisai :as meisai]
            [payroll.provenance :as prov]))

(def scheme-order
  "The order the schemes are summed and printed in — payslip order, which is
  `payroll.meisai/deduction-lines`' order, taken from it rather than retyped
  so the two cannot drift."
  (mapv :line/key meisai/deduction-lines))

(def remittance
  "Who each withholding is owed to and under which provision. Named so a
  reader of the summary knows what the number is FOR; no deadline is computed
  here, because that needs a calendar this actor does not have —
  `kotoba.taxlaw` supplies the income-tax deadline on the verdict and it is
  carried through rather than derived."
  {:income-tax-withheld
   {:remit/to "国（税務署）" :remit/provision "所得税法 第百八十三条第一項"}
   :health-insurance-withheld
   {:remit/to "保険者（全国健康保険協会 等）" :remit/provision "健康保険法 第百六十一条第二項"}
   :care-insurance-withheld
   {:remit/to "保険者" :remit/provision "健康保険法 第百六十一条第二項"}
   :employees-pension-withheld
   {:remit/to "実施機関（日本年金機構）" :remit/provision "厚生年金保険法 第八十二条第二項"}
   :employment-insurance-withheld
   {:remit/to "国（労働保険）" :remit/provision "労働保険徴収法 第三十一条第三項"}
   ;; 住民税 is the one owed to a MUNICIPALITY rather than to the state or an
   ;; insurer, which is why it cannot be summed with the others: 「区市町村ごと
   ;; にとりまとめ、区市町村から送付される納入書で納入します」. The
   ;; per-municipality split is `payroll.juminzei/municipality-payable`.
   :resident-tax-withheld
   {:remit/to "従業員の住所地の区市町村（区市町村ごとに納入）"
    :remit/provision "地方税法 第三百二十一条の五（東京都の手引きが引く条文）"}})

(def labels
  (into {} (map (juxt :line/key :line/label)) meisai/deduction-lines))

(defn summarise
  "Every run in one period for one employer → per-scheme totals.

    {:employer   {:client-id :name}
     :period     the period string
     :runs       [{:contract-id :worker :meisai} …]}

  Returns

    {:summary/employer-id :summary/period
     :summary/run-count      every run handed in
     :summary/included       runs that committed and are in the totals
     :summary/excluded       [{:contract-id :disposition}] — held or awaiting
     :summary/schemes        [{:scheme/key :scheme/label :scheme/figure
                               :scheme/remit-to :scheme/provision
                               :scheme/contributors n}]
     :summary/total          every scheme summed, or a refusal}

  `:scheme/contributors` is the evidence floor: a 健康保険料 total of 0 over
  0 contributors is an empty period and a 健康保険料 total of 0 over 3
  contributors is three people who are not 被保険者. Printing the same `0` for
  both is the failure this repository names everywhere else — a check that
  could not run returning the value of a check that ran and found nothing."
  [{:keys [employer period runs]}]
  (let [included (filterv #(= :commit (get-in % [:meisai :meisai/disposition])) runs)
        excluded (vec (for [r runs
                            :when (not= :commit (get-in r [:meisai :meisai/disposition]))]
                        {:contract-id (:contract-id r)
                         :worker (:worker r)
                         :disposition (get-in r [:meisai :meisai/disposition])}))
        schemes
        (vec (for [k scheme-order
                   :let [figs (vec (for [r included
                                         :let [ded (get-in r [:meisai :meisai/deductions])]
                                         d ded
                                         :when (= k (:line/key d))]
                                     (:line/figure d)))
                         contributors (count (filterv prov/numeric? figs))]]
               {:scheme/key k
                :scheme/label (get labels k)
                :scheme/figure (prov/total-figure (get labels k) figs
                                                  (str "期間 " period " の全 run の合計"))
                :scheme/contributors contributors
                :scheme/runs (count figs)
                :scheme/remit-to (get-in remittance [k :remit/to])
                :scheme/provision (get-in remittance [k :remit/provision])}))]
    {:summary/employer-id (:client-id employer)
     :summary/employer-name (:name employer)
     :summary/period period
     :summary/run-count (count runs)
     :summary/included (count included)
     :summary/excluded excluded
     :summary/schemes schemes
     :summary/total (prov/total-figure "控除額合計"
                                       (mapv :scheme/figure schemes)
                                       "全制度の合計")}))

(def columns
  [{:column/key :scheme :column/header "控除"}
   {:column/key :amount :column/header "金額"}
   {:column/key :provenance :column/header "出所"}
   {:column/key :contributors :column/header "計上した run 数"}
   {:column/key :runs :column/header "対象 run 数"}
   {:column/key :remit-to :column/header "納付先"}
   {:column/key :provision :column/header "根拠条文"}])

(defn ->csv
  "The summary as CSV, one row per scheme plus a total row.

  The total row is a row and not a footer, because a CSV has no footer and a
  consumer that summed the rows would double-count it if it were unlabelled.
  It is labelled `控除額合計` in the same column as the scheme names, which is
  what a spreadsheet user sees and what a parser can filter on."
  [{:summary/keys [schemes total]}]
  (text/csv
   {:columns columns
    :rows (conj (vec (for [s schemes]
                       {:scheme (:scheme/label s)
                        :amount (:scheme/figure s)
                        :provenance (name (get-in s [:scheme/figure :figure/provenance]))
                        :contributors (:scheme/contributors s)
                        :runs (:scheme/runs s)
                        :remit-to (:scheme/remit-to s)
                        :provision (:scheme/provision s)}))
                {:scheme "控除額合計"
                 :amount total
                 :provenance (name (:figure/provenance total))
                 :contributors ""
                 :runs ""
                 :remit-to ""
                 :provision ""})}))

(defn ->json
  [{:summary/keys [employer-id employer-name period run-count included excluded
                   schemes total]}]
  (text/json-document
   [[:document_type "deduction_summary"]
    [:employer_id employer-id]
    [:employer_name employer-name]
    [:period period]
    [:runs_in_period run-count]
    [:runs_in_totals included]
    [:runs_excluded
     (vec (for [e excluded]
            (text/json-object-of [[:contract_id (:contract-id e)]
                                  [:worker (:worker e)]
                                  [:disposition (:disposition e)]])))]
    [:schemes
     (vec (for [s schemes]
            (text/json-object-of
             (concat [[:scheme (:scheme/key s)]
                      [:label (:scheme/label s)]
                      [:contributors (:scheme/contributors s)]
                      [:runs (:scheme/runs s)]
                      [:remit_to (:scheme/remit-to s)]
                      [:provision (:scheme/provision s)]]
                     (text/figure->json-pairs (:scheme/figure s))))))]
    [:total (text/json-object-of (text/figure->json-pairs total))]]))
