(ns payroll.ui.a11y
  "Accessibility invariants, checked against the hiccup tree rather than the
  rendered markup.

  ## Why a tree and not a string

  Every one of these could be written as a regex over HTML, and every one of
  them would then be checking the renderer as much as the view. A missing
  `for` attribute and a renderer that dropped the attribute produce the same
  failing regex and different bugs. The tree is what the view actually
  produced.

  ## The evidence floor is the point

  `check` reports `:a11y/scanned` — elements, form controls, tables, headings
  — alongside its findings, and `clean?` is FALSE when nothing was scanned.

  This is the defect CLAUDE.md names as the most common one on this fleet: a
  check that could not run returning the value of a check that ran and found
  nothing. An accessibility checker handed an empty vector, a nil, or a tree
  it does not understand finds no violations, and `no violations` is exactly
  what a passing view looks like. Reporting the counts and refusing to call
  zero clean is what makes the green mean something.

  `payroll.ui.a11y-test` asserts BOTH directions for every rule: a tree that
  breaks it is reported, and a tree that does not is not. A rule that has
  never gone red is a rule nobody can act on.

  ## What is checked, and what is deliberately not

  Checked, because each is structural and decidable from the tree:

    :label-missing        a form control with no `<label for>` and no
                          `aria-label` / `aria-labelledby`
    :duplicate-id         two elements sharing an id — a `for` then points at
                          whichever the browser found first
    :label-for-nothing    a `<label for=x>` where no element has id `x`
    :table-no-caption     a `<table>` with no `<caption>`
    :th-no-scope          a `<th>` with no `scope`
    :heading-skip         h1 → h3, which makes a screen reader's outline lie
    :multiple-h1          more than one `<h1>` in one document
    :positive-tabindex    `tabindex` > 0, which reorders the whole page's
                          focus sequence around one element
    :img-no-alt           an `<img>` with neither `alt` nor `aria-hidden`
    :state-colour-only    an element carrying a state class with no text and
                          no `aria-label` — see below
    :button-no-name       a `<button>` with no text and no `aria-label`
    :link-no-name         an `<a href>` with no text and no `aria-label`

  NOT checked, because the tree cannot decide them and a check that guessed
  would be worse than the gap: colour contrast (a CSS question), focus
  visibility (a rendered-state question), reading order under CSS
  positioning, and whether a label's TEXT is meaningful. Those are named here
  so their absence is a recorded decision rather than an oversight.

  ## `:state-colour-only` is this repository's rule

  WCAG's use-of-colour criterion is a general one; here it is specific and it
  is the whole reason the console exists. `payroll.provenance` distinguishes
  a figure this repository computed from one somebody typed and one nobody
  has. If that distinction reaches the operator only as a colour, then it
  does not reach an operator who cannot see the colour, does not survive a
  screenshot pasted into a chat, and does not survive being printed. So every
  element carrying a `prov-*`, `state-*` or `disposition-*` class must also
  carry text or an `aria-label`, and this is the rule most likely to catch a
  future edit that adds a tidy coloured dot."
  (:require [clojure.string :as str]))

(def state-class-prefixes
  "Classes whose whole job is to say what state something is in. An element
  wearing one of these and saying nothing is a state conveyed by colour."
  ["prov-" "state-" "disposition-" "verdict-"])

(def labelled-controls
  "Elements that need an accessible name from a label."
  #{:input :select :textarea})

(def not-checked
  "What this checker does NOT decide, recorded so the absence is a decision.
  Emitted by `check` into its own result, so a report cannot be read as a
  full audit."
  ["色のコントラスト比（CSS の問題であり、hiccup 木からは決まらない）"
   "フォーカスの視認性（描画状態の問題）"
   "CSS による配置変更後の読み上げ順"
   "ラベルの文言が意味を成しているかどうか"])

;; ---------------------------------------------------------------------------
;; Walking hiccup
;; ---------------------------------------------------------------------------

(defn- element?
  "A hiccup ELEMENT, as `html.core` decides it: a vector whose head is a
  keyword. A vector whose head is another vector is a SEQUENCE of nodes there
  (html/core.cljc line 91) and must be walked into rather than treated as an
  element called `[…]` — a walker that got this wrong would silently skip
  every list built that way, and skipping is how a checker finds nothing."
  [x]
  (and (vector? x) (keyword? (first x)) (not= :hiccup/raw (first x))))

(defn- node-seq?
  "A node position holding several nodes: a seq (what `for` returns) or a
  vector whose head is a vector."
  [x]
  (or (seq? x)
      (and (vector? x) (seq x) (vector? (first x)))))

(defn- tag-of
  "The bare tag of `[:td.amt …]` — hiccup lets a class ride on the keyword."
  [el]
  (let [n (name (first el))
        i (or (str/index-of n ".") (str/index-of n "#"))]
    (keyword (if i (subs n 0 i) n))))

(defn- attrs-of [el]
  (let [a (second el)]
    (if (map? a) a {})))

(defn- classes-of
  "Every class on the element, from the attribute map and from the keyword's
  `.foo` suffixes. Both forms appear in this repository's views and a checker
  that read only one would pass a tree that used the other."
  [el]
  (let [from-attr (str/split (str (:class (attrs-of el))) #"\s+")
        n (name (first el))
        from-tag (rest (str/split (first (str/split n #"#")) #"\."))]
    (into #{} (remove str/blank?) (concat from-attr from-tag))))

(defn- expand
  "Flatten node-sequence positions without flattening elements. `flatten`
  cannot be used: a hiccup element IS a vector, so `flatten` would dissolve
  `[:td \"x\"]` into `:td` and `\"x\"` and the tree would stop existing."
  [x]
  (if (node-seq? x) (mapcat expand x) [x]))

(defn- children-of [el]
  (let [body (if (map? (second el)) (drop 2 el) (rest el))]
    (remove nil? (mapcat expand body))))

(defn- text-of
  "All the text under an element, concatenated. Used only to answer `does this
  say anything at all` — never to judge what it says."
  [x]
  (cond
    (string? x) x
    (number? x) (str x)
    (element? x) (str/join " " (keep text-of (children-of x)))
    (node-seq? x) (str/join " " (keep text-of x))
    :else nil))

(defn- walk
  "Every element in the tree, document order.

  Document order matters: `:heading-skip` reads the sequence of heading levels
  and would be answering a different question over a set."
  [tree]
  (letfn [(go [x]
            (cond
              (element? x) (cons x (mapcat go (children-of x)))
              (node-seq? x) (mapcat go x)
              :else nil))]
    (vec (go tree))))

(defn- named?
  "Does this element have an accessible name? Text content, `aria-label`, or
  `aria-labelledby`. `title` deliberately does not count: it is invisible to
  touch and to keyboard users, and treating it as a name is how a control
  ends up nameless for exactly the people this rule protects."
  [el]
  (let [a (attrs-of el)]
    (boolean (or (not (str/blank? (str (text-of el))))
                 (not (str/blank? (str (:aria-label a))))
                 (not (str/blank? (str (:aria-labelledby a))))))))

;; ---------------------------------------------------------------------------
;; The rules
;; ---------------------------------------------------------------------------

(defn- finding [rule el why]
  {:a11y/rule rule
   :a11y/tag (first el)
   :a11y/why why})

(defn check
  "Check a hiccup tree. Returns

    {:a11y/findings  [{:a11y/rule :a11y/tag :a11y/why} …]
     :a11y/scanned   {:elements n :controls n :tables n :headings n :labels n}
     :a11y/not-checked  what this checker does not decide}

  Never throws on a shape it does not understand: a string, a nil or a map
  simply contributes no elements, and the scan counts say so. That is the
  behaviour the evidence floor exists to make safe — the caller learns that
  nothing was scanned instead of learning that nothing was wrong."
  [tree]
  (let [els (walk tree)
        ids (keep #(:id (attrs-of %)) els)
        id-set (set ids)
        label-targets (into #{} (keep #(when (= :label (tag-of %))
                                         (:for (attrs-of %)))
                                      els))
        controls (filterv #(contains? labelled-controls (tag-of %)) els)
        tables (filterv #(= :table (tag-of %)) els)
        headings (filterv #(contains? #{:h1 :h2 :h3 :h4 :h5 :h6} (tag-of %)) els)
        labels (filterv #(= :label (tag-of %)) els)
        heading-levels (mapv #(-> % tag-of name (subs 1) #?(:clj parse-long
                                                            :cljs js/parseInt))
                             headings)
        findings
        (vec
         (concat
          ;; ---- form controls have a name -------------------------------
          (for [el controls
                :let [a (attrs-of el)]
                :when (and (not= "hidden" (:type a))
                           (not (contains? label-targets (:id a)))
                           (str/blank? (str (:aria-label a)))
                           (str/blank? (str (:aria-labelledby a))))]
            (finding :label-missing el
                     (str "フォーム部品に対応する <label for> も aria-label も無い"
                          "（id: " (pr-str (:id a)) "）")))

          ;; ---- ids are unique ------------------------------------------
          (for [[id n] (frequencies ids)
                :when (> n 1)]
            {:a11y/rule :duplicate-id
             :a11y/tag :*
             :a11y/why (str "id " (pr-str id) " が " n
                            " 箇所にある。label の for がどれを指すかは"
                            "ブラウザ任せになる")})

          ;; ---- a label points at something -----------------------------
          (for [el labels
                :let [f (:for (attrs-of el))]
                :when (and f (not (contains? id-set f)))]
            (finding :label-for-nothing el
                     (str "label for=" (pr-str f) " に対応する id が無い")))

          ;; ---- tables ---------------------------------------------------
          (for [el tables
                :when (not-any? #(= :caption (tag-of %)) (walk el))]
            (finding :table-no-caption el
                     "表に <caption> が無い。表の目的が読み上げられない"))

          (for [el els
                :when (and (= :th (tag-of el))
                           (str/blank? (str (:scope (attrs-of el)))))]
            (finding :th-no-scope el
                     "<th> に scope が無い。行見出しか列見出しか決まらない"))

          ;; ---- headings -------------------------------------------------
          (for [[prev cur] (map vector heading-levels (rest heading-levels))
                :when (> cur (inc prev))]
            {:a11y/rule :heading-skip
             :a11y/tag (keyword (str "h" cur))
             :a11y/why (str "見出しが h" prev " から h" cur
                            " に飛んでいる。読み上げの目次が実際の構造と"
                            "食い違う")})

          (when (> (count (filterv #(= 1 %) heading-levels)) 1)
            [{:a11y/rule :multiple-h1
              :a11y/tag :h1
              :a11y/why (str "h1 が " (count (filterv #(= 1 %) heading-levels))
                             " 個ある。文書の主題は一つである")}])

          ;; ---- focus order ----------------------------------------------
          (for [el els
                :let [t (:tabindex (attrs-of el))
                      n (cond (number? t) t
                              (string? t) #?(:clj (parse-long t)
                                             :cljs (js/parseInt t))
                              :else nil)]
                :when (and n (pos? n))]
            (finding :positive-tabindex el
                     (str "tabindex=" n
                          " はページ全体のフォーカス順を一要素のために組み替える")))

          ;; ---- images ---------------------------------------------------
          (for [el els
                :let [a (attrs-of el)]
                :when (and (= :img (tag-of el))
                           (nil? (:alt a))
                           (not= "true" (str (:aria-hidden a))))]
            (finding :img-no-alt el "<img> に alt も aria-hidden も無い"))

          ;; ---- state is never colour alone -------------------------------
          (for [el els
                :let [cs (classes-of el)]
                :when (and (some (fn [p] (some #(str/starts-with? % p) cs))
                                 state-class-prefixes)
                           (not (named? el)))]
            (finding :state-colour-only el
                     (str "状態を表すクラス "
                          (pr-str (vec (sort (filter (fn [c]
                                                       (some #(str/starts-with? c %)
                                                             state-class-prefixes))
                                                     cs))))
                          " を持つが、テキストも aria-label も無い。"
                          "色だけで伝わる状態は、色が見えない読み手には伝わらない")))

          ;; ---- interactive things have names ------------------------------
          (for [el els
                :when (and (= :button (tag-of el)) (not (named? el)))]
            (finding :button-no-name el "ボタンに読み上げ可能な名前が無い"))

          (for [el els
                :when (and (= :a (tag-of el))
                           (some? (:href (attrs-of el)))
                           (not (named? el)))]
            (finding :link-no-name el "リンクに読み上げ可能な名前が無い"))))]
    {:a11y/findings findings
     :a11y/scanned {:elements (count els)
                    :controls (count controls)
                    :tables (count tables)
                    :headings (count headings)
                    :labels (count labels)}
     :a11y/not-checked not-checked}))

(defn clean?
  "Did this tree pass, AND was there a tree to check?

  Deliberately not `(empty? findings)`. A view that rendered nothing has no
  findings, and calling that clean is the exact shape of failure this
  namespace's docstring is about. An `:elements` count of zero is not a pass;
  it is a scan that did not happen."
  [result]
  (and (empty? (:a11y/findings result))
       (pos? (get-in result [:a11y/scanned :elements] 0))))

(defn report
  "One line an operator or a test failure can read, coverage first so it can
  never be mistaken for an all-clear it did not earn."
  [{:a11y/keys [findings scanned]}]
  (str "要素 " (:elements scanned)
       "・部品 " (:controls scanned)
       "・表 " (:tables scanned)
       "・見出し " (:headings scanned)
       " を検査。"
       (if (seq findings)
         (str "指摘 " (count findings) " 件: "
              (str/join "、" (map #(str (name (:a11y/rule %))
                                        "(" (name (:a11y/tag %)) ")")
                                  findings)))
         (if (pos? (:elements scanned))
           "指摘なし"
           "検査対象が無い（これは合格ではない）"))))
