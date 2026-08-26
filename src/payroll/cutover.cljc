(ns payroll.cutover
  "並行運用の証拠 — the persisted evidence that MoneyForward may be switched
  off, and the gate that reads it.

  `docs/maturity.md`'s G1 asks for three consecutive reconciled pay cycles,
  at least one of which is not an ordinary month. Until now that was a
  sentence in a document and the reconciliation was rendered and discarded
  (`payroll.edge.console/reconcile!`: *nothing is written*). A gate whose
  evidence is not kept is a gate somebody satisfies from memory.

  ## A cycle is admitted from a reconciliation, never composed

  `admit-cycle` takes a `payroll.mf.reconcile/reconcile` report and an
  approval, and refuses if the report did not actually compare anything.
  There is no arity that takes a period and a boolean. The count of compared
  runs, every field difference, the source snapshot ids and the row count all
  come out of the report, so a cycle cannot record agreement the
  reconciliation did not find.

  **`:cycle/reconciled?` is copied from the report and is never an argument.**
  That is the one field somebody would otherwise be able to set.

  ## The month kind is registered with a reason, and is not derived

  G1 exists because `payroll.chingin` shows that a monthly contract's gross
  is the contracted rate and the timesheets are never read — right in an
  ordinary month, wrong in a month with a mid-month start, a leaver or unpaid
  leave. So two ordinary months agree and prove nothing about the third.

  Whether a month was ordinary is not derivable here: this actor holds no
  calendar and `wages-for` does not read the timesheets it would need. So
  `:cycle/month-kind` is REGISTERED as `:ordinary` or `:exceptional` **with a
  reason**, and an `:exceptional` with no reason is refused — otherwise the
  cheapest way to satisfy the gate is to type the word.

  ## Consecutive means consecutive, and the check is on the periods

  Three cycles that reconciled with a broken month between them are not three
  consecutive cycles. `consecutive?` compares the registered periods as
  `YYYY-MM` and requires each to follow the last; a gap, a repeat or a period
  this actor cannot parse breaks the run and the gate says which.

  ## Six conditions, and none of them is `somebody said so`

  1. three consecutive cycles, each `:cycle/reconciled? true`
  2. at least one `:cycle/month-kind :exceptional` with a reason
  3. zero `:mf/no-counterpart` and zero unknown columns carrying a value,
     across all three
  4. the evidence is in a store that can testify to its own durability —
     `payroll.store/Durable`, which only the kotobase backend implements
  5. the durable store read them back with no chain broken — the cycles were
     re-read from the store, not taken from the caller's hand
  6. the R2 projection read them back

  4, 5 and 6 are measurements and not booleans somebody passes in, for the
  reason `payroll.projection.catalog/verify-read-back` exists: a write that
  reported success against a store that kept nothing looks exactly like a
  write. 4 is separate from 5 because a `MemStore` passes 5 trivially — it
  reads back perfectly, right up until the process ends."
  (:require [clojure.string :as str]
            [payroll.store :as store]))

(def required-cycles 3)

(def month-kinds
  {:ordinary
   {:kind/label "通常の月"
    :kind/why (str "月給契約の gross は契約月額そのものであり、"
                   "通常の月ではそれが正しい")}
   :exceptional
   {:kind/label "通常でない月"
    :kind/needs-reason? true
    :kind/examples ["月の途中の入社" "月の途中の退職" "無給休職" "欠勤"
                    "割増賃金の発生" "住民税の変更通知"]
    :kind/why (str "月給契約の gross は勤怠を読まないので、"
                   "通常でない月でこそ二つの系が食い違う。"
                   "通常の月が二回一致しても三回目については何も証明しない")}})

(def cycle-fields
  "Everything a cycle must carry. Nothing is optional and nothing defaults."
  [{:field/key :cycle/employer :field/label "事業主"}
   {:field/key :cycle/period :field/label "対象期間（YYYY-MM）"}
   {:field/key :cycle/source-snapshots :field/label "突合元の識別子"
    :field/why (str "MoneyForward 側の export と当 actor 側の台帳を"
                    "後から同じもので引き直せること。"
                    "kotobase の chain node CID がここに入る")}
   {:field/key :cycle/mapped-rows :field/label "契約に紐づいた行数"}
   {:field/key :cycle/differences :field/label "全項目の差分"}
   {:field/key :cycle/approved-by :field/label "承認した actor"}
   {:field/key :cycle/approved-at :field/label "承認時刻"}
   {:field/key :cycle/month-kind :field/label "通常月かどうか"}
   {:field/key :cycle/month-reason :field/label "その分類の理由"}])

(defn- period? [x] (boolean (re-matches #"\d{4}-(0[1-9]|1[0-2])" (str x))))

(defn- next-period
  "The month after `YYYY-MM`, as a string. Arithmetic on two integers, which
  is the only date arithmetic in this repository and is confined to
  `consecutive?` — it needs no calendar, only that months run 1 to 12."
  [p]
  (let [[_ y m] (re-matches #"(\d{4})-(\d{2})" (str p))
        y* #?(:clj (parse-long y) :cljs (js/parseInt y 10))
        m* #?(:clj (parse-long m) :cljs (js/parseInt m 10))
        n (if (= 12 m*) 1 (inc m*))
        y2 (if (= 12 m*) (inc y*) y*)]
    (str y2 "-" (when (< n 10) "0") n)))

(defn consecutive?
  "Are these periods consecutive months, oldest first?"
  [periods]
  (and (seq periods)
       (every? period? periods)
       (= (vec (rest periods))
          (mapv next-period (butlast periods)))))

(defn admit-cycle
  "One reconciliation plus one approval → a cycle, or a refusal.

    {:employer :period :report :approved-by :approved-at
     :month-kind :month-reason :source-snapshots}

  `report` is `payroll.mf.reconcile/reconcile`'s output. Nothing about
  agreement is taken from the caller."
  [{:keys [employer period report approved-by approved-at
           month-kind month-reason source-snapshots]}]
  (let [kind (get month-kinds month-kind)
        problems
        (cond-> []
          (str/blank? (str employer)) (conj "事業主が無い")
          (not (period? period)) (conj "対象期間が YYYY-MM ではない")
          (nil? report) (conj "突合レポートが無い")
          (and report (zero? (or (:reconcile/compared report) 0)))
          (conj (str "突合できた run が 0 件のレポートからサイクルは作れない。"
                     "差分が無いことと、比較していないことは違う"))
          (str/blank? (str approved-by)) (conj "承認した actor が記録されていない")
          (str/blank? (str approved-at)) (conj "承認時刻が記録されていない")
          (nil? kind) (conj (str "月の分類が "
                                 (pr-str (vec (sort (keys month-kinds))))
                                 " のいずれでもない"))
          (and (:kind/needs-reason? kind) (str/blank? (str month-reason)))
          (conj (str "「通常でない月」には理由が要る。"
                     "理由の無い分類は、gate を満たす最も安い方法が"
                     "語を打ち込むことになる"))
          (empty? source-snapshots)
          (conj (str "突合元の識別子が無い。"
                     "後から同じ二つを引き直せないサイクルは証拠ではない"))) ]
    (if (seq problems)
      {:cycle/status :refused :cycle/why (str/join "。" problems)}
      {:cycle/status :ok
       :cycle/record
       {:cycle/id (str employer "/" period)
        :cycle/employer employer
        :cycle/period period
        :cycle/source-snapshots (vec source-snapshots)
        :cycle/mapped-rows (count (:reconcile/runs report))
        :cycle/rows-in-file (:reconcile/rows report)
        :cycle/compared (:reconcile/compared report)
        ;; copied from the report, never an argument.
        :cycle/reconciled? (boolean (:reconcile/reconciled? report))
        :cycle/why (:reconcile/why report)
        :cycle/blockers (vec (:reconcile/blockers report))
        :cycle/unknown-columns (vec (:reconcile/unknown-columns report))
        :cycle/no-counterpart
        (vec (for [c (:reconcile/no-counterpart report)
                   :when (:carries-value? c)]
               {:column (:column c) :why (:why c)
                :values-seen (vec (:values-seen c))}))
        ;; EVERY field, agreeing ones included — a difference report that
        ;; showed only the differences cannot distinguish `eleven fields
        ;; compared, one differs` from `one field compared, and it differs`.
        :cycle/runs
        (vec (for [r (:reconcile/runs report)]
               {:run/contract-id (:run/contract-id r)
                :run/period (:run/period r)
                :run/agrees? (boolean (:run/agrees? r))
                :run/fields
                (vec (for [f (:run/fields r)]
                       {:field/key (:field/key f)
                        :field/label (:field/label f)
                        :field/verdict (:field/verdict f)
                        :field/delta (:field/delta f)
                        :field/ours-amount (get-in f [:field/ours :figure/amount])
                        :field/ours-provenance (get-in f [:field/ours :figure/provenance])
                        :field/theirs-amount (get-in f [:field/theirs :figure/amount])
                        :field/why (:field/why f)}))}))
        :cycle/differences
        (vec (for [r (:reconcile/runs report)
                   f (:run/fields r)
                   :when (not= :agree (:field/verdict f))]
               {:field/contract-id (:run/contract-id r)
                :field/key (:field/key f)
                :field/verdict (:field/verdict f)
                :field/delta (:field/delta f)
                :field/why (:field/why f)}))
        :cycle/month-kind month-kind
        :cycle/month-reason month-reason
        :cycle/approved-by approved-by
        :cycle/approved-at approved-at}})))

(defn record-cycle!
  "Admit and persist. Returns the refusal unchanged when there is one —
  nothing reaches the store on a refusal, which is `payroll.touroku`'s rule
  at the other write boundary."
  [store* args]
  (let [r (admit-cycle args)]
    (when (= :ok (:cycle/status r))
      (store/commit-cutover-cycle! store* (:cycle/record r)))
    r))

;; ---------------------------------------------------------------------------
;; The gate
;; ---------------------------------------------------------------------------

(def conditions
  "The conditions, in the order an operator satisfies them. Named so the UI, the
  API and the gate all read one list — a screen with its own copy of these is
  the copy that drifts."
  [{:gate/key :three-consecutive
    :gate/label "連続する3サイクルが突合済み"}
   {:gate/key :one-exceptional
    :gate/label "うち少なくとも1つは通常でない月"}
   {:gate/key :no-unknown-values
    :gate/label "未知の列・対応概念の無い控除に値が入っていない"}
   {:gate/key :durable-store
    :gate/label "証拠が durable store（kotobase）に在る"}
   {:gate/key :durable-read-back
    :gate/label "durable store から欠落なく読み戻せた"}
   {:gate/key :projection-read-back
    :gate/label "R2 投影から読み戻せた"}])

(defn- window
  "The trailing run of consecutive reconciled cycles, oldest first, capped at
  `required-cycles`.

  Built from the newest backwards and stopped at the first cycle that is not
  reconciled or not the previous month — so a broken month RESETS the count
  rather than being skipped over, and the progress an operator reads is
  `how far along am I now` rather than `how many good months have I ever
  had`."
  [cycles]
  (let [sorted (vec (sort-by :cycle/period cycles))]
    (loop [i (dec (count sorted)) run ()]
      (cond
        (neg? i) (vec (take-last required-cycles run))
        (not (:cycle/reconciled? (nth sorted i))) (vec (take-last required-cycles run))
        (and (seq run)
             (not= (:cycle/period (first run))
                   (next-period (:cycle/period (nth sorted i)))))
        (vec (take-last required-cycles run))
        :else (recur (dec i) (conj run (nth sorted i)))))))

(defn evaluate
  "Read the evidence and answer whether the cutover gate is met.

    {:store          the durable store — cycles are RE-READ from it
     :employer
     :projection-verification  `payroll.projection.catalog/verify-read-back`'s
                               result for this employer's cycles, or nil}

  `:cutover/progress` is `n/3` where n counts only cycles inside the current
  consecutive reconciled window — so a fourth reconciled month after a broken
  one shows 1/3 and not 4/3, which is the number that matters.

  Returns `:cutover/passed? false` and a reason for every condition that is
  not met. There is no argument that makes it true.

  ## The durability evidence is MEASURED here, never passed in

  `payroll.store/Durable` is asked of the store itself. A `MemStore` holding
  three consecutive reconciled cycles — one of them exceptional, no unknown
  columns, a verified projection — satisfies every other condition on this
  list, and that was previously enough to make this function answer
  `passed`. It must not be: the entire evidence for switching off the
  incumbent payroll system would be inside a process, and the first restart
  is the moment the employer discovers there is no record that the parallel
  run ever happened.

  So there are two durability conditions and not one:

  - `:durable-store` — the store implements `Durable` at all (only the
    kotobase backend does) and its transport CLAIMS to outlive the process.
    A store that cannot supply the evidence is not thereby durable.
  - `:durable-read-back` — every chain reconstructs to its end, and the
    cycles actually come back from a fresh read. `payroll.store.kotobase`
    fails closed on an incomplete chain, so a read that would otherwise
    silently return a short list raises instead; that throw is CAUGHT here
    and reported as an unmet condition, because a gate that propagated it
    would take the console down rather than telling the operator which of
    the conditions is failing.

  Accepting a health map as an argument was the alternative and was
  rejected: the caller assembling that map is the caller who wants the gate
  to pass."
  [{:keys [store employer projection-verification]}]
  (let [evidence (when (satisfies? store/Durable store)
                   (try (store/durability-evidence store)
                        (catch #?(:clj Exception :cljs :default) e
                          {:evidence/mode :unreadable
                           :evidence/readable? false
                           :evidence/why
                           (str "store が自身の健全性を答えられない: "
                                #?(:clj (.getMessage ^Exception e)
                                   :cljs (.-message e)))})))
        read* (try (let [cs (store/cutover-cycles store employer)]
                     {:read/ok? true
                      :read/cycles (vec (or cs []))
                      :read/answered? (some? cs)})
                   (catch #?(:clj Exception :cljs :default) e
                     {:read/ok? false
                      :read/cycles []
                      :read/answered? false
                      :read/why #?(:clj (.getMessage ^Exception e)
                                   :cljs (.-message e))}))
        cycles (:read/cycles read*)
        read-back? (and (:read/ok? read*) (:read/answered? read*))
        win (window cycles)
        exceptional (filterv #(= :exceptional (:cycle/month-kind %)) win)
        with-values (vec (for [c win
                               :when (or (seq (:cycle/no-counterpart c))
                                         (seq (:cycle/unknown-columns c)))]
                           {:cycle/period (:cycle/period c)
                            :cycle/no-counterpart (:cycle/no-counterpart c)
                            :cycle/unknown-columns (:cycle/unknown-columns c)}))
        proj-ok? (= :ok (:verify/status projection-verification))
        durable? (and (= :kotobase (:evidence/mode evidence))
                      (true? (:evidence/survives-process-restart? evidence)))
        readable? (true? (:evidence/readable? evidence))
        results
        [{:gate/key :three-consecutive
          :gate/met? (= required-cycles (count win))
          :gate/why (if (= required-cycles (count win))
                      (str "連続する " required-cycles " サイクル: "
                           (str/join "、" (map :cycle/period win)))
                      (str "連続して突合できているのは " (count win) " サイクル"
                           "（記録は全部で " (count cycles) " 件）。"
                           "間に突合できなかった月があると連続は途切れる"))}
         {:gate/key :one-exceptional
          :gate/met? (boolean (seq exceptional))
          :gate/why (if (seq exceptional)
                      (str "通常でない月: "
                           (str/join "、" (for [c exceptional]
                                            (str (:cycle/period c) "（"
                                                 (:cycle/month-reason c) "）"))))
                      (str "3サイクルとも通常の月である。"
                           "通常の月が二回一致しても三回目については"
                           "何も証明しない —— "
                           "通常でない月が出るまで並行運用を続ける"))}
         {:gate/key :no-unknown-values
          :gate/met? (empty? with-values)
          :gate/why (if (empty? with-values)
                      "対応する概念の無い控除にも未知の列にも値が入っていない"
                      (str/join "、"
                                (for [c with-values]
                                  (str (:cycle/period c) ": "
                                       (str/join "・"
                                                 (concat
                                                  (map :column (:cycle/no-counterpart c))
                                                  (:cycle/unknown-columns c)))))))}
         {:gate/key :durable-store
          :gate/met? durable?
          :gate/why
          (cond
            durable?
            (str "証拠は kotobase の chain に在り、"
                 "transport は自身が耐久であると宣言している"
                 (when (= :unknown (:evidence/key-separation evidence))
                   "（封筒と blind index の鍵が別かどうかは未確認）"))

            (nil? evidence)
            (str "この store は自身の耐久性について何も答えられない"
                 "（payroll.store/Durable を実装していない）。"
                 "プロセス内の store に三サイクル分の証拠が載っていても、"
                 "それは最初の再起動で消える証拠である。"
                 "答えられないことは「耐久である」ではない")

            (= :unreadable (:evidence/mode evidence))
            (:evidence/why evidence)

            :else
            (str "store の transport が :transport/durable? true を"
                 "宣言していない。"
                 "並行運用の証拠が明日も在ることを、誰も述べていない"))}
         {:gate/key :durable-read-back
          :gate/met? (and durable? readable? read-back?
                          (= required-cycles (count win)))
          :gate/why
          (cond
            (not (:read/ok? read*))
            (str "store がサイクルの読み戻しを拒否した: " (:read/why read*)
                 "。読めない履歴は空の履歴ではない")

            (not read-back?)
            (str "store からサイクルを読み戻せない。"
                 "書けたことと在ることは別である")

            (not durable?)
            (str "耐久でない store からの読み戻しは、"
                 "この gate の証拠にならない")

            (not readable?)
            (str "七つの chain のうち辿れないものがある: "
                 (:evidence/why evidence)
                 "。ここに出ている件数は読めた分の下限であって、"
                 "この事業主が届け出た件数ではない")

            (not= required-cycles (count win))
            (str "読み戻せたのは " (count cycles) " 件だが、"
                 "連続して突合できているのは " (count win) " サイクルである")

            :else (str "store から " (count cycles) " 件を欠落なく読み戻し、"
                       "うち " (count win) " サイクルが連続して突合済みである"))}
         {:gate/key :projection-read-back
          :gate/met? proj-ok?
          :gate/why (or (:verify/why projection-verification)
                        (str "R2 投影の読み戻しが行われていない。"
                             "未実施は合格ではない"))}]
        unmet (filterv #(not (:gate/met? %)) results)]
    {:cutover/employer employer
     :cutover/passed? (empty? unmet)
     :cutover/progress {:progress/reconciled (count win)
                        :progress/required required-cycles
                        :progress/text (str (count win) "/" required-cycles)}
     :cutover/cycles cycles
     :cutover/window win
     :cutover/durability evidence
     :cutover/conditions (mapv (fn [c]
                                 (merge c (first (filter #(= (:gate/key c)
                                                             (:gate/key %))
                                                         results))))
                               conditions)
     :cutover/held-by (mapv :gate/key unmet)
     :cutover/why
     (if (empty? unmet)
       (str (count conditions) "条件すべてを満たしている。"
            "これは並行運用の証拠が揃ったという意味であって、"
            "切り替えてよいという判断そのものではない —— "
            "その判断は事業主のものである")
       (str/join "。" (map :gate/why unmet)))}))
