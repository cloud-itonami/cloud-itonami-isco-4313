(ns payroll.juminzei
  "住民税 特別徴収 — a tax this actor REGISTERS from a notice and never
  computes.

  ## The whole design is in one sentence from the source

  「従業員の給与から『特別徴収税額の決定通知書』に記載の税額を差し引きし、
  区市町村ごとにとりまとめ、区市町村から送付される納入書で納入します。
  **所得税と違い、税額の計算をする手間がありません。**」

    東京都・都内区市町村「個人住民税（区市町村民税・都民税）特別徴収の
    事務手引き」令和８年１月
    https://www.tax.metro.tokyo.lg.jp/documents/d/tax/tebiki-tokubetsu-tax-r8-1

  So 住民税 is the opposite of every other figure in this repository. The four
  社会保険 premiums and the income tax are amounts an operator declares and
  this actor cannot check; 住民税 is an amount a MUNICIPALITY decided, sent on
  paper, and this actor's only job is to hold it, hand out the right month's
  figure, and refuse when there is no notice.

  That is why there is no rate here, no table, and no arithmetic that
  produces a tax. The arithmetic that IS here is the two summaries an
  employer needs — what to deduct this month and what to remit — and both are
  sums over registered figures.

  ## What was read, item by item

  | fact | source |
  |---|---|
  | 徴収期間は 6月から翌年5月までの12か月 | 手引き（5）納期と納入方法 |
  | 納期限は月割額を徴収した月の翌月10日（土日祝なら次の平日） | 同 |
  | 納期の特例（常時10人未満）は 6–11月分を12月10日、12月–翌5月分を6月10日 | 同 |
  | 税額は区市町村が計算し、5月末日までに決定通知書を送付する | （4）特別徴収税額決定通知書の送付 |
  | 変更は「特別徴収税額の変更通知書」で届き、通知された変更月から差し引く | （6）税額の変更通知 |
  | 給与支払報告書は1月31日までに提出（地方税法 第317条の6） | （3） |
  | 特別徴収義務者の指定は 地方税法 第41条・第321条の4・第328条の5第1項 | （1） |
  | 6/1–12/31 の退職は申し出があれば一括徴収 | （7） |
  | 翌1/1–4/30 の退職は 地方税法 第321条の5第2項 により申し出なしでも5/31までに一括徴収 | （7） |
  | 異動届は事由発生月の翌月10日まで | （8） |

  ## What is NOT read, and is therefore refused

  - **地方税法 itself.** The article numbers above are the ones the 手引き
    cites; the statute text has not been retrieved from e-Gov the way
    `payroll.shakai-hoken`'s four were. So this namespace quotes a
    municipality's guide and says so, and does not claim to have read the law.
  - **Any municipality other than the 都内区市町村 the guide covers.** A
    notice from elsewhere registers exactly the same way — the shape is
    national — but the DUE DATES and the 納期の特例 above are what this guide
    says, and `:municipality/guide-read?` is false for anywhere else.
  - **The calendar.** 「土・日曜日、又は祝日の場合は、その次の平日」 is a
    rule about a calendar this actor does not have, so a due date is reported
    as the 10th WITH that rule attached, never as a resolved date.
  - **Whether a notice is genuine.** A registered notice is an operator's
    transcription of a piece of paper. Nothing here verifies it, exactly as
    nothing verifies `:employment/year-end-declaration-filed?`.

  ## 特別徴収 is one word for two different documents

  国税庁's 法定調書の手引 (令和8年分) has a 第3章 titled
  「退職所得の源泉徴収票・特別徴収票」, and that 特別徴収票 is **not** what
  this namespace holds. It is a 法定調書 about 退職手当等 — an income tax
  document, filed with the 税務署, about a payment an employer made. What
  `payroll.juminzei` registers is the 住民税の特別徴収税額の決定通知書: a
  municipality's decision about a RESIDENT's local tax, sent to the employer
  as the 特別徴収義務者, and never filed anywhere by this actor.

  Same word, different document, different addressee, different tax, and a
  different direction of travel — one goes out to a tax office, the other
  arrives from a town hall. `payroll.artifact.gensen/amendments-2026` records
  the 令和8年分 change to the first one, and it changes nothing here.

  ## Where a notice is KEPT

  `admit-registration` and `register-notice!` are the write boundary, and
  `payroll.store/Store`'s notice stream is where an admitted record lands. A
  notice is its own stream and not a ledger shape for the reason
  `payroll.store` states there: nothing in the graph produces one, the
  governor never sees one, and an operator registering a municipality's
  decision is not an event in a payroll run's life.

  Nothing is ever overwritten. A correction is a NEW entry naming the notice
  it replaces (`:notice/replaces`), and what is current is DERIVED by
  `effective-notices` — which is why a superseded notice is still readable and
  the console can show what a municipality corrected."
  (:require [clojure.string :as str]
            [payroll.store :as store]))

(def source
  {:source/title "個人住民税（区市町村民税・都民税）特別徴収の事務手引き"
   :source/authority "東京都・都内区市町村"
   :source/edition "令和８年１月"
   :source/url "https://www.tax.metro.tokyo.lg.jp/documents/d/tax/tebiki-tokubetsu-tax-r8-1"
   :source/read-at "2026-08-26"
   :source/cites ["地方税法 第41条" "地方税法 第317条の6"
                  "地方税法 第321条の4" "地方税法 第321条の5第2項"
                  "地方税法 第328条の5第1項"]
   :source/limit (str "区市町村の手引きであって、地方税法の条文そのものではない。"
                      "条文は e-Gov から取得していない")})

(def collection-months
  "6月から翌年5月まで、支給月の順。

  `:month/ordinal` is 1–12 in collection order and `:month/of-year` is
  `:current` for June–December and `:next` for January–May, because a tax
  year's twelve instalments span two calendar years and a vector of month
  numbers alone cannot say which January is meant."
  (vec (for [[i m] (map-indexed vector [6 7 8 9 10 11 12 1 2 3 4 5])]
         {:month/ordinal (inc i)
          :month/month m
          :month/of-year (if (>= m 6) :current :next)
          :month/label (str (if (>= m 6) "" "翌年") m "月")})))

(def month-keys
  "The twelve keys a notice registers, in collection order:
  `:juminzei/m06` … `:juminzei/m05`.

  `m06` and not `06`: a keyword whose name starts with a digit PRINTS fine
  and cannot be READ back, so `:m/06` would round-trip through
  `payroll.store.kotobase`'s EDN blocks as far as the write and fail at the
  read. Measured 2026-08-26 — the reader answered `Invalid token: :m/10`."
  (mapv #(keyword "juminzei"
                  (str "m" (when (< (:month/month %) 10) "0") (:month/month %)))
        collection-months))

(def remittance-rule
  {:rule/deadline "月割額を徴収した月の翌月10日"
   :rule/holiday (str "この日が土・日曜日、又は祝日の場合は、その次の平日。"
                      "この actor は暦を持たないので、日付は解決しない")
   :rule/special (str "納期の特例（給与の支払いを受ける者が常時10人未満、"
                      "区市町村長の承認）: 6月から11月までの分を12月10日までに、"
                      "12月から翌年5月までの分を6月10日までに納入する。"
                      "特例は納期の特例であって、"
                      "従業員の給与からは毎月徴収する")
   :rule/source "手引き（5）納期と納入方法"})

(def notice-kinds
  "決定通知書 and 変更通知書 — different documents with different rules.

  A 決定 establishes twelve months; a 変更 replaces the figures from a stated
  month onward (「通知された変更月から徴収金額を変更して」). Modelling the
  second as `just register the decision again` would lose which month the
  change starts in, and an employer that applied a mid-year change from June
  has under- or over-deducted every month since."
  {:notice/decision
   {:kind/label "特別徴収税額の決定通知書"
    :kind/arrives "毎年5月末日までに事業所へ送付される"
    :kind/establishes :twelve-months}
   :notice/revision
   {:kind/label "特別徴収税額の変更通知書"
    :kind/arrives "税額が変更になったときに送付される"
    :kind/establishes :from-a-stated-month}})

(def answers
  "Answers that ARE an answer about this month's 住民税."
  #{:notified :not-a-collection-month :no-obligation-registered})

(def refusals
  "Answers that are the absence of one. A set rather than the complement, for
  `payroll.shakai-hoken/refusals`' reason: an answer added and not classified
  belongs to neither, so `answerable?` is false and the caller refuses."
  #{:no-notice :notice-year-mismatch :month-not-in-notice
    :malformed-notice :municipality-not-declared})

;; ---------------------------------------------------------------------------
;; A notice
;; ---------------------------------------------------------------------------

(defn- whole-yen? [x]
  (and (number? x) (not (neg? x)) (zero? (mod x 1))))

(defn- named-string?
  "A non-blank string that carries no `/`.

  The slash is refused because `notice-id` joins six components with one, and
  a component that may contain a slash makes the join ambiguous: 「架空区/2」
  と「架空区」+「2」 would produce the same id, and two different notices
  sharing an id is a correction history that silently forks."
  [x]
  (and (string? x) (not (str/blank? x)) (not (str/includes? x "/"))))

(def notice-fields
  "What an operator transcribes off the paper. Nothing is optional and nothing
  is defaulted — a notice with eleven months registered is not a notice with a
  zero in the twelfth."
  [{:field/key :notice/kind :field/label "通知の種類"
    :field/admits #(contains? notice-kinds %)
    :field/why ":notice/decision または :notice/revision"}
   {:field/key :notice/municipality :field/label "区市町村"
    :field/admits named-string?
    :field/why (str "通知を出した区市町村の名称。納入先はここで決まる。"
                    "「/」は入れられない —— 通知の ID を組み立てる区切りである")}
   {:field/key :notice/tax-year :field/label "年度"
    :field/admits #(and (string? %) (re-matches #"\d{4}" %))
    :field/why "YYYY（徴収が6月に始まる年度）"}
   {:field/key :notice/reference :field/label "通知書番号"
    :field/admits named-string?
    :field/why (str "区市町村がその紙に付した番号。"
                    "訂正・再交付を明示的に指すために、"
                    "どの紙から書き写したのかを名指しできること。"
                    "「/」は入れられない —— 通知の ID を組み立てる区切りである")}
   {:field/key :notice/revision :field/label "改訂番号"
    :field/admits #(and (integer? %) (not (neg? %)))
    :field/why (str "0 が初回。訂正・再交付のたびに 1 つ上げる。"
                    "同じ紙の二度目の転記と、"
                    "区市町村が出し直した紙とを区別する")}
   {:field/key :notice/replaces :field/label "差し替える通知の ID"
    ;; nil-or-string and NOT `named-string?`: this field holds a `notice-id`,
    ;; which is six components joined with `/` — the very character the
    ;; components themselves may not contain. Whether it is required, and
    ;; whether it names something this employer has actually registered, is
    ;; `admit-registration`'s question, because neither can be answered by
    ;; looking at the value alone.
    :field/admits #(or (nil? %) (and (string? %) (not (str/blank? %))))
    :field/why (str "改訂番号が 0 のときは nil。"
                    "1 以上のときは、どの通知を差し替えるのかを"
                    ":notice/id で名指しする（payroll.juminzei/admit-registration "
                    "がそれを検査する）")}
   {:field/key :notice/designated-number :field/label "指定番号"
    :field/admits #(or (nil? %) (string? %))
    :field/why "区市町村が特別徴収義務者に付す番号。任意"}
   {:field/key :notice/months :field/label "月割額"
    :field/admits map?
    :field/why (str "6月から翌年5月までの12か月分（:juminzei/m06 … :juminzei/m05）。"
                    "変更通知は :notice/effective-from 以降の月だけでよい")}
   {:field/key :notice/annual-total :field/label "年税額"
    :field/admits #(or (nil? %) (whole-yen? %))
    :field/why (str "決定通知書には必ず記載があり、12か月の月割額の合計と一致する"
                    "（一致しない転記は「安い税」ではなく書き写しの誤りである）。"
                    "変更通知は適用開始月以降の月しか載せていないので、"
                    "その月割額を年税額と突き合わせることはできない —— "
                    "だから変更通知では nil を許す")}
   {:field/key :notice/effective-from :field/label "変更の適用開始月"
    :field/admits #(or (nil? %) (contains? (set month-keys) %))
    :field/why "変更通知のみ。通知された変更月から徴収金額を変更する"}
   {:field/key :notice/registered-at :field/label "受領・転記した日"
    :field/admits #(and (string? %) (not (str/blank? %)))
    :field/why (str "この actor は暦を持たない。"
                    "受領日は操作者が登録するものであって、"
                    "ここで導出されるものではない"
                    "（payroll.cutover の :cycle/approved-at と同じ規則）")}])

(defn admit-notice
  "Admit one transcribed notice, or refuse.

  A decision notice must carry all twelve months; a revision must carry
  `:notice/effective-from` and every month from there to 翌年5月. Partial
  registration is refused rather than filled in with the previous notice's
  figures — an employer that carried a stale figure forward has deducted an
  amount no municipality asked for, and 納入 is by the municipality's own
  納入書 against their own total.

  ## The twelve months of a 決定通知書 must add up to its 年税額

  Both figures are printed on the same piece of paper, so a disagreement
  between them is a transcription error and never a smaller tax. Checking it
  is the one arithmetic here that can catch a mistyped month — a digit
  dropped from 月割額 would otherwise register as a real, lawful-looking
  deduction and be paid every month for a year.

  **A 変更通知書 is NOT checked this way**, and that is not an oversight: it
  carries only the months from `:notice/effective-from` onward
  （「通知された変更月から徴収金額を変更して」）, so summing what it carries
  answers a question about part of a year and comparing that to a year total
  would refuse every correct 変更通知書 there is. Its `:notice/annual-total`
  is therefore allowed to be nil, and when it is present nothing here
  reconciles it."
  [employer-id m]
  (let [bad (vec (for [{:field/keys [key label admits why]} notice-fields
                       :when (not (admits (get m key)))]
                   {:notice/key key :notice/label label
                    :notice/why (str label "が受け付けられない（" why "）。"
                                     "登録値: " (pr-str (get m key)))}))
        kind (:notice/kind m)
        from (:notice/effective-from m)
        required (case kind
                   :notice/decision month-keys
                   :notice/revision (when from
                                      (drop-while #(not= from %) month-keys))
                   nil)
        missing (vec (for [k required
                           :when (not (whole-yen? (get (:notice/months m) k)))]
                       k))
        ;; only once every month is present — summing a map with a hole in it
        ;; would report a total nobody wrote on any paper.
        summed (when (and (= :notice/decision kind) (empty? missing))
                 (reduce + 0 (map #(get (:notice/months m) %) month-keys)))]
    (cond
      (seq bad)
      {:notice/status :refused :notice/why (str/join "、" (map :notice/why bad))
       :notice/violations bad}

      (and (= :notice/revision kind) (nil? from))
      {:notice/status :refused
       :notice/why (str "変更通知には適用開始月（:notice/effective-from）が要る。"
                        "「通知された変更月から徴収金額を変更」するので、"
                        "開始月の無い変更は適用できない")}

      (seq missing)
      {:notice/status :refused
       :notice/why (str "月割額が登録されていない月がある: "
                        (str/join "、" (map name missing))
                        "。未登録は零ではない —— "
                        "この actor は税額を計算しないので、"
                        "通知に無い月の額をどこからも導けない")
       :notice/missing-months missing}

      (and summed (not= summed (:notice/annual-total m)))
      {:notice/status :refused
       :notice/why (str "決定通知書の年税額は "
                        (pr-str (:notice/annual-total m))
                        " と登録されているが、12か月の月割額の合計は "
                        summed " である。"
                        "月割額が年税額に足し合わない決定通知書は"
                        "「安い税」ではなく書き写しの誤りである —— "
                        "どちらの数字も同じ一枚の紙に印字されている")
       :notice/annual-total-declared (:notice/annual-total m)
       :notice/annual-total-summed summed}

      :else
      {:notice/status :ok
       :notice/record (assoc (select-keys m (map :field/key notice-fields))
                             :notice/employer employer-id
                             :notice/source (:source/title source))})))

;; ---------------------------------------------------------------------------
;; What to deduct this month
;; ---------------------------------------------------------------------------

(def month-ordinal
  "A month key's position in the collection order, 0-based. nil for anything
  that is not one of the twelve."
  (into {} (map-indexed (fn [i k] [k i]) month-keys)))

(defn- month-key-of
  "`\"2026-08\"` → `:juminzei/m08`, or nil. Prefix arithmetic only — this actor parses
  no dates, and the YEAR is checked against the notice separately."
  [period]
  (when-let [[_ mm] (re-matches #"\d{4}-(\d{2})" (str period))]
    (let [k (keyword "juminzei" (str "m" mm))]
      (when (contains? (set month-keys) k) k))))

(defn- tax-year-of
  "Which 年度 a `YYYY-MM` period belongs to: June onward is that year's, and
  January–May belongs to the year before — because the twelve instalments run
  6月→翌年5月."
  [period]
  (when-let [[_ yyyy mm] (re-matches #"(\d{4})-(\d{2})" (str period))]
    (let [m #?(:clj (parse-long mm) :cljs (js/parseInt mm 10))
          y #?(:clj (parse-long yyyy) :cljs (js/parseInt yyyy 10))]
      (str (if (>= m 6) y (dec y))))))

;; ---------------------------------------------------------------------------
;; Identity and supersession
;;
;; Both pure, and both read over the same durable stream: `notice-id` is what
;; makes a retried transcription recognisable as one, and `effective-notices`
;; is what makes a correction a correction rather than a second opinion.
;;
;; They are here, above `assess`, because `assess` reads through them. A
;; superseded notice does not decide a month, and a version of this file where
;; only the operations screen knew that would have the payslip and the screen
;; answering differently about the same August.
;; ---------------------------------------------------------------------------

(defn notice-id
  "The identity of one notice:

    employer/municipality/tax-year/kind/reference/revision

  joined with `/` on exactly those six components, in that order, with `kind`
  as its bare name and `revision` as a string.

  Six and not fewer, because every one of them can differ between two notices
  an employer holds at once: two municipalities in the same year, a 決定 and a
  変更 from the same municipality, a re-issued paper with its own 通知書番号,
  and a correction of a paper already transcribed.

  **`:notice/municipality` and `:notice/reference` refuse a value containing
  `/` (`notice-fields`), and that refusal is what makes this join
  unambiguous.** Without it 「架空区/2」 with reference 「7」 and 「架空区」
  with reference 「2/7」 would produce the same id — and two notices sharing an
  id is a correction history that forks without anything reporting it."
  [n]
  (str/join "/" [(:notice/employer n)
                 (:notice/municipality n)
                 (:notice/tax-year n)
                 (some-> (:notice/kind n) name)
                 (:notice/reference n)
                 (str (:notice/revision n))]))

(defn effective-notices
  "Every registered notice that no OTHER registered notice replaces, in
  registration order.

  A notice is superseded when some other registered notice names its
  `:notice/id` in `:notice/replaces`. That is the whole rule, and the reason
  it is a rule rather than an overwrite: **a superseded notice is still in the
  store and is still readable.** Nothing is ever edited or deleted — the
  correction is a new entry that names what it corrects.

  That is what lets the console show what a municipality CORRECTED rather than
  only what it last said. An employer asked 「なぜ8月と9月で控除額が違うのか」
  can be shown both papers and the date each was transcribed; a store that had
  overwritten the first one could only show the second and assert that the
  first never existed.

  It also means this function is the only place that decides what is current.
  A caller that filtered the store's entries itself would be a second copy of
  this rule."
  [notices]
  (let [replaced (into #{} (keep :notice/replaces) notices)]
    (vec (remove #(contains? replaced (:notice/id %)) notices))))

(defn- governing-notice
  "Of `notices` — already narrowed to one 年度, registration order, newest LAST
  — the one that governs month `mk`, or nil.

  A 決定通知書 governs every month of its year; a 変更通知書 governs only from
  its `:notice/effective-from` onward（「通知された変更月から徴収金額を変更
  して」）, so a revision that starts in October does not govern August. Newest
  wins, which is what makes a later notice a correction rather than a second
  opinion.

  ONE function, called by both `assess` and `coverage`. It was written out
  twice for about an hour and that is exactly the drift this repository keeps
  refusing: `assess` answering `this month is covered` while `coverage`
  answered `it is not` is a disagreement nothing would report, because the two
  are read on different screens.

  `month-ordinal` and not `.indexOf`: host interop would make this `.cljc` a
  `.clj` in disguise, which is the same mistake `payroll.mf.import` records
  avoiding for the same reason."
  [notices mk]
  (last (filterv (fn [n]
                   (or (= :notice/decision (:notice/kind n))
                       (and (:notice/effective-from n)
                            mk
                            (<= (month-ordinal (:notice/effective-from n))
                                (month-ordinal mk)))))
                 notices)))

(defn assess
  "What this employee's 住民税 is for one period. Pure.

    {:period     \"YYYY-MM\"
     :notices    every notice registered for this contract, newest LAST
     :obligation :special-collection | :not-special-collection | nil}

  `:obligation` is an operator's registration of whether this employee is
  under 特別徴収 at all. The guide says an employer with 所得税の源泉徴収義務
  is designated a 特別徴収義務者 and that 普通徴収 needs one of the 普A–普F
  reasons — so `:not-special-collection` is a REGISTERED answer with a reason
  attached, and **nil is neither**: an employee nobody has classified is a
  refusal, because 「この普通徴収切替理由書の提出がない場合、原則どおり、
  特別徴収対象者となります」.

  Returns `{:juminzei/answer … :juminzei/answerable? … :juminzei/amount …}`
  where `:juminzei/amount` is present only for `:notified`.

  ## A superseded notice does not decide a month

  `notices` is the whole registered stream, superseded entries included —
  nothing is ever overwritten — so this runs `effective-notices` over it
  first. A notice some later notice REPLACED is not in force, and deducting
  the figure printed on a paper the municipality has since reissued is
  deducting an amount nobody is owed.

  It is also what keeps this function and `coverage` from disagreeing. They
  share `governing-notice`, but sharing the rule is not enough on its own: if
  one of them read the raw stream and the other read the effective set, they
  would answer opposite things about the same month — 「この月は通知がある」
  on the operations screen and a figure on the payslip, or the reverse — and
  the two are read on different screens by different people."
  [{:keys [period notices obligation]}]
  (let [mk (month-key-of period)
        ty (tax-year-of period)
        applicable (filterv #(= ty (:notice/tax-year %))
                            (effective-notices notices))
        governing (governing-notice applicable mk)
        amount (get-in governing [:notice/months mk])
        answer (cond
                 (nil? obligation) :municipality-not-declared
                 (= :not-special-collection obligation) :no-obligation-registered
                 (nil? mk) :malformed-notice
                 (empty? notices) :no-notice
                 (empty? applicable) :notice-year-mismatch
                 (nil? governing) :month-not-in-notice
                 (not (whole-yen? amount)) :month-not-in-notice
                 :else :notified)]
    (cond-> {:juminzei/answer answer
             :juminzei/answerable? (contains? answers answer)
             :juminzei/period period
             :juminzei/tax-year ty
             :juminzei/month mk
             :juminzei/municipality (:notice/municipality governing)
             :juminzei/source source
             :juminzei/why
             (case answer
               :notified (str (:notice/municipality governing) "の"
                              (get-in notice-kinds [(:notice/kind governing)
                                                    :kind/label])
                              "（" ty "年度）に記載された月割額。"
                              "この actor が計算した額ではない")
               :no-obligation-registered
               (str "この被用者は特別徴収の対象ではないと登録されている。"
                    "普通徴収に切り替えるには 普A〜普F の理由が要る —— "
                    "「切替理由書の提出がない場合、原則どおり、"
                    "特別徴収対象者となります」")
               :municipality-not-declared
               (str "この被用者が特別徴収の対象かどうかが登録されていない。"
                    "所得税の源泉徴収義務がある事業主は特別徴収義務者に"
                    "指定される（地方税法 第41条・第321条の4・"
                    "第328条の5第1項）ので、未登録は「対象外」ではない")
               :no-notice
               (str "特別徴収税額の決定通知書が登録されていない。"
                    "住民税の税額は区市町村が計算して通知するものであり、"
                    "この actor は計算しない（「所得税と違い、"
                    "税額の計算をする手間がありません」）")
               :notice-year-mismatch
               (str "登録されている通知は " ty
                    " 年度のものではない。"
                    "徴収期間は6月から翌年5月までの12か月なので、"
                    "年度を跨いだ流用はできない")
               :month-not-in-notice
               (str "通知に " (when mk (name mk))
                    " の月割額が無い。未登録は零ではない")
               :malformed-notice
               (str "対象期間 " (pr-str period) " が YYYY-MM ではない。"
                    "この actor は暦を持たず、期間を推測しない"))}

      (= :notified answer)
      (assoc :juminzei/amount amount
             :juminzei/notice-kind (:notice/kind governing)
             :juminzei/designated-number (:notice/designated-number governing)
             :juminzei/remittance
             (assoc remittance-rule
                    :rule/for-this-month
                    (str period " に徴収した分の納期限は翌月10日"))))))

;; ---------------------------------------------------------------------------
;; Coverage — what the operations screen reads
;; ---------------------------------------------------------------------------

(defn coverage
  "How many of the twelve collection months of `tax-year` an effective notice
  supplies a 月割額 for.

    {:tax-year \"2026\" :notices <every registered notice for this employer>}

  =>

    {:coverage/tax-year :coverage/months-covered :coverage/months-required
     :coverage/complete? :coverage/uncovered-months :coverage/why}

  **No amount appears in the result, and that is a constraint and not an
  omission.** This is what the operations screen reads, and that screen is a
  deployment-health surface: it is rendered for whoever is running the actor,
  it is quoted into `payroll.operations`' report, and that report is asserted
  to carry nothing that must not be logged. A month's 月割額 is one employee's
  tax; a count of months is not. So this answers 「その年度は埋まっているか」
  and nothing else, and a caller that needs a figure calls `assess`.

  Superseded notices do not count toward coverage — a month covered only by a
  notice a later one replaced is NOT covered, because the replaced paper is no
  longer in force and this actor will not carry a figure forward off it.
  `assess` reads through `effective-notices` and `governing-notice` too, so
  `:coverage/complete?` and twelve `:notified` answers cannot disagree.

  That has a consequence worth stating: a 変更通知書 that REPLACES a full-year
  決定通知書 while carrying only 10月 onward leaves 6月–9月 uncovered, and this
  reports them. It is the honest answer — nothing registered says what those
  months are any more — and the fix is a registration and not a default. A
  mid-year change that is not meant to supersede is registered as its own
  paper (revision 0, `:notice/replaces` nil), and then both notices are in
  force and `governing-notice` picks between them by month."
  [{:keys [tax-year notices]}]
  (let [ty (str tax-year)
        live (filterv #(= ty (:notice/tax-year %)) (effective-notices notices))
        covered? (fn [mk]
                   (whole-yen? (get-in (governing-notice live mk)
                                       [:notice/months mk])))
        uncovered (vec (remove covered? month-keys))
        n (- (count month-keys) (count uncovered))]
    {:coverage/tax-year ty
     :coverage/months-covered n
     :coverage/months-required (count month-keys)
     :coverage/complete? (zero? (count uncovered))
     :coverage/uncovered-months uncovered
     :coverage/why
     (if (empty? uncovered)
       (str ty " 年度の12か月すべてについて、"
            "有効な通知が月割額を与えている。"
            "額そのものはここには出さない —— "
            "この画面は運用状態を述べるものであって、"
            "給与の額を運ぶものではない")
       (str ty " 年度で通知のある月は " n " / " (count month-keys) " か月。"
            "通知の無い月: " (str/join "、" (map name uncovered))
            "。未登録は零ではないので、"
            "これらの月の run は住民税の行が「未確定」のままになる"))}))

;; ---------------------------------------------------------------------------
;; The two summaries an employer actually needs
;; ---------------------------------------------------------------------------

(defn employee-summary
  "One employee's whole tax year, as registered.

  `:summary/complete?` is the evidence floor: a year with eleven notified
  months and one refusal is NOT a year, and the total over it is not the
  年税額. A caller handed only a number could not tell."
  [{:keys [contract-id notices obligation tax-year]}]
  (let [months (for [{:month/keys [ordinal label]} collection-months
                     :let [k (nth month-keys (dec ordinal))
                           ;; the period is reconstructed from the tax year
                           ;; and the month's own :of-year, which is what
                           ;; makes 翌年1月 land on the right calendar year
                           mm (subs (name k) 1 3)
                           y #?(:clj (parse-long (str tax-year))
                                :cljs (js/parseInt (str tax-year) 10))
                           of (:month/of-year (nth collection-months (dec ordinal)))
                           period (str (if (= :next of) (inc y) y) "-" mm)
                           a (assess {:period period :notices notices
                                      :obligation obligation})]]
                 {:summary/month k
                  :summary/label label
                  :summary/period period
                  :summary/answer (:juminzei/answer a)
                  :summary/amount (:juminzei/amount a)
                  :summary/why (:juminzei/why a)})
        months (vec months)
        notified (filterv #(= :notified (:summary/answer %)) months)]
    {:summary/contract-id contract-id
     :summary/tax-year (str tax-year)
     :summary/months months
     :summary/notified (count notified)
     :summary/complete? (= 12 (count notified))
     :summary/annual-total (when (= 12 (count notified))
                             (reduce + 0 (map :summary/amount notified)))
     :summary/why (if (= 12 (count notified))
                    (str "12か月すべてが通知に基づいて登録されている。"
                         "年税額はその合計であって、この actor の計算ではない")
                    (str "12か月のうち " (count notified)
                         " か月しか通知に基づいていない。"
                         "残りは未登録であって零ではないので、"
                         "年税額は出さない"))}))

(defn municipality-payable
  "What must be remitted, grouped by 区市町村, for one period.

  「区市町村ごとにとりまとめ、区市町村から送付される納入書で納入します」——
  the grouping key is the municipality and not the employer, because two
  employees living in two municipalities produce two 納入書.

  `:payable/complete?` is false when any employee's month refused. A remittance
  built from a partial total is short by exactly the employees it could not
  read, and the municipality's own 納入書 carries their total."
  [{:keys [period employees]}]
  (let [assessed (for [e employees]
                   (assoc e :assessment (assess (assoc e :period period))))
        by-muni (group-by #(get-in % [:assessment :juminzei/municipality])
                          (filter #(= :notified (get-in % [:assessment :juminzei/answer]))
                                  assessed))
        refused (vec (for [a assessed
                           :when (contains? refusals
                                            (get-in a [:assessment :juminzei/answer]))]
                       {:payable/contract-id (:contract-id a)
                        :payable/answer (get-in a [:assessment :juminzei/answer])
                        :payable/why (get-in a [:assessment :juminzei/why])}))]
    {:payable/period period
     :payable/groups
     (vec (for [[muni es] (sort-by (comp str key) by-muni)]
            {:payable/municipality muni
             :payable/employees (count es)
             :payable/amount (reduce + 0 (map #(get-in % [:assessment :juminzei/amount]) es))
             :payable/deadline (:rule/deadline remittance-rule)
             :payable/holiday-rule (:rule/holiday remittance-rule)}))
     :payable/refused refused
     :payable/complete? (empty? refused)
     :payable/why (if (empty? refused)
                    (str (count by-muni) " 区市町村分。"
                         "納入書は区市町村から送られてくるものであり、"
                         "この actor は納付書の様式を読んでいない")
                    (str refused
                         " 件の被用者について月割額が確定していない。"
                         "確定していない分を除いた合計は、納入すべき額ではない"))}))

(defn deduction-figure
  "This month's 住民税 as a `payroll.provenance` figure.

  `:declared` and never `:derived`: the amount is the municipality's, this
  actor did not compute it, and `:derived` is reserved for a figure computed
  here from a rule read here. `provenance-fns` is passed in for
  `payroll.chingin/gross-figure`'s reason — this namespace stays free of a
  dependency it would use for three calls.

  A `:no-obligation-registered` answer is `:not-applicable` and NOT zero: an
  employee under 普通徴収 has no 特別徴収 line, and printing 0 asserts a
  deduction was computed and came to nothing."
  [assessment {:keys [declared not-applicable held]}]
  (case (:juminzei/answer assessment)
    :notified (declared "住民税（特別徴収）" (:juminzei/amount assessment)
                        (str (:juminzei/municipality assessment)
                             " 特別徴収税額通知")
                        (:juminzei/why assessment))
    (:no-obligation-registered :not-a-collection-month)
    (not-applicable "住民税（特別徴収）" (:juminzei/why assessment) nil)
    (held "住民税（特別徴収）" (:juminzei/why assessment)
          "地方税法 第321条の5（手引きが引く条文。条文自体は未取得）")))

;; ---------------------------------------------------------------------------
;; Registering a notice — admission, then persistence
;; ---------------------------------------------------------------------------

(def employer-naming-keys
  "Keys by which a registration body could try to name whose payroll this is.
  Refused outright — the employer comes from the verified caller.

  The same set `payroll.touroku/employer-naming-keys` carries, with
  `:notice/employer` in place of `:contract/employer` because that is the key
  an admitted notice is stamped with. Kept as its own name rather than
  required across, so the two surfaces can diverge if one ever needs to and
  the divergence is visible."
  #{:client-id :employer :employer-id :notice/employer})

(defn admit-registration
  "One transcribed notice plus everything this employer has already
  registered, and exactly one of three answers.

    (admit-registration employer-id m existing)

  `existing` is every notice already registered for this employer, oldest
  first — the whole stream, superseded entries included, because both
  `:replacement-not-registered` and `:replacement-already-replaced` are
  questions about the history and not about its current tip.

  Returns one of:

    {:registration/status :ok        :registration/record …}
    {:registration/status :duplicate :registration/notice-id … :registration/why …}
    {:registration/status :refused   :registration/reason … :registration/why …}

  ## `:duplicate` is not `:refused`, and not `:ok` either

  A retried submission — the same `:notice/id` already registered, with
  identical content — is ONE registration. It is not refused, because the
  operator did nothing wrong and telling them so would make them transcribe
  the paper again; and it is not `:ok`, because answering `:ok` would append a
  second copy of a notice to a stream whose whole purpose is to say which
  paper is current. `payroll.store.kotobase` deduplicates by content on the
  keyed streams anyway, so this is the answer arriving at the RIGHT layer
  rather than being discovered at the storage one.

  ## Every `:registration/reason` THIS function answers with

  There is one more, `:history-unreadable`, and it belongs to
  `register-notice!` rather than here: it is about the store and not about the
  paper, and this function is pure.

  | reason | what happened |
  |---|---|
  | `:employer-named` | the body carried one of `employer-naming-keys` |
  | `:notice-refused` | `admit-notice` refused; its `:notice/why` is carried through verbatim |
  | `:revision-without-replacement` | 改訂番号 is 1 or more and nothing is named as replaced |
  | `:replacement-not-registered` | it names an id this employer has not registered |
  | `:replacement-already-replaced` | what it claims to replace was already replaced by ANOTHER notice |
  | `:conflicting-content` | the same `:notice/id` is registered with different content |

  These keyword literals are pinned by `payroll.phase2-test`, so renaming one
  is a test failure rather than a silent change in what an operator is told.

  The record that reaches the store is `admit-notice`'s record plus
  `:notice/id`. `:notice/employer` is stamped by `admit-notice` from the
  verified caller and is never read out of the body."
  [employer-id m existing]
  (let [named (vec (sort (filter #(contains? m %) employer-naming-keys)))
        admitted (when (empty? named) (admit-notice employer-id m))
        record (when (= :ok (:notice/status admitted))
                 (let [r (:notice/record admitted)]
                   (assoc r :notice/id (notice-id r))))
        id (:notice/id record)
        revision (:notice/revision record)
        replaces (:notice/replaces record)
        by-id (into {} (map (juxt :notice/id identity)) existing)
        same (get by-id id)
        ;; `not= id` because a RETRY of a correction names the same notice its
        ;; first submission named, and that is not a fork — it is the same
        ;; correction arriving twice. Without this exclusion every redelivered
        ;; 変更通知書 would be refused for having already replaced what it
        ;; replaces, which is itself.
        already (first (filterv #(and (some? replaces)
                                      (= replaces (:notice/replaces %))
                                      (not= id (:notice/id %)))
                                existing))]
    (cond
      (seq named)
      {:registration/status :refused
       :registration/reason :employer-named
       :registration/why (str "登録の所有者は検証済みの呼び出し元から取る。"
                              "本文で employer を名乗ることはできない: "
                              (pr-str named))}

      (= :refused (:notice/status admitted))
      {:registration/status :refused
       :registration/reason :notice-refused
       :registration/why (:notice/why admitted)}

      (and (pos? revision) (str/blank? (str replaces)))
      {:registration/status :refused
       :registration/reason :revision-without-replacement
       :registration/why (str "改訂番号が " revision " なのに、"
                              "差し替える通知が名指しされていない。"
                              "何を訂正したのか分からない訂正は、"
                              "同じ年度について二つの通知を並べるだけで、"
                              "どちらの月割額を控除すべきか誰にも答えられない")}

      (and (pos? revision) (not (contains? by-id replaces)))
      {:registration/status :refused
       :registration/reason :replacement-not-registered
       :registration/why (str "差し替える通知として " (pr-str replaces)
                              " が名指しされているが、"
                              "この事業主はその通知を登録していない。"
                              "登録されていない紙を訂正することはできない —— "
                              "先に元の通知を登録する")}

      (and (pos? revision) already)
      {:registration/status :refused
       :registration/reason :replacement-already-replaced
       :registration/why (str (pr-str replaces) " は既に "
                              (pr-str (:notice/id already))
                              " によって差し替えられている。"
                              "同じ通知を二つの通知が差し替えると"
                              "訂正の履歴が黙って分岐し、"
                              "どちらが現行かを誰も答えられなくなる。"
                              "訂正の訂正は、直前の通知を名指しする")}

      (and same (= same record))
      {:registration/status :duplicate
       :registration/notice-id id
       :registration/why (str "同じ通知が同じ内容で既に登録されている（"
                              id "）。"
                              "再送は二度目の登録ではない")}

      same
      {:registration/status :refused
       :registration/reason :conflicting-content
       :registration/why (str "同じ通知が別の内容で登録されている。"
                              "訂正は改訂番号を上げ、"
                              "差し替える通知を名指しする")}

      :else
      {:registration/status :ok :registration/record record})))

(defn register-notice!
  "Admit against what this employer has already registered, and persist ONLY
  on `:ok`.

    (register-notice! store {:employer employer-id :notice m})

  Returns `admit-registration`'s answer unchanged. Nothing reaches the store
  on `:refused` or on `:duplicate` — `payroll.cutover/record-cycle!` keeps the
  same rule at the other write boundary, and it is the rule that makes a
  refusal legible: a store that had been written to before the refusal was
  decided would hold a record no answer accounts for.

  ## An unreadable history refuses the registration

  `payroll.store.kotobase` fails closed on a chain it cannot walk to the end,
  so the read below can THROW. That throw is caught and turned into a refusal
  rather than propagated, and it is emphatically not turned into an empty
  history: a history that cannot be read to its end cannot answer 「これは既に
  登録されているか」, and appending against a partial read is registering
  without checking idempotency. On this stream that produces two notices with
  one id — the fork `notice-id` and `:notice/replaces` exist to prevent.

  Nothing is written in that case."
  [store* {:keys [employer notice]}]
  (let [read* (try {:read/ok? true
                    :read/notices (vec (store/juminzei-notices store* employer))}
                   (catch #?(:clj Exception :cljs :default) e
                     {:read/ok? false
                      :read/why #?(:clj (.getMessage ^Exception e)
                                   :cljs (.-message e))}))]
    (if-not (:read/ok? read*)
      {:registration/status :refused
       :registration/reason :history-unreadable
       :registration/why (str "この事業主の通知の履歴を末尾まで読めないので"
                              "登録しない: " (:read/why read*)
                              "。末尾まで読めない履歴は"
                              "「これは既に登録されているか」に答えられず、"
                              "部分的な読みに対する追記は、"
                              "冪等性を確かめずに書くことである。"
                              "読めない履歴は空の履歴ではない")}
      (let [r (admit-registration employer notice (:read/notices read*))]
        (when (= :ok (:registration/status r))
          (store/register-juminzei-notice! store* (:registration/record r)))
        r))))
