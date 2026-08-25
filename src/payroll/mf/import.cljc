(ns payroll.mf.import
  "Reading a MoneyForward クラウド給与 export, without pretending one has been
  read.

  Three properties, each of which is a way an importer quietly loses money:

  1. **An unknown column is REPORTED, never dropped.** A payroll export
     carrying a deduction this repository has no name for is a deduction that
     disappears between two systems. `parse` names every header it did not
     recognise, and `payroll.mf.reconcile` refuses to call a file reconciled
     while any of them carries a non-zero amount.
  2. **A malformed row is REJECTED with its raw text, never coerced.** There
     is no `(or … 0)` anywhere in this namespace. A cell reading `280,000円`
     rejects its row and quotes it back, which is how the next version of
     `payroll.mf.schema/parse-yen` gets written against evidence instead of
     against a guess about what MoneyForward emits.
  3. **Every imported figure carries `:imported` provenance.** Not
     `:declared`, and never `:derived`. A number that came out of another
     system's file is that system's claim, and this repository has verified
     neither the file's column names nor its arithmetic.

  ## What a successful import is NOT

  It is not a payroll run. `parse` produces a REPORT — rows, rejections,
  unknown columns, provenance — and writes nothing to any store. Turning an
  imported row into a run would be this actor accepting figures it did not
  compute for a period it did not govern, which is the one thing the whole
  actor exists to prevent. The rows are for RECONCILIATION: comparing what
  MoneyForward did against what this actor would do, for the two or three
  parallel cycles the cutover gate requires."
  (:require [clojure.string :as str]
            [payroll.mf.schema :as schema]
            [payroll.provenance :as prov]))

;; ---------------------------------------------------------------------------
;; CSV reading — RFC 4180
;; ---------------------------------------------------------------------------

(defn parse-csv
  "RFC 4180 CSV text → a vector of vectors of strings.

  Handles quoted fields containing commas, line breaks and doubled quotes.
  Accepts CRLF, LF and bare CR as row terminators, because a file that came
  out of a Windows tool and through a text editor may carry any of the three
  and rejecting one would reject a file for a reason that has nothing to do
  with payroll.

  A trailing terminator does NOT produce a final empty row. A file ending in
  a newline is a file whose last row ended, and an importer that saw a
  phantom row would report one more employee than the file contains.

  Written here rather than taken from a library because this repository has
  no CSV dependency and adding one to read a header row would be the larger
  change. It is small enough to read and is tested against the cases that
  break hand-rolled readers: a comma inside quotes, a newline inside quotes,
  a doubled quote, an empty trailing field, and CRLF."
  [text]
  (if (or (nil? text) (= "" text))
    []
    (loop [i 0
           field (transient [])
           row (transient [])
           rows (transient [])
           in-quotes? false]
      (if (>= i (count text))
        ;; End of input. The final row is emitted unless nothing at all was
        ;; accumulated after the last terminator.
        (let [f (apply str (persistent! field))
              r (persistent! (conj! row f))]
          (persistent! (if (and (= 1 (count r)) (= "" f))
                         rows
                         (conj! rows r))))
        (let [ch (nth text i)
              next-ch (when (< (inc i) (count text)) (nth text (inc i)))]
          (cond
            in-quotes?
            (cond
              (and (= \" ch) (= \" next-ch))
              (recur (+ i 2) (conj! field \") row rows true)

              (= \" ch)
              (recur (inc i) field row rows false)

              :else
              (recur (inc i) (conj! field ch) row rows true))

            (= \" ch)
            (recur (inc i) field row rows true)

            (= \, ch)
            (recur (inc i) (transient [])
                   (conj! row (apply str (persistent! field))) rows false)

            (or (= \newline ch) (= \return ch))
            (let [skip (if (and (= \return ch) (= \newline next-ch)) 2 1)
                  f (apply str (persistent! field))
                  r (persistent! (conj! row f))]
              (recur (+ i skip) (transient []) (transient [])
                     (if (and (= 1 (count r)) (= "" f)) rows (conj! rows r))
                     false))

            :else
            (recur (inc i) (conj! field ch) row rows false)))))))

;; ---------------------------------------------------------------------------
;; The import
;; ---------------------------------------------------------------------------

(def provenance-record
  "What is known about where these figures came from. Attached to every
  report, so a reconciliation that is read six months later still says the
  file was never verified."
  {:provenance/source :moneyforward-cloud-payroll-export
   :provenance/verified? false
   :provenance/why
   (str "実際の MoneyForward クラウド給与のエクスポートを"
        "この repository は一度も読んでいない。"
        "列名はすべて推測であり（payroll.mf.schema）、"
        "取り込まれた金額は当リポジトリが検算していない他システムの主張である")
   :provenance/columns-verified 0})

(defn- header-analysis
  "Which of the file's headers are known, which required ones are absent, and
  which are present and unrecognised."
  [headers]
  (let [known (filterv schema/by-column headers)
        unknown (filterv #(not (schema/by-column %)) headers)
        missing (filterv #(not (some #{%} headers)) schema/required-columns)]
    {:headers/present (vec headers)
     :headers/known known
     :headers/unknown unknown
     :headers/missing-required missing}))

(defn- read-cell [col raw]
  (case (:mf/kind col)
    :yen (schema/parse-yen raw)
    (let [t (some-> raw str str/trim)]
      (if (str/blank? (str t)) nil t))))

(defn- row-problems [values-by-column]
  (vec (for [[column v] values-by-column
             :when (= :mf/unparseable v)]
         {:problem/column column
          :problem/why (str column " の値が円単位の整数として読めない")})))

(defn parse
  "An export's text → a report.

    {:import/status      :ok | :rejected
     :import/provenance  `provenance-record`
     :import/headers     `header-analysis`
     :import/rows        [{:row/number :row/values :row/figures :row/mapped?
                           :row/contract-id …}]
     :import/rejected    [{:row/number :row/why :row/raw}]
     :import/unknown-columns
     :import/no-counterpart  columns MoneyForward carries that this actor has
                             no concept of, with the amounts seen

  `contracts` is this actor's REGISTERED contracts, used only to resolve
  `:mf/employee-number` → contract id. A row whose employee number is not
  registered is kept, marked `:row/mapped? false`, and excluded from
  reconciliation — dropping it would hide an employee MoneyForward is paying
  and this actor has never heard of, which is precisely the finding a cutover
  needs.

  `:rejected` at the FILE level happens only when a required column is
  absent: with no `支給年月` there is nothing to reconcile any row against,
  and reporting per-row failures for every row in the file would bury the one
  fact that explains all of them."
  [text contracts]
  (let [rows (parse-csv text)]
    (if (empty? rows)
      {:import/status :rejected
       :import/provenance provenance-record
       :import/why "ファイルに行が無い"
       :import/headers {:headers/present [] :headers/known []
                        :headers/unknown [] :headers/missing-required
                        schema/required-columns}
       :import/rows [] :import/rejected []
       :import/unknown-columns [] :import/no-counterpart []}
      (let [headers (mapv str/trim (first rows))
            analysis (header-analysis headers)
            body (rest rows)]
        (if (seq (:headers/missing-required analysis))
          {:import/status :rejected
           :import/provenance provenance-record
           :import/why (str "必須の列が無い: "
                            (str/join "、" (:headers/missing-required analysis))
                            "。列名はこの repository の推測なので、"
                            "実ファイルの見出しが違う可能性がある"
                            "（payroll.mf.schema を参照）")
           :import/headers analysis
           :import/rows [] :import/rejected []
           :import/unknown-columns (:headers/unknown analysis)
           :import/no-counterpart []}
          (let [parsed
                (for [[n raw-row] (map-indexed vector body)
                      :let [line-no (+ n 2)
                            padded (into raw-row
                                         (repeat (max 0 (- (count headers)
                                                           (count raw-row)))
                                                 ""))
                            by-col (into {} (map vector headers padded))
                            values (into {} (for [[column raw] by-col
                                                  :let [col (schema/by-column column)]
                                                  :when col]
                                              [column (read-cell col raw)]))
                            problems (row-problems values)]]
                  (if (seq problems)
                    {::rejected {:row/number line-no
                                 :row/why (str/join "、" (map :problem/why problems))
                                 :row/raw raw-row}}
                    (let [to (into {} (for [[column v] values
                                            :let [col (schema/by-column column)]
                                            :when (not= :mf/no-counterpart (:mf/to col))]
                                        [(:mf/to col) v]))
                          contract (schema/contract-for contracts
                                                        (:employee-number to))]
                      {::row
                       {:row/number line-no
                        :row/employee-number (:employee-number to)
                        :row/employee-name (:employee-name to)
                        :row/period (:period to)
                        :row/values to
                        :row/mapped? (some? contract)
                        :row/contract-id (:contract/id contract)
                        :row/figures
                        (into {}
                              (for [[k v] to
                                    :when (and (number? v)
                                               (not (contains? #{:employee-number
                                                                 :employee-name
                                                                 :period} k)))]
                                [k (prov/imported
                                    (name k) v
                                    (str "MoneyForward export 行 " line-no)
                                    (:provenance/why provenance-record))]))
                        :row/unmapped-why
                        (when-not contract
                          (str "従業員番号 " (pr-str (:employee-number to))
                               " に対応する契約が登録されていない。"
                               "契約側に " schema/employee-map-key
                               " を登録するまで、この行は突合の対象にならない"
                               "（氏名での推測照合はしない）"))}})))
                ;; the column's position, found by scanning rather than by
                ;; `.indexOf`, which is host interop and would make this
                ;; `.cljc` a `.clj` in disguise.
                index-of (fn [column]
                           (first (keep-indexed #(when (= column %2) %1) headers)))
                no-counterpart
                (vec (for [column schema/no-counterpart-columns
                           :let [idx (index-of column)]
                           :when idx
                           :let [seen (vec (for [r body
                                                 :let [v (get r idx)]
                                                 :when (and v (not (str/blank? v)))]
                                             v))]]
                       {:column column
                        :to :mf/no-counterpart
                        :why (:mf/no-counterpart-why (schema/by-column column))
                        :values-seen seen
                        ;; A 住民税 column reading `0` is the column being
                        ;; present and nothing having been withheld — nothing
                        ;; vanished between the two systems. A non-zero value,
                        ;; or one that cannot be read at all, IS something
                        ;; that vanished. `payroll.mf.reconcile` blocks on
                        ;; this rather than on `:values-seen`, so an employer
                        ;; whose export carries the column and never uses it
                        ;; is not permanently unreconcilable.
                        :carries-value?
                        (boolean (some #(not= 0 (schema/parse-yen %)) seen))}))]
            {:import/status :ok
             :import/provenance provenance-record
             :import/headers analysis
             :import/rows (vec (keep ::row parsed))
             :import/rejected (vec (keep ::rejected parsed))
             :import/unknown-columns (:headers/unknown analysis)
             :import/no-counterpart no-counterpart}))))))
