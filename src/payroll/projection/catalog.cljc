(ns payroll.projection.catalog
  "The Iceberg catalog boundary: create a namespace, create a table, append
  rows idempotently, read back, and refuse.

  ## A protocol, and no HTTP client — for `payroll.kotobase.transport`'s reason

  A deployment injects a driver. This repository ships none that reaches a
  network, so the tests have no network and the properties below are
  measured rather than asserted. `payroll.projection.r2` is the ADAPTER
  CONTRACT for Cloudflare's R2 Data Catalog: the configuration it needs, the
  requests it would make, and the permission that is currently missing.

  ## Idempotency is the append's problem and it is solved with an id

  An Iceberg append is not naturally idempotent — a retried commit adds the
  rows again, and a projection with a duplicated payroll run is a
  reconciliation that reports twice the wage bill. So every append carries a
  `snapshot-id` (this projection run) and every ROW carries its own stable
  key (`run_id` / `cycle_id`), and `append!` asks the driver which keys it
  already has before writing.

  A driver that cannot answer `existing-keys` is REFUSED rather than written
  to blindly. `payroll.projection.catalog-test` builds one that cannot and
  asserts the refusal, because the failure it prevents is silent and
  cumulative.

  ## Retry and conflict are different and are not merged

    :conflict   another writer committed against the snapshot this append
                was built on. RETRIED, up to `max-attempts`, because Iceberg
                commits are optimistic and a conflict means `try again on the
                new snapshot` rather than `something is wrong`.
    :transient  the driver could not reach the catalog. RETRIED.
    :refused    the catalog said no — a permission, a schema mismatch, a
                malformed row. **NOT retried.** Retrying a 401 produces four
                more 401s and an operator reading a log of four failures
                instead of one reason.

  That last distinction is the live one: the current blocker on this
  deployment is a 401 on `create_table`, and a driver that retried it would
  have turned one legible failure into a rate-limit.

  ## Health is a read-back, not a ping

  `health` creates nothing and writes nothing. It asks the catalog for the
  namespace and each table, and reports what it got — including `:missing`,
  which is a fact and not an error. A projection whose table does not exist
  is not unhealthy; it is unbuilt, and those need different actions."
  (:require [clojure.string :as str]
            [payroll.projection.schema :as schema]))

(defprotocol Catalog
  (create-namespace! [c ns*]
    "`{:catalog/status :ok|:exists|:refused|:transient …}`")
  (create-table! [c ns* table]
    "`table` is a `payroll.projection.schema` table map.
    `{:catalog/status :ok|:exists|:refused|:transient :catalog/why …}`")
  (existing-keys [c ns* table-name key-column keys*]
    "Which of `keys*` the table already carries.
    `{:catalog/status :ok :catalog/keys #{…}}` or a refusal. A driver that
    cannot answer this must say so; `append!` will not write without it.")
  (append! [c ns* table-name rows snapshot-id]
    "`{:catalog/status :ok :catalog/appended n :catalog/snapshot …}`,
    `{:catalog/status :conflict …}`, `:transient`, or `:refused`.")
  (read-rows [c ns* table-name {:keys [limit]}]
    "For read-back verification. `{:catalog/status :ok :catalog/rows […]}`.")
  (describe [c] "What this catalog is, with no credential in it."))

(def max-attempts
  "How many times a conflict or a transient failure is retried.

  Three, and bounded for `payroll.kotobase.transport/max-cas-attempts`'
  reason: an unbounded retry against a contended table is a process that
  never returns and never says why."
  3)

(def retryable #{:conflict :transient})

(defn ensure-tables!
  "Create the namespace and both tables, idempotently.

  `:exists` is a SUCCESS and is reported separately from `:ok`, because
  `we created it` and `it was already there` are different facts about a
  deployment and an operator running this twice should be able to tell."
  [catalog]
  (let [ns* schema/namespace-name
        n (create-namespace! catalog ns*)
        ts (for [t schema/tables]
             (assoc (create-table! catalog ns* t) :catalog/table (:table/name t)))]
    {:ensure/namespace n
     :ensure/tables (vec ts)
     :ensure/ok? (and (contains? #{:ok :exists} (:catalog/status n))
                      (every? #(contains? #{:ok :exists} (:catalog/status %)) ts))
     :ensure/why
     (if (and (contains? #{:ok :exists} (:catalog/status n))
              (every? #(contains? #{:ok :exists} (:catalog/status %)) ts))
       (str "namespace " (str/join "." ns*) " と表 "
            (str/join "、" (map :table/name schema/tables)) " が在る")
       (str/join "。"
                 (cond-> []
                   (not (contains? #{:ok :exists} (:catalog/status n)))
                   (conj (str "namespace: " (:catalog/why n)))
                   true
                   (into (for [t ts
                               :when (not (contains? #{:ok :exists} (:catalog/status t)))]
                           (str (:catalog/table t) ": " (:catalog/why t)))))))}))

(defn- attempt-append
  [catalog ns* table-name rows snapshot-id]
  (loop [attempt 1]
    (let [r (append! catalog ns* table-name rows snapshot-id)]
      (if (and (contains? retryable (:catalog/status r))
               (< attempt max-attempts))
        (recur (inc attempt))
        (assoc r :catalog/attempts attempt)))))

(defn project!
  "Append `rows` to `table-name`, skipping the keys already there.

    {:catalog :namespace :table :key-column :rows :snapshot-id}

  Returns

    {:project/status :ok | :refused | :conflict | :nothing-to-do
     :project/appended n
     :project/skipped  [key …]     already present — idempotency working
     :project/why}

  **`:nothing-to-do` is not `:ok`.** A projection that appended zero rows
  because every row was already there is a healthy repeat; one that appended
  zero rows because it was handed no rows is a projection over nothing, and
  reporting them identically is the evidence-floor failure this repository
  keeps finding. The two are separate statuses and the count travels."
  [{:keys [catalog namespace table key-column rows snapshot-id]}]
  (let [ks (mapv #(get % key-column) rows)
        existing (existing-keys catalog namespace table key-column ks)]
    (cond
      (empty? rows)
      {:project/status :nothing-to-do :project/appended 0 :project/skipped []
       :project/why (str "投影する行が 0 件である。"
                         "これは「重複が無かった」ではなく「対象が無かった」")}

      (not= :ok (:catalog/status existing))
      {:project/status :refused :project/appended 0
       :project/why (str "既存の鍵を問い合わせられない: "
                         (or (:catalog/why existing) "driver が答えられない")
                         "。冪等性を確かめずに append すると、"
                         "再実行のたびに給与 run が二重に載る")}

      :else
      (let [have (:catalog/keys existing)
            fresh (vec (remove #(contains? have (get % key-column)) rows))
            skipped (vec (filter #(contains? have %) ks))]
        (if (empty? fresh)
          {:project/status :ok :project/appended 0 :project/skipped skipped
           :project/why (str (count skipped) " 件はすでに投影済み。"
                             "冪等な再実行であって、"
                             "投影する対象が無かったのではない")}
          (let [r (attempt-append catalog namespace table fresh snapshot-id)]
            (case (:catalog/status r)
              :ok {:project/status :ok
                   :project/appended (count fresh)
                   :project/skipped skipped
                   :project/snapshot (:catalog/snapshot r)
                   :project/attempts (:catalog/attempts r)
                   :project/why (str (count fresh) " 件を追記し、"
                                     (count skipped) " 件は既存だった")}
              :conflict {:project/status :conflict
                         :project/appended 0 :project/skipped skipped
                         :project/attempts (:catalog/attempts r)
                         :project/why (str "他の書き手と " (:catalog/attempts r)
                                           " 回続けて競合した。追記していない")}
              {:project/status :refused :project/appended 0
               :project/skipped skipped
               :project/attempts (:catalog/attempts r)
               :project/why (or (:catalog/why r) "catalog が追記を拒否した")})))))))

(defn verify-read-back
  "Did the rows just written come back?

  Read-back rather than trusting the append's own answer, because the
  failure this is for is a driver that reports success against a catalog
  that accepted the commit and stored nothing — and a `200 OK` is what that
  looks like from the writing side.

  Returns `{:verify/status :ok|:missing|:refused :verify/found n
  :verify/expected n :verify/missing [key …]}`."
  [{:keys [catalog namespace table key-column expected-keys]}]
  (let [r (read-rows catalog namespace table {:limit nil})]
    (if (not= :ok (:catalog/status r))
      {:verify/status :refused :verify/why (:catalog/why r)}
      (let [have (into #{} (map #(get % key-column)) (:catalog/rows r))
            missing (vec (remove have expected-keys))]
        {:verify/status (if (empty? missing) :ok :missing)
         :verify/found (count (filter have expected-keys))
         :verify/expected (count expected-keys)
         :verify/missing missing
         :verify/why (if (empty? missing)
                       (str (count expected-keys)
                            " 件すべてを読み戻せた。"
                            "これは投影が在ることの証拠であって、"
                            "投影が正しいことの証拠ではない")
                       (str (count missing)
                            " 件が読み戻せない。"
                            "append は成功と答えたが、"
                            "catalog にはその行が無い"))}))))

(defn health
  "What the catalog can say about itself. Creates nothing.

  `:missing` for a table that is not there is a FACT and not an error: a
  projection that has never been built is a different state from one that is
  broken, and the operator's next action differs."
  [catalog]
  (let [ns* schema/namespace-name
        ts (for [t schema/tables]
             (let [r (read-rows catalog ns* (:table/name t) {:limit 1})]
               {:table (:table/name t)
                :status (:catalog/status r)
                :why (:catalog/why r)}))]
    {:projection/catalog (describe catalog)
     :projection/namespace (str/join "." ns*)
     :projection/tables (vec ts)
     :projection/reachable? (every? #(contains? #{:ok :missing} (:status %)) ts)
     :projection/built? (every? #(= :ok (:status %)) ts)
     :projection/privacy schema/privacy
     :projection/why
     (cond
       (every? #(= :ok (:status %)) ts)
       ;; counted rather than named `両方`, which was true of two tables and
       ;; became false the day a third landed. A sentence that has to be
       ;; edited whenever `schema/tables` grows is a sentence that will one
       ;; day disagree with the list it is describing.
       (str (count ts) " つの表がすべて在り、読める")
       (every? #(contains? #{:ok :missing} (:status %)) ts)
       (str "catalog には届くが、まだ作られていない表がある: "
            (str/join "、" (map :table (filter #(= :missing (:status %)) ts)))
            "。これは故障ではなく未構築である")
       :else
       (str/join "。" (for [t ts :when (not (contains? #{:ok :missing} (:status t)))]
                        (str (:table t) ": " (:why t)))))}))
