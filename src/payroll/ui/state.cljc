(ns payroll.ui.state
  "How a state looks to an operator — in words first, and only then in colour.

  Every state this console can show is rendered as a chip carrying THREE
  things:

    a mark      a text glyph, so the state survives a black-and-white print
    a word      Japanese, so the state survives not seeing colour at all
    an aria-label  the word plus its reason, so the state survives not
                   seeing the screen

  Colour is the fourth thing and is the only one that can be removed without
  the state disappearing. `payroll.ui.a11y/check`'s `:state-colour-only` rule
  is what holds this in place; the chip functions here are what make
  satisfying it the easy path rather than a thing to remember.

  ## The marks are text, not icons

  `●` `▲` `■` `—` `?` are characters. An `<img>` or an inline `<svg>` would
  need an `alt` that repeats the word, would not survive a copy-paste into a
  message, and would be one more thing to get wrong. They are also
  deliberately different SHAPES rather than the same shape in different
  colours: a reader with a colour-vision deficiency distinguishes shapes.

  ## Why `未確定` and `保留` are different chips

  `payroll.provenance` separates them and this repository would gain nothing
  by re-merging them at the last moment. `未確定` means nobody supplied the
  figure; `保留` means the governor refused the run over it. The operator's
  next action is different: find the number, versus read the violation."
  (:require [clojure.string :as str]
            [payroll.provenance :as prov]))

(def chips
  "The state vocabulary. `:tone` is the ONLY field a stylesheet reads, and
  nothing here depends on it being rendered."
  {:derived
   {:chip/mark "●" :chip/word "確定" :chip/tone :ok
    :chip/means "当リポジトリが登録事実と読んだ条文から計算した値"}
   :declared
   {:chip/mark "◆" :chip/word "申告値" :chip/tone :caution
    :chip/means "operator または proposal が申告した値。当リポジトリは検算していない"}
   :imported
   {:chip/mark "◇" :chip/word "取込値" :chip/tone :caution
    :chip/means "他システムのエクスポートから読んだ値。列名も金額も未検証"}
   :not-applicable
   {:chip/mark "—" :chip/word "該当なし" :chip/tone :muted
    :chip/means "登録された事実により、この項目は発生しない。零ではない"}
   :unknown
   {:chip/mark "?" :chip/word "未確定" :chip/tone :warn
    :chip/means "誰も出していない値。零でも空欄でもない"}
   :held
   {:chip/mark "▲" :chip/word "保留" :chip/tone :stop
    :chip/means "この項目を理由に governor が run を保留している"}})

(def disposition-chips
  "The three outcomes of a run. A boolean has two and a run has three — the
  reason `payroll.edge.endpoints` reports `:disposition` and not `:ok`."
  {:commit {:chip/mark "●" :chip/word "承認" :chip/tone :ok
            :chip/means "governor を通り、台帳に記録された"}
   :request-approval {:chip/mark "◆" :chip/word "署名待ち" :chip/tone :caution
                      :chip/means "人の承認を待っている。まだ支払われていない"}
   :hold {:chip/mark "▲" :chip/word "保留" :chip/tone :stop
          :chip/means "HARD 違反により保留。支払ってはならない"}})

(def verdict-chips
  "Reconciliation verdicts. `:not-comparable` is `:warn` and not `:muted`
  deliberately: it is the state a parallel run lands in when this actor held
  the figure, and a muted chip would read as `nothing to see`."
  {:agree {:chip/mark "●" :chip/word "一致" :chip/tone :ok}
   :differ {:chip/mark "▲" :chip/word "不一致" :chip/tone :stop}
   :only-in-mf {:chip/mark "◀" :chip/word "MF のみ" :chip/tone :warn}
   :only-here {:chip/mark "▶" :chip/word "当 actor のみ" :chip/tone :warn}
   :not-comparable {:chip/mark "?" :chip/word "比較不能" :chip/tone :warn}})

(def unknown-chip
  "What to show for a state this namespace does not know.

  Not a blank and not a guess. A state nobody classified is exactly as
  actionable as an unknown figure, and rendering it as nothing would make a
  bug in this console indistinguishable from a clean run."
  {:chip/mark "?" :chip/word "不明な状態" :chip/tone :warn
   :chip/means "この console が分類していない状態。表示できていないだけで、"})

(defn- chip*
  [{:chip/keys [mark word tone means]} extra-class why]
  [:span {:class (str "chip chip-" (name (or tone :muted))
                      (when extra-class (str " " extra-class)))
          :aria-label (str word "。" (or why means))}
   [:span {:class "chip-mark" :aria-hidden "true"} mark]
   [:span {:class "chip-word"} word]])

(defn figure-chip
  "The provenance of a figure, as a chip. `why` is the figure's own reason,
  so the accessible name says what is wrong with THIS figure and not what the
  provenance means in general."
  [f]
  (let [p (:figure/provenance f)]
    (chip* (get chips p unknown-chip)
           (str "prov-" (name (or p :unknown)))
           (:figure/why f))))

(defn disposition-chip [d]
  (chip* (get disposition-chips d unknown-chip)
         (str "disposition-" (name (or d :unknown)))
         nil))

(defn verdict-chip [v & [why]]
  (chip* (get verdict-chips v unknown-chip)
         (str "verdict-" (name (or v :unknown)))
         why))

(defn yes-no-chip
  "A registered boolean, three-valued.

  `nil` is NOT `いいえ`. Across this repository an unobserved coverage flag is
  its own answer and holds the run; a console that painted it as `no` would
  be the place that quietly turned an unanswered question into an answer."
  [v label]
  (cond
    (true? v) (chip* {:chip/mark "●" :chip/word "はい" :chip/tone :ok
                      :chip/means (str label "は「はい」で登録されている")}
                     "state-yes" nil)
    (false? v) (chip* {:chip/mark "○" :chip/word "いいえ" :chip/tone :muted
                       :chip/means (str label "は「いいえ」で登録されている")}
                      "state-no" nil)
    :else (chip* {:chip/mark "?" :chip/word "未登録" :chip/tone :warn
                  :chip/means (str label "が登録されていない。"
                                   "未登録は「いいえ」ではない")}
                 "state-unregistered" nil)))

(def answer-chips
  "The one-word answers a section of `payroll.operations/report` carries.

  A separate vocabulary from `chips` and not an extension of it, because
  these are answers about a DEPLOYMENT and those are provenances of a FIGURE.
  Folding them together would let `確定` — which means this repository
  computed a number — be rendered next to a store, where it would mean
  nothing anybody could act on.

  Every entry that is not an affirmative answer is `:warn` or `:stop` and
  never `:muted`. `未設定` and `報告なし` are the two states an operator is
  most likely to read as `fine`, and this repository's whole position is that
  a question nobody asked is not a question that was answered."
  {:registered {:chip/mark "●" :chip/word "登録済み" :chip/tone :ok
                :chip/means "この事実は登録されている"}
   :not-registered {:chip/mark "?" :chip/word "未登録" :chip/tone :warn
                    :chip/means "登録されていない。未登録は零でも「無い」でもない"}
   :readable {:chip/mark "●" :chip/word "読める" :chip/tone :ok
              :chip/means "保存先を最後まで辿れた。空であることとは別である"}
   :unreadable {:chip/mark "▲" :chip/word "読めない" :chip/tone :stop
                :chip/means "辿れない chain がある。件数は読めた分の下限である"}
   :not-reported {:chip/mark "?" :chip/word "報告なし" :chip/tone :warn
                  :chip/means "この store は自身の健全性を答えない。答えないことは「健全である」ではない"}
   :built {:chip/mark "●" :chip/word "構築済み" :chip/tone :ok
           :chip/means "表が存在し、読める。投影が正しいことの証拠ではない"}
   :not-built {:chip/mark "?" :chip/word "未構築" :chip/tone :warn
               :chip/means "catalog には届くが、表がまだ作られていない。故障ではなく未構築である"}
   :unreachable {:chip/mark "▲" :chip/word "到達不能" :chip/tone :stop
                 :chip/means "catalog に届かない"}
   :not-configured {:chip/mark "?" :chip/word "未設定" :chip/tone :warn
                    :chip/means "設定されていない。未設定は「投影が正しい」ではない"}})

(defn answer-chip
  "A section's one-word answer. An answer this namespace has not classified
  gets `unknown-chip` and never a blank — a state nobody classified is
  exactly as actionable as an unknown figure."
  [a]
  (chip* (get answer-chips a unknown-chip)
         (str "state-" (name (or a :unknown)))
         nil))

(defn met-chip
  "Whether one cutover condition is met.

  `未了` and not `いいえ`: the question is not a yes/no about a fact somebody
  registered, it is whether a condition on switching off the incumbent
  payroll system holds — and the operator's next action is to work it down."
  [met? label]
  (if met?
    (chip* {:chip/mark "●" :chip/word "満たしている" :chip/tone :ok
            :chip/means (str label "は満たされている")}
           "state-met" nil)
    (chip* {:chip/mark "▲" :chip/word "未了" :chip/tone :stop
            :chip/means (str label "はまだ満たされていない")}
           "state-unmet" nil)))

(defn amount-text
  "A figure's amount as text for a cell, or the word for its state.

  Returns a STRING and never hiccup, so a caller can put it in a table cell,
  an `aria-label` or an export without three code paths. The chip goes next
  to it; this is the value."
  [f]
  (if (prov/numeric? f)
    (str (:figure/amount f))
    (:chip/word (get chips (:figure/provenance f) unknown-chip))))

(def legend
  "Every chip, once, for the console's legend. Generated from `chips` rather
  than written out, so a state added there appears in the legend without a
  second edit — the failure `payroll.ui.views` would otherwise have is a
  state that renders on a screen and is explained nowhere."
  (vec (for [p prov/provenances
             :let [c (get chips p)]
             :when c]
         (assoc c :chip/provenance p))))

(defn legend-hiccup []
  [:dl {:class "legend"}
   (for [{:chip/keys [provenance mark word means]} legend]
     [:div {:class "legend-item"}
      [:dt [:span {:class (str "chip chip-" (name (:chip/tone (get chips provenance)))
                               " prov-" (name provenance))
                   :aria-label word}
            [:span {:class "chip-mark" :aria-hidden "true"} mark]
            [:span {:class "chip-word"} word]]]
      [:dd means]])])

(defn tone-of
  "The tone a whole row or panel should take, from the figures in it: the
  worst one wins.

  Worst-wins rather than most-common, because a panel of eleven confirmed
  figures and one held one is a panel somebody must not act on, and an
  average would render it green."
  [figures]
  (let [ps (set (map :figure/provenance figures))]
    (cond
      (contains? ps :held) :stop
      (contains? ps :unknown) :warn
      (or (contains? ps :imported) (contains? ps :declared)) :caution
      (seq ps) :ok
      ;; No figures at all. Not `:ok` — an empty panel has not been checked.
      :else :warn)))

(defn summarise
  "One sentence for a set of figures, coverage first.

  `payroll.ui.a11y/report` and `kotoba.worklaw/describe` both put coverage
  first for the same reason: a summary that led with `問題なし` over an empty
  set would read as an all-clear it did not earn."
  [figures]
  (let [by (frequencies (map :figure/provenance figures))]
    (if (empty? figures)
      "項目が無い（これは「問題なし」ではない）"
      (str "項目 " (count figures) " 件: "
           (str/join "、"
                     (for [p prov/provenances
                           :let [n (get by p 0)]
                           :when (pos? n)]
                       (str (:chip/word (get chips p)) " " n)))))))
