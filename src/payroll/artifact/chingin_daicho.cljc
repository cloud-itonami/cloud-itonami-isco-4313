(ns payroll.artifact.chingin-daicho
  "賃金台帳 — every run this actor recorded for one employer, as a wage ledger.

  ## It is not certified as the statutory 賃金台帳, and says so

  労働基準法 第百八条 obliges an employer to keep a 賃金台帳, and
  労働基準法施行規則 第五十四条 prescribes what it must contain. **This
  repository has read neither.** It therefore cannot enumerate the required
  items, cannot check this file against them, and does not claim this file
  satisfies the obligation.

  Note what is NOT done here: no list of `required items` appears anywhere in
  this namespace. Writing one from memory would be worse than the absence,
  because a list that looks read invites an operator to tick it off. What is
  emitted instead is the list of columns this ledger DOES carry, and a
  refusal naming the rule that would have to be read to say anything about
  sufficiency.

  ## Held runs are IN the ledger, and are the reason it is a ledger

  A wage ledger that contained only the runs that committed would answer
  `what was paid` and could not answer `why was this month not paid`, which
  is the question anybody actually opens it for. Every disposition is a row —
  commit, escalation and hold alike — with the amounts of a held run printed
  as `未確定` and not as blanks.

  This is `payroll.store/run-history`'s discipline (`the whole life of one
  contract's runs, not just the latest state`) carried into a file somebody
  keeps."
  (:require [payroll.artifact.text :as text]
            [payroll.meisai :as meisai]))

(def sufficiency
  "What this repository can and cannot say about this file being the
  statutory ledger. Emitted into the artifact, not left here."
  {:sufficiency/claimed? false
   :sufficiency/why
   (str "労働基準法 第百八条（賃金台帳の調製義務）も、"
        "労働基準法施行規則 第五十四条（記載事項）も、"
        "この repository は読んでいない。"
        "したがってこの台帳が法定の記載事項を満たすかどうかは未検査であり、"
        "満たしているとも満たしていないとも主張しない")
   :sufficiency/statutes-named-as-unread
   ["労働基準法 第百八条（賃金台帳）"
    "労働基準法施行規則 第五十四条（賃金台帳の記載事項）"]})

(def columns
  "The ledger's columns, in order. The vector IS the file's shape: `csv`
  iterates it and never a map, so the bytes do not move when a row map gains
  a key.

  `provenance_*` columns are not decoration. A row where 総支給額 is a number
  and a row where it is `未確定` are different rows, and a reader who sorted
  by amount would put every unknown at one end and read the block as zeroes."
  [{:column/key :period :column/header "対象期間"}
   {:column/key :contract-id :column/header "雇用契約"}
   {:column/key :worker :column/header "従業員"}
   {:column/key :disposition :column/header "処理"}
   {:column/key :wage-type :column/header "賃金形態"}
   {:column/key :gross :column/header "総支給額"}
   {:column/key :gross-provenance :column/header "総支給額の出所"}
   {:column/key :income-tax :column/header "所得税"}
   {:column/key :health-insurance :column/header "健康保険料"}
   {:column/key :care-insurance :column/header "介護保険料"}
   {:column/key :employees-pension :column/header "厚生年金保険料"}
   {:column/key :employment-insurance :column/header "雇用保険料"}
   {:column/key :deduction-total :column/header "控除合計"}
   {:column/key :net :column/header "差引支給額"}
   {:column/key :net-provenance :column/header "差引支給額の出所"}
   {:column/key :payable :column/header "支払可否"}
   {:column/key :timesheet-count :column/header "登録勤怠件数"}
   {:column/key :timesheets-read :column/header "勤怠を金額計算に使ったか"}])

(defn- line-figure [m k]
  (some #(when (= k (:line/key %)) (:line/figure %)) (:meisai/deductions m)))

(defn row
  "One run as a ledger row.

    {:period :contract-id :worker :meisai}

  Every amount cell is the FIGURE, not the number: `payroll.artifact.text/csv`
  routes a figure through `cell`, which is what puts `未確定` where a blank
  would otherwise be."
  [{:keys [period contract-id worker meisai]}]
  {:period period
   :contract-id contract-id
   :worker worker
   :disposition (case (:meisai/disposition meisai)
                  :commit "承認"
                  :request-approval "署名待ち"
                  :hold "保留"
                  "未処理")
   :wage-type (some-> (get-in meisai [:meisai/basis :chingin/wage-type]) name)
   :gross (:meisai/gross meisai)
   :gross-provenance (name (get-in meisai [:meisai/gross :figure/provenance]))
   :income-tax (line-figure meisai :income-tax-withheld)
   :health-insurance (line-figure meisai :health-insurance-withheld)
   :care-insurance (line-figure meisai :care-insurance-withheld)
   :employees-pension (line-figure meisai :employees-pension-withheld)
   :employment-insurance (line-figure meisai :employment-insurance-withheld)
   :deduction-total (:meisai/deduction-total meisai)
   :net (:meisai/net meisai)
   :net-provenance (name (get-in meisai [:meisai/net :figure/provenance]))
   :payable (if (meisai/payable? meisai) "可" "不可")
   :timesheet-count (get-in meisai [:meisai/basis :chingin/timesheet-count])
   :timesheets-read (if (get-in meisai [:meisai/basis :chingin/reads-timesheets?])
                      "使った" "使っていない")})

(defn ->csv
  "Rows → CSV. Deterministic: `columns` fixes the order and
  `payroll.artifact.text/csv` never iterates a map."
  [rows]
  (text/csv {:columns columns :rows rows}))

(defn ->json
  "The ledger as deterministic JSON, with the sufficiency refusal and the
  coverage counts attached to the file rather than to a covering note.

  `:rows_by_disposition` is emitted because a consumer that counted rows and
  found a number would have no way to tell a ledger of twelve paid months
  from a ledger of twelve held ones."
  [rows]
  (text/json-document
   [[:document_type "wage_ledger"]
    [:statutory_form false]
    [:statutory_form_why (:sufficiency/why sufficiency)]
    [:statutes_named_as_unread (vec (:sufficiency/statutes-named-as-unread sufficiency))]
    [:columns (mapv :column/header columns)]
    [:row_count (count rows)]
    [:rows_by_disposition (frequencies (map :disposition rows))]
    [:rows (vec (for [r rows]
                  (text/json-object-of
                   (for [c columns
                         :let [v (get r (:column/key c))]]
                     [(:column/key c)
                      (if (and (map? v) (contains? v :figure/provenance))
                        (text/json-object-of (text/figure->json-pairs v))
                        v)]))))]]))
