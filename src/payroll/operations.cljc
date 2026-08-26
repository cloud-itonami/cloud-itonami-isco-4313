(ns payroll.operations
  "運用の現況 — one report, read by the console and by the API.

  Everything a non-technical operator has to be able to see without reading
  code, assembled once and rendered twice. One namespace and not two
  because the failure this prevents is the one the console's artifact table
  and the export dispatcher would otherwise have: a screen with its own copy
  of a list is the copy that drifts.

  ## Every section answers `what has been read` and not `is it correct`

  A rate has a source with a URL and a retrieval date. A 住民税 figure comes
  from a registered 決定通知書 or does not exist. A reconciliation is a count
  of runs COMPARED, never a count of runs that agreed. The cutover gate lists
  which conditions hold it. The store and the projection say whether they
  could be read, separately from whether they are empty.

  ## Nothing here may carry payroll data

  `payroll.sensitive/log-violations` is run over the whole report by
  `payroll.operations-test`, and `redact` is applied on the way out. This is
  the surface most likely to be screenshotted into a ticket, and a screen
  that showed a worker's name next to an amount would be the one place this
  actor leaks by design rather than by accident.

  Amounts are excluded and not merely names: `never-logged` covers `:gross`
  and every withheld figure, because a payroll amount in a ticket is a
  payroll amount in whatever indexes tickets, and nobody chose that."
  (:require [clojure.string :as str]
            [payroll.artifact.gensen :as gensen]
            [payroll.cutover :as cutover]
            [payroll.juminzei :as juminzei]
            [payroll.mf.schema :as mf-schema]
            [payroll.rates :as rates]
            [payroll.sensitive :as sensitive]
            [payroll.store :as store]
            [payroll.ui.views :as views]
            [payroll.warimashi :as warimashi]))

(defn redact
  "Drop every key whose value must not be logged, recursively, and say how
  many were dropped.

  Dropped and COUNTED rather than dropped silently: a report that quietly
  removed a field is a report a reader believes was complete. The count is
  attached at the top level as `:report/redacted-keys`."
  [m]
  (let [n (atom 0)
        walk (fn walk [x]
               (cond
                 (map? x) (into {} (for [[k v] x
                                         :when (or (sensitive/loggable-name? k)
                                                   (do (swap! n inc) false))]
                                     [k (walk v)]))
                 (sequential? x) (mapv walk x)
                 :else x))
        cleaned (walk m)]
    (assoc cleaned :report/redacted-keys @n)))

(def resident-tax-registration
  "Where an operator registers a 決定通知書, which IS this screen.

  ## Both halves exist

  `payroll.juminzei/admit-notice` reads a notice off the paper and refuses a
  partial one, `payroll.juminzei/admit-registration` decides whether what
  arrived is a first registration, a retried transcription or a correction of
  a notice already held, and `payroll.juminzei/register-notice!` persists the
  admitted record on `payroll.store/Store`'s notice stream — a SEVENTH
  CAS-guarded kotobase chain, employer-scoped and append-only.

  What used to be missing was the other half: anything an operator could
  reach from a browser. `POST /console/juminzei-notice` is that half. It sits
  behind the console's three gates and its `Origin` check like every other
  write, the employer comes from the verified caller rather than from the
  body, and a refusal re-renders the form with the transcription still in it.

  It is REPORTED rather than assumed. An operator looking for the form has to
  be told where it is; and a report that stopped mentioning registration the
  moment it became possible would leave the one screen that says what this
  deployment can do silent about what it just gained."
  {:registration/available? true
   :registration/path (str "POST " views/notice-path)
   :registration/admits-through 'payroll.juminzei/admit-registration
   :registration/persists-through 'payroll.juminzei/register-notice!
   ;; What `:registration/missing-seam` used to name. The seam is not missing,
   ;; so the key that named it is GONE rather than left pointing at a
   ;; namespace that now has the route: a field nobody updated is how a report
   ;; starts describing the past while reading as a description of the present.
   :registration/writes-to 'payroll.store/Store
   :registration/why
   (str "決定通知書・変更通知書は、この画面のフォームから登録する"
        "（POST " views/notice-path "）。"
        "保存されるのは payroll.juminzei が受け付けたものだけで、"
        "既定値の補完はしない —— "
        "12か月のうち転記されていない月は「零円」ではなく、"
        "未登録のまま登録そのものが拒否される。"
        "額は区市町村が決定したものであり、この actor は計算しない")
   :registration/action
   (str "手元の通知書を、紙のとおりに転記して登録する。"
        "訂正・再交付を受け取ったときは、改訂番号を1つ上げ、"
        "差し替える通知を ID で名指しする —— "
        "上書きはされず、元の通知も残るので、"
        "区市町村が何を訂正したのかを後から示せる。"
        "未登録の年度がある間、その月の run は住民税の行が"
        "「未確定」のままになり、"
        "payroll.meisai/payable? が支払可と答えないので、"
        "振込ファイルはその run については作られない —— "
        "これは欠落ではなく拒否である")
   :registration/fields
   ;; `:field/id` and not `:field/key`: `payroll.sensitive` blocks a key whose
   ;; tail is `key` (it is matching `:envelope/key`), and a redactor that
   ;; dropped this one would leave the panel rendering blank rows. The same
   ;; rename `artifacts` makes below, for the same reason.
   ;;
   ;; `:field/admits` is a PREDICATE and is deliberately not carried: a
   ;; function in the report would print as an object identity, and this
   ;; report is asserted to be byte-identical across calls.
   (vec (for [f juminzei/notice-fields]
          {:field/id (:field/key f)
           :field/label (:field/label f)
           :field/why (:field/why f)}))})

(defn resident-tax
  "住民税 特別徴収 — what is registered, what of it is still in force, and
  what follows from a year not being covered.

  A month with no notice is not a month with zero 住民税. This actor computes
  no 住民税 at all (地方税法 第三百二十一条の四 makes the municipality decide
  it and send a 決定通知書), so every figure here came off a piece of paper or
  does not exist.

  ## Three answers and not two

    `:registered`     this employer has notices, and they are readable
    `:not-registered` this employer has none
    `:unreadable`     the STORE could not be read to the end of the chain

  The third is the one this whole repository is organised around: **an
  unreadable history is not an empty one.** They are the same shape — no
  notices came back either way — and they are opposite operator actions. One
  says `transcribe the paper on your desk`; the other says `do not transcribe
  anything, because this deployment cannot tell you what it already holds`.
  A section that answered `:not-registered` to both would send an operator to
  register a notice that is already there, and `payroll.juminzei/notice-id`
  would then be the only thing standing between them and a forked correction
  history.

  On `:unreadable` the three counts are **nil and not zero**, for the same
  reason. A count is the result of counting; a read that failed counted
  nothing, and `0 件登録されている` is a measurement nobody made.

  ## No amount appears in this section, and that is a constraint

  Not a 月割額, not the 年税額, not a total over either. This is the surface
  most likely to be screenshotted into a ticket — `payroll.sensitive` is run
  over the whole report and `payroll.juminzei/coverage` refuses to answer in
  yen for exactly this reason — and the exact figures are printed on the
  municipality's own paper, which the operator is holding. What an operator
  needs HERE is a status: is there a paper, is it still the current one, and
  is the year covered. `:section/coverage` answers the last of those in
  months, and `payroll.juminzei/assess` is where a figure comes from."
  [registered readable? why]
  (let [readable? (boolean readable?)
        notices (if readable? (vec registered) [])
        live (juminzei/effective-notices notices)
        live-ids (into #{} (map :notice/id) live)
        years (vec (sort (distinct (keep :notice/tax-year live))))]
    {:section/id :resident-tax
     :section/label "住民税（特別徴収）"
     :section/answer (cond (not readable?) :unreadable
                           (seq notices) :registered
                           :else :not-registered)
     :section/registered (when readable? (count notices))
     :section/effective (when readable? (count live))
     ;; the DIFFERENCE, so a correction is visible AS a correction. A screen
     ;; that showed only what is in force would show one notice where a
     ;; municipality sent two, and the employee asking 「なぜ8月と9月で控除額が
     ;; 違うのか」 could not be answered from it.
     :section/superseded (when readable? (- (count notices) (count live)))
     :section/source juminzei/source
     :section/collection-months juminzei/collection-months
     :section/remittance juminzei/remittance-rule
     :section/registration resident-tax-registration
     ;; One `coverage` per distinct tax year among the EFFECTIVE notices,
     ;; sorted. Effective and not registered, because a year whose only notice
     ;; was replaced is a year `coverage` will report as uncovered — and it is
     ;; right to: the replaced paper is not in force and this actor will not
     ;; carry a figure forward off it.
     :section/coverage
     (mapv #(juminzei/coverage {:tax-year % :notices notices}) years)
     :section/notices
     ;; The field names below are read off `payroll.juminzei/notice-fields`
     ;; and not guessed. They were guessed once: this section asked a notice
     ;; for `:notice/year`, which no admitted record has (it is
     ;; `:notice/tax-year`), and counted the twelve months at the TOP level of
     ;; the notice, where they are not — they are under `:notice/months`. Both
     ;; answered without throwing: a blank year and `0 か月`. Nothing caught it
     ;; because the only test of this section passed it an EMPTY notice list,
     ;; so the registered branch had never once been rendered.
     (vec (for [n notices]
            {:notice/municipality (:notice/municipality n)
             :notice/tax-year (:notice/tax-year n)
             :notice/kind (:notice/kind n)
             :notice/revision (:notice/revision n)
             :notice/superseded? (not (contains? live-ids (:notice/id n)))
             :notice/effective-from (:notice/effective-from n)
             :notice/months-registered
             (count (keep (partial get (:notice/months n)) juminzei/month-keys))
             :notice/months-required (count juminzei/month-keys)}))
     :section/why
     (cond
       (not readable?)
       (str "この事業主の通知の履歴を末尾まで読めなかった: " (or why "理由不明")
            "。読めない履歴は空の履歴ではない —— "
            "一件も登録されていないのか、"
            "登録されたものを読み出せないのかは別のことであり、"
            "後者で転記をやり直すと、"
            "同じ紙が二度登録されて訂正の履歴が黙って分岐する。"
            "件数は数えていないので、ここには出さない")

       (seq notices)
       (str (count notices) " 件の通知が登録されている"
            "（有効 " (count live) " 件、差し替え済み " (- (count notices) (count live))
            " 件）。"
            "差し替えられた通知も残してある —— "
            "区市町村が何を訂正したのかは、"
            "訂正後の紙だけからは示せない。"
            "金額はその通知書の値であって、この actor が計算したものではなく、"
            "この画面には出さない")

       :else
       (str "決定通知書が一件も登録されていない。"
            "この actor は住民税を計算しない —— "
            "税額は市区町村が決定して通知するものであり、"
            "通知が無い月は「住民税ゼロ」ではなく「わからない」である"))}))

(defn overtime
  "割増賃金 — the rates that were read, and what is still registered by hand."
  []
  {:section/id :overtime
   :section/label "割増賃金（労基法 第三十七条）"
   :section/sources (vec (vals warimashi/sources))
   :section/categories
   (vec (for [c warimashi/categories]
          {:category/id (:category/key c)
           :category/label (:category/label c)
           :category/rate (:category/rate c)
           :category/provision (:category/provision c)}))
   :section/excluded-allowances (vec warimashi/excluded-allowances)
   :section/rounding-policies (vec (keys warimashi/rounding-policies))
   :section/refusals (vec (sort warimashi/refusals))
   :section/why
   (str "割増率は条文から転記済み。"
        "時間単価の基礎から除外する手当（第三十七条第五項・"
        "施行規則 第二十一条）は限定列挙であり、"
        "どれに当たるかは事業主が登録する —— "
        "名前からは決まらない")})

(defn rate-versions
  "Which rate tables this deployment holds, with the document each came from.

  The 源泉徴収税額表 row is the one to read carefully. Its 231 bands ARE now
  transcribed, out of a workbook pinned by SHA-256 — so this section reports
  the pin next to the count, because `231 bands` is a claim about a quantity
  and `this digest` is a claim about which edition. A row that reported only
  the first would look identical after the 告示 was amended underneath it.

  What is still unread is reported in the same map, by name
  (`:table/not-transcribed`): 日額表, 賞与の算出率の表, and the 端数処理 for
  the two segments where the workbook prints a rate instead of an amount.
  Reporting the table as complete because it has rows is exactly the drift
  this section is for.

  `:section/sources-without-url` is computed rather than assumed. A source
  row is a citation, and a citation the operator cannot follow is worth
  naming — but it is named as a MEASUREMENT of this deployment's rows, not
  as a requirement that every kind of source must have one. A 条文 that was
  read from a corpus with no public address would be legitimate and would
  appear here rather than being quietly dropped or falsely given a link."
  []
  {:section/id :rates
   :section/label "料率・税額表の版"
   :section/sources (vec (vals rates/sources))
   :section/sources-without-url
   (vec (for [[k v] rates/sources :when (str/blank? (str (:source/url v)))]
          {:source/key k :source/title (:source/title v)
           :source/authority (:source/authority v)}))
   :section/insurance
   (vec (for [r rates/insurance-rates]
          {:rate/scheme (:rate/scheme r)
           :rate/label (:rate/label r)
           :rate/prefecture (:rate/prefecture r)
           :rate/effective-from (:rate/effective-from r)
           :rate/effective-to (:rate/effective-to r)
           :rate/source (:rate/source r)}))
   :section/prefectures-transcribed
   (vec (sort (distinct (keep #(when (string? (:rate/prefecture %))
                                 (:rate/prefecture %))
                              rates/insurance-rates))))
   :section/withholding-table
   (let [t rates/withholding-table]
     {:table/label (:table/label t)
      :table/effective-from (:table/effective-from t)
      :table/effective-to (:table/effective-to t)
      :table/transcribed? (:table/transcribed? t)
      :table/bands (count (:table/bands t))
      :table/thresholds (count (:table/thresholds t))
      :table/segments (+ (count (:table/kou-segments t))
                         (count (:table/otsu-segments t)))
      :table/transcribed-by (:table/transcribed-by t)
      :table/sha256 (get-in t [:table/provenance :source/sha256])
      :table/source-url (get-in t [:table/provenance :source/url])
      :table/retrieved-at (get-in t [:table/provenance :source/retrieved-at])
      :table/not-transcribed (vec (:table/not-transcribed t))
      :table/why (:table/why t)})
   ;; 別表第五 and the 速算表 are 税額表 too, and this deployment does not hold
   ;; them. They live beside the monthly table here rather than only inside
   ;; `payroll.artifact.gensen`, because an operator reading `料率・税額表の版`
   ;; is asking which tables this deployment has — and the answer `the monthly
   ;; one, and not the two the year-end settlement needs` is the whole answer.
   :section/annual-tables
   (vec (for [t gensen/annual-tables]
          {:table/label (:table/label t)
           :table/read? (:table/read? t)
           :table/why (:table/why t)}))
   :section/refusals (vec (sort (concat rates/rate-refusals
                                        rates/withholding-refusals)))
   :section/why
   (str "都道府県別の料率は転記済みの支部だけが引ける。"
        "未転記の支部・適用期間外の月はいずれも拒否になり、"
        "近い値で代用されることはない。"
        "源泉徴収税額表は月額表を転記済みだが、"
        "率が印字されている二つの区間の端数処理は読めていないので、"
        "そこは厳密な有理数を返して丸めを拒否する")})

(defn artifacts
  "What this console can produce, and what none of them claims to be."
  []
  {:section/id :artifacts
   :section/label "出力できる書類"
   :section/artifacts
   ;; `:artifact/key` is renamed on the way out. `payroll.sensitive` blocks a
   ;; key whose tail is `key` — it is matching `:envelope/key` — and a
   ;; redactor that silently dropped this field would leave the section
   ;; rendering blank rows. The looser match is correct for a denylist and
   ;; the rename is correct here.
   (vec (for [a views/artifacts]
          (-> a (assoc :artifact/id (:artifact/key a))
              (dissoc :artifact/key))))
   :section/any-statutory? (boolean (some :artifact/statutory? views/artifacts))
   ;; Read off `payroll.ui.views` rather than written here, and the console's
   ;; router reads the same def. A screen that offers a download and a router
   ;; that serves one are two places for one path, and the second is the one
   ;; that 404s after a rename.
   :section/export-path views/export-path
   :section/why (str "どれも法定様式ではない。"
                     "様式そのものを読んでいない書類を"
                     "「法定調書です」と名乗らせない")})

(defn moneyforward
  "The import boundary, the cycles recorded, and the last reconciliation.

  `:section/columns-verified` is zero and is REPORTED as a number rather than
  omitted: the column names are this repository's guesses, and a guess that
  stops being labelled becomes a fact."
  [cycles reconciliation]
  {:section/id :moneyforward
   :section/label "MoneyForward 取込・突合"
   :section/columns (count mf-schema/columns)
   :section/columns-verified (count (filter :mf/verified? mf-schema/columns))
   :section/no-counterpart (vec mf-schema/no-counterpart-columns)
   :section/cycles
   (vec (for [c (sort-by :cycle/period cycles)]
          {:cycle/period (:cycle/period c)
           :cycle/month-kind (:cycle/month-kind c)
           :cycle/month-reason (:cycle/month-reason c)
           :cycle/reconciled? (:cycle/reconciled? c)
           :cycle/compared (:cycle/compared c)
           :cycle/mapped-rows (:cycle/mapped-rows c)
           :cycle/differences (count (:cycle/differences c))
           :cycle/approved-by (:cycle/approved-by c)
           :cycle/approved-at (:cycle/approved-at c)}))
   :section/latest
   (when reconciliation
     {:reconcile/compared (:reconcile/compared reconciliation)
      :reconcile/rows (:reconcile/rows reconciliation)
      :reconcile/reconciled? (:reconcile/reconciled? reconciliation)
      :reconcile/blockers (vec (:reconcile/blockers reconciliation))
      :reconcile/unknown-columns (vec (:reconcile/unknown-columns reconciliation))
      :reconcile/why (:reconcile/why reconciliation)})
   :section/why
   (str "列名はすべて推測であり、実際のエクスポートを一度も読んでいない。"
        "突合できた run が 0 件のレポートは「差分が無い」ではない")})

(defn cutover-section
  "The gate, its progress, and every condition holding it."
  [evaluation]
  {:section/id :cutover
   :section/label "並行運用の終了条件"
   :section/passed? (:cutover/passed? evaluation)
   :section/progress (:cutover/progress evaluation)
   :section/conditions
   (vec (for [c (:cutover/conditions evaluation)]
          {:gate/id (:gate/key c) :gate/label (:gate/label c)
           :gate/met? (boolean (:gate/met? c)) :gate/why (:gate/why c)}))
   :section/blockers (vec (:cutover/held-by evaluation))
   :section/durability (:cutover/durability evaluation)
   :section/why (:cutover/why evaluation)})

(defn store-section
  "Whether the store could be READ, kept separate from whether it is empty.

  `:section/entries-are-a-floor?` is the field that matters: on an
  incomplete read the counts are a lower bound, and a reader who takes them
  for totals has been told the employer filed less than they did."
  [health]
  (if (nil? health)
    {:section/id :store :section/label "保存先"
     :section/answer :not-reported
     :section/why (str "この配備の store は自身の健全性を報告しない"
                       "（durable backend ではない）。"
                       "報告が無いことは「健全である」ではない")}
    {:section/id :store :section/label "保存先"
     :section/answer (if (:store/readable? health) :readable :unreadable)
     :section/mode (:store/mode health)
     :section/survives-restart? (:store/survives-process-restart? health)
     :section/streams (vec (:store/streams health))
     :section/entries-are-a-floor? (:store/entries-are-a-floor? health)
     :section/break-kinds (vec (:store/break-kinds health))
     :section/keys-separated (:store/key-separation health)
     :section/why (:store/why health)}))

(defn- preflight-summary
  "`payroll.projection.r2/preflight` reduced to what a screen may show.

  A PROJECTION of the preflight and not the preflight itself, for two
  reasons. The configuration map carries `:r2/token-provider`, and while its
  value is a NAME rather than a token, a report that forwarded whatever the
  environment produced would be one nobody could keep safe by reading this
  file. And `:config/key` — on the rows naming a missing variable — has the
  tail `key`, which `payroll.sensitive` blocks, so forwarding it whole would
  drop the field and leave the panel rendering rows with a blank column.

  nil in, nil out: a deployment that computed no preflight is different from
  one that computed a failing preflight, and the screen says which."
  [pf]
  (when pf
    {:preflight/ready? (boolean (:preflight/ready? pf))
     :preflight/reason (:preflight/reason pf)
     :preflight/why (:preflight/why pf)
     :preflight/missing
     (vec (for [c (:preflight/missing pf)]
            {:config/env (:config/env c)
             :config/label (:config/label c)
             :config/secret? (boolean (:config/secret? c))
             :config/why (:config/why c)}))
     :preflight/permissions
     (vec (for [r (:preflight/required-permissions pf)]
            (select-keys r [:permission/scope :permission/level
                            :permission/why :permission/observed])))
     :preflight/blocker
     (when-let [b (:preflight/blocker pf)]
       (select-keys b [:blocker/create-namespace :blocker/create-table
                       :blocker/diagnosis :blocker/resolution
                       :blocker/until-then]))}))

(defn projection-section
  "Whether the analytical projection exists, is reachable, and read back.

  `:missing` is a FACT and not an error — a projection that has never been
  built is a different state from one that is broken, and the operator's next
  action differs.

  `preflight` is the third distinguishable thing and answers a question the
  other two cannot: `not-configured` says this deployment holds no catalog
  driver, and the preflight says whether the deployment's ENVIRONMENT could
  supply one — configuration missing, or configured and blocked on a token
  permission that was measured rather than guessed. It makes no request."
  [health verification preflight]
  (let [pf (preflight-summary preflight)]
    (if (nil? health)
      (cond-> {:section/id :projection
               :section/label "分析用の投影（R2 Data Catalog）"
               :section/answer :not-configured
               :section/why (str "R2 Data Catalog が設定されていない。"
                                 "未設定は「投影が正しい」ではない")}
        pf (assoc :section/preflight pf))
      (cond-> {:section/id :projection
               :section/label "分析用の投影（R2 Data Catalog）"
               :section/answer (cond (:projection/built? health) :built
                                     (:projection/reachable? health) :not-built
                                     :else :unreachable)
               :section/namespace (:projection/namespace health)
               :section/tables (vec (:projection/tables health))
               :section/privacy (:projection/privacy health)
               :section/verification
               (when verification
                 {:verify/status (:verify/status verification)
                  :verify/found (:verify/found verification)
                  :verify/expected (:verify/expected verification)
                  :verify/why (:verify/why verification)})
               :section/why (:projection/why health)}
        pf (assoc :section/preflight pf)))))

(defn- registered-notices
  "This employer's 住民税 notices, read from the store, with a store that
  REFUSES the read kept apart from a store that has none.

  `payroll.store.kotobase` fails closed on a chain it cannot walk to the end,
  so this read can THROW. The throw is caught here rather than propagated,
  because one unreadable chain must not take the whole report down: an
  operator whose store is damaged is exactly the operator who needs the other
  seven sections — the store panel, the cutover blockers, the projection
  preflight — and a 500 tells them nothing about any of them.

  What it must NOT do is turn the throw into an empty vector. That is the
  defect this repository is organised around and CLAUDE.md names as the most
  common one on this fleet: a read that could not run returning the value of
  a read that ran and found nothing. So the answer carries the flag AND the
  reason, and `resident-tax` renders `:unreadable`."
  [store employer]
  (try {:notices/readable? true
        :notices/notices (vec (store/juminzei-notices store employer))}
       (catch #?(:clj Exception :cljs :default) e
         {:notices/readable? false
          :notices/why #?(:clj (.getMessage ^Exception e)
                          :cljs (.-message e))})))

(defn report
  "The whole operations surface for one employer.

    {:store :employer :reconciliation
     :store-health :projection-health :projection-verification
     :projection-preflight}

  Returns a map of sections, redacted. Deterministic: it reads only what it
  is given plus this repository's own constants, and holds no clock, no
  randomness and no ordering that depends on a hash — `payroll.operations`
  is asserted to produce byte-identical output for identical input.

  ## The notices are READ HERE and are not an option

  They used to be `:juminzei-notices`, injected by the caller. Nothing in
  this repository ever supplied it except the test suite, so every real
  deployment — the console screen and `GET /api/operations` alike — rendered
  「決定通知書が一件も登録されていない」 for employers who had registered
  notices, and the section that exists to say `absence is not zero` was
  itself reporting an absence it had never looked for. An injected read of a
  store the function already holds is a second source for one fact, and the
  one nobody wires up is the one that answers wrong in production and right
  in the tests."
  [{:keys [store employer reconciliation
           store-health projection-health projection-verification
           projection-preflight]}]
  (let [ev (cutover/evaluate {:store store :employer employer
                              :projection-verification projection-verification})
        notices (registered-notices store employer)]
    (redact
     {:report/actor "cloud-itonami-isco-4313"
      :report/employer employer
      :report/sections
      [(resident-tax (:notices/notices notices)
                     (:notices/readable? notices)
                     (:notices/why notices))
       (overtime)
       (rate-versions)
       (artifacts)
       (moneyforward (:cutover/cycles ev) reconciliation)
       (cutover-section ev)
       (store-section store-health)
       (projection-section projection-health projection-verification
                           projection-preflight)]
      :report/why
      (str "この応答は、この配備が何を読んだかを述べるだけである。"
           "給与の計算が正しいことも、"
           "この事業主の届出が済んでいることも主張しない")})))

(defn blockers
  "Every unmet condition across the report, as a flat list an operator can
  work down. Ordered by section so the list is stable."
  [rep]
  (vec (for [s (:report/sections rep)
             b (cond
                 (= :cutover (:section/id s))
                 (for [c (:section/conditions s) :when (not (:gate/met? c))]
                   {:blocker/what (:gate/label c) :blocker/why (:gate/why c)})

                 ;; Three ways 住民税 holds a run, and they stay three. Their
                 ;; reasons are three different operator actions —
                 ;; 「一件も登録されていない」 is `transcribe the paper`,
                 ;; 「読めなかった」 is `do not transcribe anything yet`, and
                 ;; 「12か月のうち n か月しか通知に基づいていない」 is
                 ;; `find the notice for the months nobody has a paper for`.
                 ;; Folding them into one blocker would send an operator to
                 ;; the wrong one of the three at least twice.
                 (= :resident-tax (:section/id s))
                 (case (:section/answer s)
                   :unreadable
                   [{:blocker/what "住民税の通知の読み出し"
                     :blocker/why (:section/why s)}]

                   :not-registered
                   [{:blocker/what "住民税の決定通知書"
                     :blocker/why (:section/why s)}]

                   (for [c (:section/coverage s)
                         :when (not (:coverage/complete? c))]
                     {:blocker/what (str "住民税の通知（"
                                         (:coverage/tax-year c) " 年度）")
                      :blocker/why
                      (str (:coverage/tax-year c) " 年度は、12か月のうち "
                           (:coverage/months-covered c)
                           " か月しか通知に基づいていない。"
                           (:coverage/why c))}))

                 (and (= :store (:section/id s))
                      (not= :readable (:section/answer s)))
                 [{:blocker/what "保存先の読み出し" :blocker/why (:section/why s)}]

                 (and (= :projection (:section/id s))
                      (not= :built (:section/answer s)))
                 [{:blocker/what "分析用の投影" :blocker/why (:section/why s)}]

                 (and (= :rates (:section/id s))
                      (not (get-in s [:section/withholding-table
                                      :table/transcribed?])))
                 [{:blocker/what "源泉徴収税額表"
                   :blocker/why (get-in s [:section/withholding-table :table/why])}]

                 ;; The table IS transcribed, and there is still work on it.
                 ;; A blocker that vanished the moment the bands landed would
                 ;; tell the operator the row was finished when what actually
                 ;; happened is that its largest part was.
                 (and (= :rates (:section/id s))
                      (seq (get-in s [:section/withholding-table
                                      :table/not-transcribed])))
                 (cons
                  {:blocker/what "源泉徴収税額表"
                   :blocker/why
                   (str "月額表は "
                        (get-in s [:section/withholding-table :table/bands])
                        " 帯すべて転記済み。まだ読めていないのは: "
                        (str/join "、"
                                  (map :gap/what
                                       (get-in s [:section/withholding-table
                                                  :table/not-transcribed])))
                        "。読めていない区間は拒否になり、"
                        "近い値で代用されることはない")}
                  (for [t (:section/annual-tables s) :when (not (:table/read? t))]
                    {:blocker/what (:table/label t)
                     :blocker/why (str (:table/why t)
                                       "。年末調整の年税額は、"
                                       "この表が読まれるまで拒否のままになる")}))

                 :else [])]
         (assoc b :blocker/section (:section/id s)))))

(defn ->text
  "The report as plain lines, for an operator who wants to paste it into a
  ticket. Never HTML and never a table — this is the form that survives being
  copied."
  [rep]
  (str/join
   "\n"
   (concat
    [(str "運用の現況 — " (:report/employer rep))
     (:report/why rep)
     ""]
    (for [s (:report/sections rep)]
      (str "■ " (:section/label s)
           (when-let [a (:section/answer s)] (str "（" (name a) "）"))
           "\n  " (:section/why s)))
    [""
     (str "未了 " (count (blockers rep)) " 件")]
    (for [b (blockers rep)]
      (str "  - " (:blocker/what b) ": " (:blocker/why b))))))
