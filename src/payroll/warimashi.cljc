(ns payroll.warimashi
  "割増賃金 — 労働基準法 第三十七条, priced from rates that were read.

  `payroll.chingin` names 第三十七条 as UNREAD and holds every run with
  registered overtime on it. This namespace is the other half: the rates were
  read, from two sources published by the ministry that administers the Act.

    厚生労働省「割増賃金について（労働基準法第37条より）」
    https://www.mhlw.go.jp/bunya/roudoukijun/faq_kijyunhou_25.html

    厚生労働省 和歌山労働局「割増賃金率」
    https://jsite.mhlw.go.jp/wakayama-roudoukyoku/newpage_00470.html

  Both were retrieved 2026-08-26. `payroll.chingin`'s hold is NOT removed by
  this namespace existing — it is removed for a run whose overtime this
  namespace could actually price, which needs three more registered facts
  (below) and is a different question from whether a rate was read.

  ## Categories are given, never inferred

  `price` takes CATEGORISED hours and refuses a total. There is no branch
  that looks at `:ts/hours`, subtracts 40, and calls the remainder overtime.

  It cannot be done from here and doing it approximately would be worse than
  refusing: which hours are 法定時間外 depends on 第三十二条's daily and weekly
  limits, on the 変形労働時間制 the workplace uses and on which day the
  employer designated as the 法定休日 — none of which is a payroll fact, and
  the middle one is a 就業規則 this repository does not hold. `kotoba-lang/
  worklaw` holds the 32/34/35/36 thresholds and states that it does not price
  overtime; this prices overtime and does not categorise it. Between them the
  categorisation is nobody's, and it is the employer's, which is correct.

  ## The rates, with what each one's source actually says

  | category | rate | quoted? |
  |---|---|---|
  | 時間外（法定外） | 1.25 | 和歌山局: 「25%以上」 |
  | 月60時間を超える時間外 | 1.50 | 和歌山局: 「5割以上の率」 |
  | 法定休日 | 1.35 | 和歌山局: 「3割5分以上の割増賃金」 |
  | 深夜 | +0.25 | 厚労省 FAQ、条文引用つき |
  | 時間外＋深夜 | 1.50 | 厚労省 FAQ: 「2割5分以上＋2割5分以上＝5割以上」 |
  | 法定休日＋深夜 | 1.60 | 厚労省 FAQ: 「3割5分以上＋2割5分以上＝6割以上」 |
  | 60時間超＋深夜 | 1.75 | **NOT quoted anywhere read** — derived |

  The last row is marked and travels marked. Both pages show the combination
  as ADDITION of the two rates and neither performs that addition for the
  60-hour case, so `:rate/derived-by :addition-of-two-read-rates` is on it
  and on nothing else. A reader who needs the quoted figure knows exactly
  which one to go and find.

  **These are the statutory MINIMA.** Every source says 以上. An employer
  whose 就業規則 sets a higher rate is paying lawfully and this actor would
  under-price them, so a registered `:employer/premium-rates` overrides, and
  a registered rate BELOW the minimum is refused.

  ## Two things the sources do not say, and which are therefore registered

  1. **端数処理.** Neither page states a rounding rule (measured: both were
     read for it). So there is no default. `rounding-policies` names the
     shapes a 就業規則 can specify and the employer registers which one; an
     unregistered policy is a refusal, not a floor.
  2. **月平均所定労働時間数** — the divisor that turns a monthly wage into an
     hourly one. It comes from the annual 所定労働日数 and the daily 所定労働
     時間, which are 就業規則 facts. Registered, never computed.

  ## What IS quoted about the base

  和歌山局 lists the excluded allowances: 家族手当、通勤手当、別居手当、
  子女教育手当、住宅手当、臨時支給賃金 — with the warning that exclusion is
  judged 「名称ではなく内容で」 and that a 一律定額支給 allowance may not
  qualify. So this namespace excludes them BY REGISTERED KEY and reports the
  warning next to the figure; it does not decide whether an allowance called
  住宅手当 is one."
  (:require [clojure.string :as str]))

(def sources
  {:mhlw/faq
   {:source/title "割増賃金について（労働基準法第37条より）"
    :source/authority "厚生労働省"
    :source/url "https://www.mhlw.go.jp/bunya/roudoukijun/faq_kijyunhou_25.html"
    :source/read-at "2026-08-26"
    :source/silent-on ["月60時間超の割増率" "代替休暇" "端数処理"]}
   :mhlw/wakayama
   {:source/title "割増賃金率"
    :source/authority "厚生労働省 和歌山労働局"
    :source/url "https://jsite.mhlw.go.jp/wakayama-roudoukyoku/newpage_00470.html"
    :source/read-at "2026-08-26"
    :source/silent-on ["端数処理" "適用日"]}})

(def categories
  "Every category this namespace prices, in the order a payslip lists them.

  A vector, for `payroll.shakai-hoken/schemes`' reason — this is read by
  people and a set would reorder differently on different runtimes."
  [{:category/key :overtime
    :category/label "時間外労働（法定外）"
    :category/hours-key :hours/statutory-overtime
    :category/rate 5/4
    :category/provision "労働基準法 第三十七条第一項"
    :category/quote "時間外労働（法定外）: 25%以上"
    :category/source :mhlw/wakayama}
   {:category/key :overtime-over-60
    :category/label "月60時間を超える時間外労働"
    :category/hours-key :hours/overtime-over-60
    :category/rate 3/2
    :category/provision "労働基準法 第三十七条第一項但書"
    :category/quote "月60時間を超える時間外労働の場合、通常の賃金の計算額の5割以上の率"
    :category/source :mhlw/wakayama}
   {:category/key :statutory-holiday
    :category/label "法定休日労働"
    :category/hours-key :hours/statutory-holiday
    :category/rate 27/20
    :category/provision "労働基準法 第三十七条第一項"
    :category/quote "法定休日に労働させた場合には3割5分以上の割増賃金"
    :category/source :mhlw/wakayama}
   {:category/key :late-night
    :category/label "深夜労働（時間外ではない深夜）"
    :category/hours-key :hours/late-night
    :category/rate 5/4
    :category/provision "労働基準法 第三十七条第四項"
    :category/quote (str "午後10時から午前５時までの間において労働させた場合に"
                         "おいては、その時間の労働については、通常の労働時間の"
                         "賃金の計算額の２割５分以上の率で計算した割増賃金を"
                         "支払わなければならない。")
    :category/source :mhlw/faq
    :category/note (str "所定労働時間内の深夜。割増は1.25であって、"
                        "深夜「加算分」0.25 だけを払う欄ではない —— "
                        "通常の賃金に0.25を加えた1.25が支払われる")}
   {:category/key :overtime-late-night
    :category/label "時間外＋深夜"
    :category/hours-key :hours/overtime-late-night
    :category/rate 3/2
    :category/provision "労働基準法 第三十七条第一項・第四項"
    :category/quote "法定時間外労働に対する２割５分以上　＋　深夜労働に対する２割５分以上＝５割以上"
    :category/source :mhlw/faq}
   {:category/key :holiday-late-night
    :category/label "法定休日＋深夜"
    :category/hours-key :hours/holiday-late-night
    :category/rate 8/5
    :category/provision "労働基準法 第三十七条第一項・第四項"
    :category/quote "法定休日労働に対する３割５分以上　＋　深夜労働に対する２割５分以上　＝６割以上"
    :category/source :mhlw/faq}
   {:category/key :over-60-late-night
    :category/label "月60時間超の時間外＋深夜"
    :category/hours-key :hours/over-60-late-night
    :category/rate 7/4
    :category/provision "労働基準法 第三十七条第一項但書・第四項"
    ;; The ONLY row with no quote, and it says so rather than borrowing one.
    :category/derived-by :addition-of-two-read-rates
    :category/derivation "1.50（60時間超）+ 0.25（深夜）"
    :category/why-not-quoted
    (str "読んだ二つの出典はどちらもこの組合せを明示していない"
         "（FAQ は 60時間超そのものに触れず、和歌山局は組合せを"
         "時間外＋深夜 と 休日＋深夜 の二つしか挙げていない）。"
         "他の二つの組合せがどちらも加算で示されているので同じ規則を"
         "当てはめたが、これは引用ではなく導出である")
    :category/source :mhlw/wakayama}])

(def category-by-key (into {} (map (juxt :category/key identity)) categories))
(def hours-keys (mapv :category/hours-key categories))

(def excluded-allowances
  "除外される手当, as 和歌山局 lists them.

  `:excluded/key` is what an operator registers; the exclusion is by KEY and
  never by inspecting a label, because the source's own warning is that the
  test is 内容 and not 名称."
  [{:excluded/key :allowance/family :excluded/label "家族手当"}
   {:excluded/key :allowance/commuting :excluded/label "通勤手当"}
   {:excluded/key :allowance/separation :excluded/label "別居手当"}
   {:excluded/key :allowance/child-education :excluded/label "子女教育手当"}
   {:excluded/key :allowance/housing :excluded/label "住宅手当"}
   {:excluded/key :allowance/irregular :excluded/label "臨時に支払われた賃金"}])

(def exclusion-warning
  (str "除外は「名称ではなく内容」で判断され、"
       "「一律定額支給」の手当は除外対象外となる場合がある。"
       "この actor は登録されたキーで除外するだけで、"
       "その手当が実際に除外対象かどうかは判断しない")

  )

(def rounding-policies
  "The rounding shapes a 就業規則 can specify. **There is no default.**

  Both sources were read for a 端数処理 rule and neither states one, so a
  default here would be this repository inventing a rule about somebody's
  wage and stamping it with an article of the Labour Standards Act."
  {:round/half-up
   {:policy/label "50銭以上切上げ・50銭未満切捨て"
    :policy/apply (fn [n] (let [f (- n (int n))]
                            (if (>= f 1/2) (inc (int n)) (int n))))}
   :round/floor
   {:policy/label "円未満切捨て"
    :policy/apply (fn [n] (int n))}
   :round/ceiling
   {:policy/label "円未満切上げ（労働者に有利な側）"
    :policy/apply (fn [n] (if (zero? (- n (int n))) (int n) (inc (int n))))}
   :round/none
   {:policy/label "丸めない（厳密な有理数のまま返す）"
    :policy/apply identity}})

(def answers #{:priced :no-premium-hours})

(def refusals
  "Every answer that is the absence of one. A set rather than the complement,
  for `payroll.shakai-hoken/refusals`' reason."
  #{:hourly-base-not-registered :monthly-hours-not-registered
    :rounding-policy-not-registered :uncategorised-hours
    :malformed-hours :rate-below-statutory-minimum})

;; ---------------------------------------------------------------------------
;; The hourly base
;; ---------------------------------------------------------------------------

(defn- positive-number? [x] (and (number? x) (pos? x)))

(defn hourly-base
  "1時間当たりの賃金 for one contract, or a refusal.

  Hourly contracts: `:contract/rate` IS the hourly wage.
  Monthly contracts: `(月額 − 除外手当) / 月平均所定労働時間数`, where the
  divisor is REGISTERED (`:employment/monthly-scheduled-hours`) and never
  derived — it comes from the annual 所定労働日数 and the daily 所定労働時間,
  which are 就業規則 facts this repository does not hold.

  Returned as an exact ratio, not a number: `payroll.shakai-hoken/
  pension-employee-share` gives the reason — ClojureScript has no distinct
  float type and a division here hands back something that prints like money
  and is not. Rounding happens once, at the end, under the registered policy."
  [contract]
  (let [rate (:contract/rate contract)
        wage-type (:contract/wage-type contract)
        excluded (vec (for [{:excluded/keys [key label]} excluded-allowances
                            :let [v (get contract key)]
                            :when (and (number? v) (pos? v))]
                        {:excluded/key key :excluded/label label :excluded/amount v}))
        excluded-total (reduce + 0 (map :excluded/amount excluded))
        hours (:employment/monthly-scheduled-hours contract)]
    (cond
      (not (positive-number? rate))
      {:base/status :refused :base/answer :hourly-base-not-registered
       :base/why "契約に賃金額（:contract/rate）が登録されていない"}

      (= :hourly wage-type)
      {:base/status :ok
       :base/hourly rate
       :base/how "時給契約なので :contract/rate がそのまま1時間当たりの賃金"
       :base/excluded excluded
       :base/warning exclusion-warning}

      (not= :monthly wage-type)
      {:base/status :refused :base/answer :hourly-base-not-registered
       :base/why (str "賃金形態が " (pr-str wage-type)
                      " で、1時間当たりの賃金を出す規則が無い")}

      (not (positive-number? hours))
      {:base/status :refused :base/answer :monthly-hours-not-registered
       :base/why (str "月平均所定労働時間数"
                      "（:employment/monthly-scheduled-hours）が登録されていない。"
                      "年間所定労働日数と1日の所定労働時間から出る就業規則の"
                      "数値であり、この actor は持っていないし推定もしない")}

      (>= excluded-total rate)
      {:base/status :refused :base/answer :hourly-base-not-registered
       :base/why (str "除外手当の合計 " excluded-total
                      " が月額 " rate " 以上ある。"
                      "算定基礎が零以下になる登録は受け付けない")}

      :else
      {:base/status :ok
       :base/hourly (/ (- rate excluded-total) hours)
       :base/how (str "（月額 " rate " − 除外手当 " excluded-total
                      "）÷ 月平均所定労働時間数 " hours)
       :base/monthly-scheduled-hours hours
       :base/excluded excluded
       :base/warning exclusion-warning})))

;; ---------------------------------------------------------------------------
;; Pricing
;; ---------------------------------------------------------------------------

(defn- effective-rate
  "The rate for one category: the employer's registered one if it is at or
  above the statutory minimum, otherwise a refusal.

  `以上` in every source is what makes the override legitimate; it is also
  what makes a LOWER registered rate not a variation but a shortfall, and
  this actor does not price one."
  [{:category/keys [key rate label]} employer-rates]
  (let [own (get employer-rates key)]
    (cond
      (nil? own) {:rate/value rate :rate/source :statutory-minimum}
      (not (number? own)) {:rate/refused
                           (str label "の登録割増率 " (pr-str own) " が数ではない")}
      (< own rate) {:rate/refused
                    (str label "の登録割増率 " own " は法定の最低 " (double rate)
                         " を下回る。条文はいずれも「以上」なので、"
                         "下回る率は就業規則の定めではなく不足である")}
      :else {:rate/value own :rate/source :employer-registered})))

(defn price
  "Price one month's categorised premium hours. Pure.

    {:contract        the REGISTERED contract
     :hours           {:hours/statutory-overtime n :hours/late-night n …}
                      — CATEGORISED. A `:hours/total` key is REFUSED.
     :rounding        one of `rounding-policies`' keys, registered by the
                      employer from its 就業規則
     :employer-rates  optional {category-key rate} overriding the minima}

  Returns

    {:warimashi/answer     :priced | :no-premium-hours | a refusal
     :warimashi/answerable? bool
     :warimashi/lines      [{:category :hours :rate :exact :yen :provision …}]
     :warimashi/total-yen  the rounded total, or nil
     :warimashi/base       `hourly-base`'s report
     :warimashi/rounding   which policy, and that it was registered}

  Every line carries its own provision and quote, so a payslip can print the
  article next to the amount — which is what makes this different from a
  multiplier somebody remembered."
  [{:keys [contract hours rounding employer-rates]}]
  (let [base (hourly-base contract)
        policy (get rounding-policies rounding)
        given (select-keys hours hours-keys)
        uncategorised (vec (remove (set hours-keys) (keys hours)))
        malformed (vec (for [[k v] given
                             :when (not (and (number? v) (not (neg? v))))]
                         k))
        engaged (into {} (for [[k v] given :when (and (number? v) (pos? v))] [k v]))
        rates (into {} (for [c categories]
                         [(:category/key c) (effective-rate c employer-rates)]))
        rate-refusals (vec (keep :rate/refused (vals rates)))]
    (cond
      (seq uncategorised)
      {:warimashi/answer :uncategorised-hours
       :warimashi/answerable? false
       :warimashi/why
       (str "この actor が知らない時間の区分がある: " (pr-str uncategorised)
            "。割増賃金の区分は労働時間の合計からは導けない —— "
            "どの時間が法定時間外か、どの日が法定休日かは"
            "第三十二条・第三十五条と就業規則で決まる事実であって、"
            "この actor が推定してよい事実ではない。"
            "受け付ける区分: " (pr-str hours-keys))}

      (seq malformed)
      {:warimashi/answer :malformed-hours
       :warimashi/answerable? false
       :warimashi/why (str "時間数が非負の数でない区分がある: " (pr-str malformed))}

      (= :refused (:base/status base))
      {:warimashi/answer (:base/answer base)
       :warimashi/answerable? false
       :warimashi/base base
       :warimashi/why (:base/why base)}

      (seq rate-refusals)
      {:warimashi/answer :rate-below-statutory-minimum
       :warimashi/answerable? false
       :warimashi/why (str/join "。" rate-refusals)}

      (empty? engaged)
      {:warimashi/answer :no-premium-hours
       :warimashi/answerable? true
       :warimashi/base base
       :warimashi/lines []
       :warimashi/total-yen 0
       :warimashi/why (str "割増の対象時間が一件も登録されていない。"
                           "これは「登録されていない」ではなく"
                           "「零と登録されている」——"
                           "区分ごとに 0 が入っている、または欄自体が無い")}

      (nil? policy)
      {:warimashi/answer :rounding-policy-not-registered
       :warimashi/answerable? false
       :warimashi/base base
       :warimashi/why
       (str "端数処理の規則が登録されていない（" (pr-str rounding) "）。"
            "読んだ二つの出典はどちらも端数処理を述べていないので、"
            "この actor に既定は無い —— "
            "既定を置けば、それは誰かの賃金についての規則を"
            "この repository が発明して労基法の条文で刻印することになる。"
            "登録できるのは " (pr-str (vec (sort (keys rounding-policies)))))}

      :else
      (let [hourly (:base/hourly base)
            lines (vec (for [c categories
                             :let [h (get engaged (:category/hours-key c))]
                             :when h
                             :let [r (:rate/value (get rates (:category/key c)))
                                   exact (* hourly r h)]]
                         (cond-> {:line/category (:category/key c)
                                  :line/label (:category/label c)
                                  :line/hours h
                                  :line/rate r
                                  :line/rate-source (:rate/source
                                                     (get rates (:category/key c)))
                                  :line/exact exact
                                  :line/yen ((:policy/apply policy) exact)
                                  :line/provision (:category/provision c)
                                  :line/quote (:category/quote c)
                                  :line/source (get sources (:category/source c))}
                           (:category/derived-by c)
                           (assoc :line/derived-by (:category/derived-by c)
                                  :line/derivation (:category/derivation c)
                                  :line/why-not-quoted (:category/why-not-quoted c)))))]
        {:warimashi/answer :priced
         :warimashi/answerable? true
         :warimashi/base base
         :warimashi/lines lines
         ;; Rounded PER LINE and then summed, which is a choice and is stated:
         ;; a payslip shows one figure per category, so the figures a person
         ;; can add up must be the figures that were added up. Rounding once
         ;; on the total would make the printed lines not sum to the printed
         ;; total, which is the shape of discrepancy an employee reports.
         :warimashi/total-yen (reduce + 0 (map :line/yen lines))
         :warimashi/rounding {:rounding/policy rounding
                              :rounding/label (:policy/label policy)
                              :rounding/registered? true
                              :rounding/applied :per-line
                              :rounding/why-per-line
                              (str "区分ごとに丸めてから合計する。"
                                   "明細に印字される各行の合計が、"
                                   "印字される合計と一致する必要があるため")
                              :rounding/not-in-sources
                              (str "端数処理は読んだ二つの出典のどちらにも"
                                   "記載が無い。就業規則の定めである")}
         :warimashi/why (str (count lines) " 区分・合計 "
                             (reduce + 0 (map :line/yen lines)) " 円。"
                             "率は法令の最低基準（いずれも「以上」）であり、"
                             "区分は登録された事実である")}))))

(defn premium-figure
  "The month's 割増賃金 as a `payroll.provenance` figure.

  `:derived` when priced — this IS a figure computed here from rates read
  here, which is exactly what `:derived` is reserved for. Every refusal is
  `:held`, because an unpriced overtime hour is not a smaller wage, it is an
  unanswered question about what somebody is owed."
  [priced {:keys [derived held]}]
  (if (:warimashi/answerable? priced)
    (derived "割増賃金" (:warimashi/total-yen priced)
             "労働基準法 第三十七条（厚生労働省の公表資料から読んだ率）"
             (:warimashi/why priced))
    (held "割増賃金" (:warimashi/why priced) "労働基準法 第三十七条")))
