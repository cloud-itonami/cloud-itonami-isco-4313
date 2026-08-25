(ns payroll.mf.reconcile
  "Comparing what MoneyForward did against what this actor would do.

  This is the instrument the cutover gate runs on. Leaving MoneyForward is
  not a migration of data; it is a claim that a second system computes the
  same payroll, and the only evidence for that claim is running both for
  several cycles and comparing every figure.

  ## `:reconciled` is a verdict this report is very reluctant to reach

  A field comparison is FIVE-valued, not two:

  | verdict | means |
  |---|---|
  | `:agree` | both sides have a number and the numbers are equal |
  | `:differ` | both sides have a number and they are not equal |
  | `:only-in-mf` | MoneyForward has a figure; this actor has none |
  | `:only-here` | this actor has a figure; MoneyForward's file has no column for it |
  | `:not-comparable` | this actor HELD the figure, so there is nothing to compare |

  Only the first is a pass, and `:not-comparable` is deliberately not one.
  It is the state of every run this actor holds for an unobserved 標準報酬月額
  — and a reconciliation that scored those as agreeing would report a clean
  parallel run for a month in which this actor computed nothing at all. That
  is this repository's recurring defect, at the exact point it would be most
  expensive: the report that says it is safe to switch.

  ## A file-level pass has an evidence floor

  `reconciled?` is false when the number of compared rows is ZERO. A report
  over an empty import, or over a file every row of which was unmapped, has
  no differences to show and would otherwise print exactly like a perfect
  month. The count travels with the verdict for the same reason
  `payroll.artifact.text/coverage` does.

  ## Columns this actor has no concept of are a failure, not a footnote

  住民税 is the live case: MoneyForward withholds it and this actor has no
  rule, no line and no account for it. A row where MoneyForward deducted
  住民税 and this actor did not is not a discrepancy in a figure — it is a
  deduction one system makes and the other cannot. `reconciled?` is false
  while any such column carries a value, and the report names it."
  (:require [clojure.string :as str]
            [payroll.artifact.text :as text]
            [payroll.meisai :as meisai]
            [payroll.provenance :as prov]))

(def compared-fields
  "What is compared, and what it is called.

  Taken from `payroll.meisai/deduction-lines` plus gross and net, so a
  deduction added there is compared here without a second edit — the drift
  `payroll.governor/hoken-refusal-rules` avoids by being driven off the
  refusals rather than off a `cond`."
  (into [{:field/key :gross :field/label "総支給額"}]
        (concat (for [l meisai/deduction-lines]
                  {:field/key (:line/key l) :field/label (:line/label l)})
                [{:field/key :net :field/label "差引支給額"}])))

(defn- ours
  "This actor's figure for one field of one run."
  [m k]
  (case k
    :gross (:meisai/gross m)
    :net (:meisai/net m)
    (some #(when (= k (:line/key %)) (:line/figure %)) (:meisai/deductions m))))

(defn compare-field
  "One field, five-valued. `their-figure` is an `:imported` figure or nil."
  [{:field/keys [key label]} our-figure their-figure]
  (let [ours-n (prov/amount our-figure)
        theirs-n (prov/amount their-figure)
        base {:field/key key :field/label label
              :field/ours our-figure :field/theirs their-figure}]
    (cond
      (and (some? ours-n) (some? theirs-n))
      (assoc base
             :field/verdict (if (= ours-n theirs-n) :agree :differ)
             :field/delta (- theirs-n ours-n)
             :field/why (when (not= ours-n theirs-n)
                          (str label "が一致しない: 当 actor " ours-n
                               " / MoneyForward " theirs-n
                               "（差 " (- theirs-n ours-n) "）")))

      ;; This actor held or could not answer. Never scored as agreement.
      (and (nil? ours-n) (some? theirs-n)
           (contains? #{:held :unknown} (:figure/provenance our-figure)))
      (assoc base :field/verdict :not-comparable
             :field/why (str label "について当 actor は数字を出していない: "
                             (:figure/why our-figure)
                             "。比較できないことは一致ではない"))

      (some? theirs-n)
      (assoc base :field/verdict :only-in-mf
             :field/why (str label "は MoneyForward にのみ存在する"
                             (when (= :not-applicable
                                      (:figure/provenance our-figure))
                               (str "。当 actor では「該当なし」: "
                                    (:figure/why our-figure)))))

      (some? ours-n)
      (assoc base :field/verdict :only-here
             :field/why (str label "は当 actor にのみ存在する"
                             "（この export に対応する列が無いか、空である）"))

      :else
      (assoc base :field/verdict :not-comparable
             :field/why (str label "はどちらにも数字が無い")))))

(def passing-verdicts
  "The field verdicts that do not block a reconciliation.

  `:agree` and nothing else. `:only-here` is not here even though it can be
  benign — a 介護保険料 this actor computes for a worker MoneyForward does not
  treat as a 第二号被保険者 is a real disagreement about the law, not a
  formatting difference — and calling it a pass would decide that question by
  omission."
  #{:agree})

(defn reconcile-run
  "One imported row against one of this actor's runs.

    {:row     a `payroll.mf.import` row
     :meisai  `payroll.meisai/lines` for the same contract and period}"
  [{:keys [row meisai]}]
  (let [fields (vec (for [f compared-fields]
                      (compare-field f
                                     (ours meisai (:field/key f))
                                     (get-in row [:row/figures (:field/key f)]))))
        by (frequencies (map :field/verdict fields))]
    {:run/contract-id (:row/contract-id row)
     :run/period (:row/period row)
     :run/employee-number (:row/employee-number row)
     :run/row-number (:row/number row)
     :run/fields fields
     :run/by-verdict by
     :run/agrees? (every? #(contains? passing-verdicts (:field/verdict %)) fields)
     :run/blocking (vec (for [f fields
                              :when (not (contains? passing-verdicts (:field/verdict f)))]
                          (select-keys f [:field/key :field/label :field/verdict
                                          :field/delta :field/why])))}))

(defn reconcile
  "A whole import against this actor's runs.

    {:import   `payroll.mf.import/parse`'s report
     :ours     {[contract-id period] meisai}
     :period}

  Returns a report whose top-level verdict is `:reconciled?`, which is true
  only when ALL of:

    - the import itself was not rejected
    - at least one row was compared            ← the evidence floor
    - every compared row agrees on every field
    - no row was unmapped
    - no unknown column carried a value
    - no `:mf/no-counterpart` column carried a value

  Five of those six are things a naive implementation would not check, and
  each of them is a way a switch-over goes wrong while the report is green."
  [{:keys [import ours period]}]
  (if (= :rejected (:import/status import))
    {:reconcile/reconciled? false
     :reconcile/period period
     :reconcile/why (str "取り込みが拒否されている: " (:import/why import))
     :reconcile/provenance (:import/provenance import)
     :reconcile/compared 0
     :reconcile/runs [] :reconcile/unmapped []
     :reconcile/unknown-columns (vec (:import/unknown-columns import))
     :reconcile/no-counterpart (vec (:import/no-counterpart import))
     :reconcile/rejected-rows (vec (:import/rejected import))}
    (let [rows (:import/rows import)
          mapped (filterv :row/mapped? rows)
          unmapped (vec (for [r rows :when (not (:row/mapped? r))]
                          {:row/number (:row/number r)
                           :row/employee-number (:row/employee-number r)
                           :row/employee-name (:row/employee-name r)
                           :row/why (:row/unmapped-why r)}))
          runs (vec (for [r mapped
                          :let [m (get ours [(:row/contract-id r) (:row/period r)])]]
                      (if m
                        (reconcile-run {:row r :meisai m})
                        {:run/contract-id (:row/contract-id r)
                         :run/period (:row/period r)
                         :run/row-number (:row/number r)
                         :run/fields []
                         :run/agrees? false
                         :run/blocking
                         [{:field/verdict :only-in-mf
                           :field/why (str "契約 " (:row/contract-id r)
                                           " の " (:row/period r)
                                           " について、この actor には run が"
                                           "一件も無い。MoneyForward は支給しているが"
                                           "当 actor はその期間を処理していない")}]})))
          ;; `:carries-value?` and NOT `(seq :values-seen)`. A 住民税 column
          ;; reading 0 on every row is the column being present and nothing
          ;; having been withheld; blocking on it would make an employer whose
          ;; export carries the column and never uses it permanently
          ;; unreconcilable, and a blocker that can never be cleared is one
          ;; operators learn to read past.
          counterpart-with-values
          (vec (filter :carries-value? (:import/no-counterpart import)))
          compared (count (filter #(seq (:run/fields %)) runs))
          blockers
          (cond-> []
            (zero? compared)
            (conj (str "突合できた run が 0 件である。"
                       "差分が無いことと、比較していないことは違う"))

            (some #(not (:run/agrees? %)) runs)
            (conj (str (count (filterv #(not (:run/agrees? %)) runs))
                       " 件の run が一致していない"))

            (seq unmapped)
            (conj (str (count unmapped)
                       " 件の行が契約に紐づいていない"
                       "（MoneyForward が支給していて当 actor が知らない従業員）"))

            (seq (:import/unknown-columns import))
            (conj (str "この repository が知らない列がある: "
                       (str/join "、" (:import/unknown-columns import))))

            (seq counterpart-with-values)
            (conj (str "当 actor に対応する概念が無い控除に値が入っている: "
                       (str/join "、" (map :column counterpart-with-values))))

            (seq (:import/rejected import))
            (conj (str (count (:import/rejected import))
                       " 行が読み取れずに拒否されている")))]
      {:reconcile/reconciled? (empty? blockers)
       :reconcile/period period
       :reconcile/provenance (:import/provenance import)
       :reconcile/compared compared
       :reconcile/rows (count rows)
       :reconcile/runs runs
       :reconcile/unmapped unmapped
       :reconcile/unknown-columns (vec (:import/unknown-columns import))
       :reconcile/no-counterpart (vec (:import/no-counterpart import))
       :reconcile/rejected-rows (vec (:import/rejected import))
       :reconcile/blockers blockers
       :reconcile/why (if (empty? blockers)
                        (str compared " 件の run が全項目で一致した。"
                             "これは 1 サイクル分の証拠であって、"
                             "移行の可否ではない（cutover gate は複数サイクルを要求する）")
                        (str/join "。" blockers))})))

;; ---------------------------------------------------------------------------
;; The report as a file
;; ---------------------------------------------------------------------------

(def columns
  [{:column/key :period :column/header "対象期間"}
   {:column/key :contract-id :column/header "雇用契約"}
   {:column/key :field :column/header "項目"}
   {:column/key :ours :column/header "当 actor"}
   {:column/key :theirs :column/header "MoneyForward"}
   {:column/key :verdict :column/header "判定"}
   {:column/key :delta :column/header "差"}
   {:column/key :why :column/header "理由"}])

(def verdict-labels
  {:agree "一致" :differ "不一致" :only-in-mf "MF のみ"
   :only-here "当 actor のみ" :not-comparable "比較不能"})

(defn ->csv
  "Every compared field as one row. Deterministic; `compared-fields` fixes the
  within-run order and the runs keep the import's row order.

  Rows are emitted for AGREEING fields too. A difference report that showed
  only the differences could not distinguish `we compared eleven fields and
  one differs` from `we compared one field and it differs`."
  [{:reconcile/keys [runs]}]
  (text/csv
   {:columns columns
    :rows (vec (for [r runs
                     f (:run/fields r)]
                 {:period (:run/period r)
                  :contract-id (:run/contract-id r)
                  :field (:field/label f)
                  :ours (text/cell (:field/ours f))
                  :theirs (if (:field/theirs f)
                            (text/cell (:field/theirs f))
                            text/unknown-cell)
                  :verdict (get verdict-labels (:field/verdict f))
                  :delta (:field/delta f)
                  :why (:field/why f)}))}))

(defn ->json
  [{:reconcile/keys [reconciled? period compared rows runs unmapped
                     unknown-columns no-counterpart rejected-rows blockers why
                     provenance]}]
  (text/json-document
   [[:document_type "mf_reconciliation"]
    [:reconciled (boolean reconciled?)]
    [:why why]
    [:period period]
    [:source_verified (boolean (:provenance/verified? provenance))]
    [:source_why (:provenance/why provenance)]
    [:rows_in_file rows]
    [:runs_compared compared]
    [:blockers (vec blockers)]
    [:unknown_columns (vec unknown-columns)]
    [:no_counterpart
     (vec (for [c no-counterpart]
            (text/json-object-of [[:column (:column c)]
                                  [:why (:why c)]
                                  [:values_seen (vec (:values-seen c))]])))]
    [:unmapped_rows
     (vec (for [u unmapped]
            (text/json-object-of [[:row (:row/number u)]
                                  [:employee_number (:row/employee-number u)]
                                  [:why (:row/why u)]])))]
    [:rejected_rows
     (vec (for [r rejected-rows]
            (text/json-object-of [[:row (:row/number r)]
                                  [:why (:row/why r)]])))]
    [:runs
     (vec (for [r runs]
            (text/json-object-of
             [[:contract_id (:run/contract-id r)]
              [:period (:run/period r)]
              [:agrees (boolean (:run/agrees? r))]
              [:fields
               (vec (for [f (:run/fields r)]
                      (text/json-object-of
                       [[:field (:field/key f)]
                        [:label (:field/label f)]
                        [:verdict (:field/verdict f)]
                        [:ours (prov/amount (:field/ours f))]
                        [:ours_provenance (get-in f [:field/ours :figure/provenance])]
                        [:theirs (prov/amount (:field/theirs f))]
                        [:delta (:field/delta f)]
                        [:why (:field/why f)]])))]])))]]))
