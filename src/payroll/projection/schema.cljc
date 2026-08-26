(ns payroll.projection.schema
  "What may leave the operational store for the analytical one, and in what
  shape.

  ## The direction is one-way and the store is not this one

  Kotobase is the transactional source of truth (`payroll.store.kotobase`).
  R2 Data Catalog is a PROJECTION: an Iceberg table an accountant or an
  auditor can query without touching the ledger, and one that could be
  deleted and rebuilt from the ledger without losing anything. CLAUDE.md's
  test for the difference is exactly that — *delete it: is data lost, or is
  it only slower* — and every table here answers `only slower`.

  Nothing reads back from a projection into a payroll decision. There is no
  function here that returns a figure a run could be built from.

  ## De-identification is a refusal, not a filter

  `project-run` REFUSES a row carrying a forbidden key rather than dropping
  the key. A silent drop produces a row that looks de-identified and a caller
  who believes the column was never there — and the next person to add a
  column gets no signal at all. The vocabulary is `payroll.sensitive`, which
  the health surfaces and the statutory artifacts share, so `what counts as
  identifying` is decided once.

  The employee appears as `:contract_id`, which is an identifier the
  EMPLOYER chose and can re-key. It is a pseudonym and not anonymity, and
  `:privacy` on every row says so — a projection that claimed anonymity while
  carrying a stable per-person key would be making a claim about
  re-identification that nothing here can support.

  ## Three tables, because they answer three questions

  `payroll_run_projection` is one row per committed or held run: what it
  paid, what it withheld, and what the governor said. It is the table a
  reconciliation against the accounting system runs on.

  `parallel_reconciliation_projection` is one row per compared FIELD of a
  parallel cycle — not per run — because the cutover gate turns on whether
  every field agreed, and a table with one row per run cannot show which
  field did not.

  `resident_tax_notice_projection` is one row per REGISTRATION EVENT on the
  住民税 notice stream — corrections and re-issues included, because nothing
  on that stream is ever overwritten and the correction history is the thing
  worth querying. It answers 「訂正がいつ何度あったか、いま何年度が有効か」
  and, by construction, nothing about 「いくらか」: see the absences written
  beside its columns."
  (:require [clojure.string :as str]
            [payroll.digest :as digest]
            [payroll.juminzei :as juminzei]
            [payroll.sensitive :as sensitive]))

(def namespace-name
  "The Iceberg namespace. One level, and named for the actor rather than for
  the employer: an employer-per-namespace layout would put the tenant
  boundary in a catalog whose access control this repository does not own."
  ["payroll"])

(def run-table
  {:table/name "payroll_run_projection"
   :table/namespace namespace-name
   :table/what "1 行 = 1 給与 run（承認・保留の別を含む）"
   :table/partition-by ["employer_id" "period"]
   :table/columns
   [{:col/name "snapshot_id" :col/type :string :col/why "この投影の識別子"}
    {:col/name "run_id" :col/type :string :col/why "冪等性の鍵。同じ run は一度だけ"}
    {:col/name "employer_id" :col/type :string}
    {:col/name "contract_id" :col/type :string
     :col/privacy :pseudonymous
     :col/why "事業主が付けた識別子。氏名ではないが、同一人を通して指す"}
    {:col/name "period" :col/type :string}
    {:col/name "disposition" :col/type :string :col/why ":commit/:hold/:request-approval"}
    {:col/name "gross" :col/type :long}
    {:col/name "net" :col/type :long}
    {:col/name "income_tax_withheld" :col/type :long}
    {:col/name "health_insurance_withheld" :col/type :long}
    {:col/name "care_insurance_withheld" :col/type :long}
    {:col/name "employees_pension_withheld" :col/type :long}
    {:col/name "employment_insurance_withheld" :col/type :long}
    {:col/name "resident_tax_withheld" :col/type :long}
    {:col/name "gross_provenance" :col/type :string
     :col/why "figure の出所。金額だけを読んだ集計が確定値に見えないようにする"}
    {:col/name "net_provenance" :col/type :string}
    {:col/name "unverified_figures" :col/type :int
     :col/why "この run のうち当 repository が検算していない項目の数"}
    {:col/name "violation_rules" :col/type :string
     :col/why "HARD 違反の rule 名を「;」で連結したもの。detail は載せない"}
    {:col/name "ledger_cid" :col/type :string
     :col/why "kotobase の chain node CID。投影から原本へ戻る唯一の経路"}]})

(def reconciliation-table
  {:table/name "parallel_reconciliation_projection"
   :table/namespace namespace-name
   :table/what "1 行 = 並行運用で突合した 1 項目"
   :table/partition-by ["employer_id" "period"]
   :table/columns
   [{:col/name "snapshot_id" :col/type :string}
    {:col/name "cycle_id" :col/type :string :col/why "冪等性の鍵"}
    {:col/name "employer_id" :col/type :string}
    {:col/name "period" :col/type :string}
    {:col/name "contract_id" :col/type :string :col/privacy :pseudonymous}
    {:col/name "field" :col/type :string}
    {:col/name "ours" :col/type :long}
    {:col/name "theirs" :col/type :long}
    {:col/name "verdict" :col/type :string
     :col/why ":agree/:differ/:only-in-mf/:only-here/:not-comparable"}
    {:col/name "delta" :col/type :long}
    {:col/name "month_kind" :col/type :string :col/why ":ordinary か :exceptional か"}
    {:col/name "reconciled" :col/type :boolean :col/why "そのサイクル全体の判定"}
    {:col/name "approved_by" :col/type :string :col/why "承認した actor の識別子"}
    {:col/name "approved_at" :col/type :string}]})

(def notice-table
  {:table/name "resident_tax_notice_projection"
   :table/namespace namespace-name
   :table/what "1 行 = 住民税 特別徴収 通知の登録イベント 1 件（訂正・再交付を含む履歴）"
   :table/partition-by ["employer_id" "tax_year"]
   :table/columns
   [{:col/name "snapshot_id" :col/type :string :col/why "この投影の識別子"}
    {:col/name "notice_event_id" :col/type :string
     :col/why (str "冪等性の鍵。"
                   "employer_id/tax_year/kind/r<revision>/#<seq> であり、"
                   "seq はこの事業主の登録履歴における位置。"
                   "行だけから導出でき、区市町村も通知書番号も運ばない")}
    {:col/name "employer_id" :col/type :string}
    {:col/name "tax_year" :col/type :string :col/why "徴収が6月に始まる年度"}
    {:col/name "notice_kind" :col/type :string :col/why "decision か revision か"}
    {:col/name "revision" :col/type :int :col/why "0 が初回。訂正のたびに 1 つ上がる"}
    {:col/name "status" :col/type :string
     :col/why (str "effective か superseded か。"
                   "差し替えられた通知も行として残る —— "
                   "原本で上書きされないものを、投影で消さない")}
    {:col/name "effective_from" :col/type :string
     :col/why (str "変更通知の適用開始月の KEY（\"m10\"）。決定通知は nil。"
                   "月の名前は金額ではない")}
    {:col/name "months_registered" :col/type :int
     :col/why (str "その通知が運んでいる月の数。COUNT であって合計ではない —— "
                   "12 か月のうち何か月ぶんの紙かは訂正の履歴の一部だが、"
                   "その額は投影の外にある")}
    {:col/name "registered_at" :col/type :string
     :col/why (str "操作者が登録した受領・転記日。"
                   "この actor は暦を持たないので、ここで導出されるものではない")}
    {:col/name "notice_id_digest" :col/type :string
     :col/privacy :pseudonymous
     :col/why (str "payroll.juminzei/notice-id の SHA-256（hex）。"
                   "その id は区市町村と通知書番号を含むので、"
                   "この digest は同じ紙について安定しており、"
                   "どちらも平文では運ばない。"
                   "ただし擬名であって匿名ではない —— "
                   "しかも操作用ストアの冪等性 tag（payroll.kotobase.blind-index）"
                   "とは違い鍵付きではないので、"
                   "事業主・区市町村・年度・通知書番号を既に知っている者は、"
                   "自分でハッシュを取ればその推測を確認できる。"
                   "金額に対する digest ではなく、そこが線である —— "
                   "レコード全体の digest なら、"
                   "同じ手口で給与の額を確認できてしまう")}
    {:col/name "replaces_digest" :col/type :string
     :col/privacy :pseudonymous
     :col/why (str "差し替えられた通知の id の SHA-256（hex）、無ければ nil。"
                   "訂正の連鎖を投影の中で辿れるようにするためだけにあり、"
                   "identity そのものは運ばない")}]
   ;; ------------------------------------------------------------------------
   ;; What is ABSENT, and why the trade was made this way
   ;;
   ;; 区市町村・通知書番号・指定番号・年税額・十二の月割額 は投影しない。
   ;;
   ;; 区市町村 is close to an address for an employer with one employee —
   ;; 「区市町村ごとにとりまとめ」る税なので、一人しかいない事業主の行に
   ;; 区市町村が載れば、それはその一人の居住地である。通知書番号と指定番号は
   ;; その紙と事業主を役所の台帳に対して名指しする識別子で、分析には要らない。
   ;; 年税額と月割額はその人の税そのもの —— 住民税は前年の所得から役所が
   ;; 計算したものなので、月割額は所得の代理変数でもある。
   ;;
   ;; 失われる分析はある。区市町村ごとの納入額の推移も、年税額の分布も、
   ;; 「訂正で税額がいくら動いたか」も、この表からは出せない。それらは
   ;; 操作用ストアの原本を読む者の仕事であって、監査人や会計士が権限なしに
   ;; 引ける表の仕事ではない。この表が答えるのは
   ;; 「訂正がいつ何度あったか、いま何年度が有効か」であって「いくらか」ではない。
   ;;
   ;; 列を宣言しないことが de-identification そのものである点に注意する ——
   ;; `check-row` は表が宣言していない列を黙って落とさず拒否するので、
   ;; ここに 月割額 の列が無いことは、月割額を運ぶ行が拒否されることを意味する。
   ;; ------------------------------------------------------------------------
   })

(def tables [run-table reconciliation-table notice-table])

(def privacy
  "What every projected row does and does not claim, carried into the
  catalog's own table properties so it cannot be separated from the data."
  {:privacy/de-identified? true
   :privacy/anonymous? false
   :privacy/why (str "氏名・住所・個人番号・口座番号・支店コードは投影しない。"
                     "contract_id は事業主が付けた擬名であり、"
                     "同一人を通して指すので匿名ではない")
   :privacy/never-projected (mapv :sensitive/label sensitive/forbidden-outside)
   ;; `payroll.sensitive` is the vocabulary of what makes a row a PERSON, and
   ;; it is the right vocabulary for the two payroll tables. The notice table
   ;; withholds five more things that are not on that list and never will be:
   ;; a municipality is not an identifier in general, and a 年税額 is an
   ;; amount like any other. They are absent HERE for reasons particular to
   ;; this stream — see the block beside `notice-table`'s columns — so they
   ;; are named here rather than being read out of a set that does not
   ;; contain them. A key that a denylist does not carry is not thereby safe.
   :privacy/never-projected-additionally
   ["区市町村" "通知書番号" "指定番号" "年税額" "十二の月割額"]
   :privacy/never-projected-additionally-why
   (str "住民税の通知の投影は「訂正がいつ何度あったか、"
        "いま何年度が有効か」に答えるものであって、"
        "「いくらか」には答えない。"
        "従業員が一人の事業主にとって区市町村は住所に近く、"
        "月割額は前年の所得から役所が計算した税そのものである")})

;; ---------------------------------------------------------------------------
;; Building a row
;; ---------------------------------------------------------------------------

(defn- yen [f] (when (map? f) (:figure/amount f)))

(defn- column-names [table] (into #{} (map :col/name) (:table/columns table)))

(defn check-row
  "Every reason this row may not leave, or `nil`.

  Two checks and both are refusals: a key `payroll.sensitive` forbids, and a
  column the table does not declare. The second matters as much as the first
  — an undeclared column is a field somebody added to a row and not to a
  schema, and an Iceberg append with an unknown column either fails at the
  catalog or silently widens the table."
  [table row]
  (let [forbidden (sensitive/violations row)
        undeclared (vec (remove (column-names table) (map name (keys row))))]
    (when (or (seq forbidden) (seq undeclared))
      {:row/status :refused
       :row/forbidden forbidden
       :row/undeclared undeclared
       :row/why (str/join
                 "。"
                 (cond-> []
                   (seq forbidden)
                   (conj (str "投影してはならない項目がある: "
                              (str/join "、" (map #(or (:sensitive/label %)
                                                       (str (:sensitive/key %)))
                                                  forbidden))
                              "。落とすのではなく拒否する —— "
                              "黙って落とした行は、"
                              "その列が最初から無かったように見える"))
                   (seq undeclared)
                   (conj (str "表が宣言していない列がある: "
                              (str/join "、" undeclared)))))})))

(defn project-run
  "One ledger entry as a `payroll_run_projection` row, or a refusal.

    {:snapshot-id  this projection run's id
     :entry        a ledger entry
     :meisai       `payroll.meisai/lines` for it
     :ledger-cid   the chain node CID, when the store has one}

  `run_id` is `employer/contract/period/disposition` — stable, derivable from
  the entry alone, and the idempotency key an append uses. Two projections of
  the same ledger produce the same `run_id`s and the second appends nothing."
  [{:keys [snapshot-id entry meisai ledger-cid]}]
  (let [ded (into {} (for [d (:meisai/deductions meisai)]
                       [(:line/key d) (yen (:line/figure d))]))
        row {:snapshot_id snapshot-id
             :run_id (str/join "/" [(:client-id entry) (:contract-id entry)
                                    (:period entry)
                                    (name (or (:disposition entry) :unknown))])
             :employer_id (:client-id entry)
             :contract_id (:contract-id entry)
             :period (:period entry)
             :disposition (some-> (:disposition entry) name)
             :gross (yen (:meisai/gross meisai))
             :net (yen (:meisai/net meisai))
             :income_tax_withheld (:income-tax-withheld ded)
             :health_insurance_withheld (:health-insurance-withheld ded)
             :care_insurance_withheld (:care-insurance-withheld ded)
             :employees_pension_withheld (:employees-pension-withheld ded)
             :employment_insurance_withheld (:employment-insurance-withheld ded)
             :resident_tax_withheld (:resident-tax-withheld ded)
             :gross_provenance (some-> (get-in meisai [:meisai/gross :figure/provenance]) name)
             :net_provenance (some-> (get-in meisai [:meisai/net :figure/provenance]) name)
             :unverified_figures (:meisai/unverified meisai)
             ;; rule names only. `:detail` carries amounts and, for the
             ;; wage-basis rules, a registered value — neither of which
             ;; belongs in a table somebody queries from a spreadsheet.
             :violation_rules (str/join ";" (map #(str (:rule %))
                                                 (get-in entry [:verdict :violations])))
             :ledger_cid ledger-cid}]
    (or (check-row run-table row)
        {:row/status :ok :row/table (:table/name run-table) :row/value row})))

(defn project-notice
  "One registration event as a `resident_tax_notice_projection` row, or a
  refusal.

    {:snapshot-id  this projection run's id
     :notice       one registered notice record, as the store holds it
     :seq          its index in this employer's registration history
     :status       :effective | :superseded, from `effective-notices`
     :replaces-id  the `notice-id` this one replaces, or nil}

  `notice_event_id` is `employer/tax-year/kind/r<revision>/#<seq>` — stable,
  derivable from the row alone, and carrying neither the 区市町村 nor the
  通知書番号 that `payroll.juminzei/notice-id` needs to be unique in the
  operational store. `seq` is what keeps it unique here: two papers from two
  municipalities in the same year with the same 改訂番号 differ in nothing
  else this table is allowed to see.

  `:status` and `:replaces-id` are ARGUMENTS and not derived here, because
  both are properties of the whole history — `payroll.juminzei/effective-notices`
  is the one place that decides what is current, and a second copy of that
  rule living in a projection is exactly the drift this repository keeps
  refusing.

  The digests are `payroll.juminzei/notice-id` run through SHA-256, computed
  rather than read off the record: a projection that trusted a stored
  `:notice/id` would be carrying whatever the store happened to hold, and the
  id is the one thing here that has to be the same function everywhere."
  [{:keys [snapshot-id notice seq status replaces-id]}]
  (let [id (juminzei/notice-id notice)
        row {:snapshot_id snapshot-id
             :notice_event_id (str/join "/" [(:notice/employer notice)
                                             (:notice/tax-year notice)
                                             (some-> (:notice/kind notice) name)
                                             (str "r" (:notice/revision notice))
                                             (str "#" seq)])
             :employer_id (:notice/employer notice)
             :tax_year (:notice/tax-year notice)
             :notice_kind (some-> (:notice/kind notice) name)
             :revision (:notice/revision notice)
             :status (some-> status name)
             ;; the month KEY and not the month's figure: `:juminzei/m10`
             ;; names October, and a month name is not an amount.
             :effective_from (some-> (:notice/effective-from notice) name)
             ;; a COUNT. How many of the twelve this paper carries is part of
             ;; the correction history; what they say is not.
             :months_registered (count (:notice/months notice))
             :registered_at (:notice/registered-at notice)
             :notice_id_digest (digest/sha256-hex id)
             :replaces_digest (some-> replaces-id digest/sha256-hex)}]
    (or (check-row notice-table row)
        {:row/status :ok :row/table (:table/name notice-table) :row/value row})))

(defn project-reconciliation
  "One cycle's compared fields as `parallel_reconciliation_projection` rows.

  One row per FIELD. A refused row refuses the whole cycle's projection: a
  reconciliation table missing the field that disagreed is a reconciliation
  table that says the cycle agreed."
  [{:keys [snapshot-id cycle]}]
  (let [rows (vec (for [r (:cycle/runs cycle)
                        f (:run/fields r)]
                    {:snapshot_id snapshot-id
                     :cycle_id (:cycle/id cycle)
                     :employer_id (:cycle/employer cycle)
                     :period (:cycle/period cycle)
                     :contract_id (:run/contract-id r)
                     :field (name (:field/key f))
                     :ours (:field/ours-amount f)
                     :theirs (:field/theirs-amount f)
                     :verdict (name (:field/verdict f))
                     :delta (:field/delta f)
                     :month_kind (some-> (:cycle/month-kind cycle) name)
                     :reconciled (boolean (:cycle/reconciled? cycle))
                     :approved_by (:cycle/approved-by cycle)
                     :approved_at (:cycle/approved-at cycle)}))
        refusals (vec (keep #(check-row reconciliation-table %) rows))]
    (if (seq refusals)
      {:row/status :refused
       :row/why (str/join "。" (map :row/why refusals))
       :row/refusals refusals}
      {:row/status :ok :row/table (:table/name reconciliation-table)
       :row/values rows})))
