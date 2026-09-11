(ns payroll.ui.a11y-test
  "Both directions for every rule.

  CLAUDE.md's sixth question — *has this check ever refused for the reason it
  names?* — is what this namespace answers. A rule that has only ever been
  green is a rule nobody can act on, and one that is green because it scanned
  nothing is worse than no rule.

  Each rule gets a tree that breaks it and a tree that does not, and the
  second assertion is the one that catches a checker made vacuously strict."
  (:require [clojure.test :refer [deftest is testing]]
            [payroll.ui.a11y :as a11y]))

(defn- rules-of [tree]
  (set (map :a11y/rule (:a11y/findings (a11y/check tree)))))

(defn- fires? [rule tree] (contains? (rules-of tree) rule))

;; ---------------------------------------------------------------------------
;; The evidence floor
;; ---------------------------------------------------------------------------

(deftest an-empty-scan-is-not-a-pass
  (testing "the defect CLAUDE.md names as the most common one on this fleet:
            a check that could not run returning the value of a check that
            ran and found nothing"
    (doseq [nothing [nil [] "" {} 42 [[]]]]
      (let [r (a11y/check nothing)]
        (is (empty? (:a11y/findings r)))
        (is (not (a11y/clean? r))
            (str (pr-str nothing) " was reported as clean"))
        (is (zero? (get-in r [:a11y/scanned :elements])))))))

(deftest the-report-says-so-in-words
  (let [r (a11y/check nil)]
    (is (re-find #"検査対象が無い" (a11y/report r)))))

(deftest a-tree-with-elements-and-no-findings-is-clean
  (let [r (a11y/check [:div [:h1 "見出し"] [:p "本文"]])]
    (is (a11y/clean? r))
    (is (= 3 (get-in r [:a11y/scanned :elements])))))

;; ---------------------------------------------------------------------------
;; The walker itself — a checker that cannot see is a checker that passes
;; ---------------------------------------------------------------------------

(deftest the-walker-descends-into-a-seq-of-nodes
  (testing "`for` returns a seq. A walker that treated it as an element would
            silently skip every generated list — and skipping is how a
            checker finds nothing"
    (is (fires? :th-no-scope
                [:table [:caption "c"]
                 [:tbody (for [i (range 3)] [:tr [:th (str i)]])]]))))

(deftest the-walker-descends-into-a-vector-of-nodes
  (testing "`html.core` treats a vector whose head is a vector as a sequence
            of nodes (core.cljc line 91), and this repository's views use
            that form"
    (is (fires? :th-no-scope
                [:table [:caption "c"] [:tbody [[:tr [:th "a"]] [:tr [:th "b"]]]]]))))

(deftest the-walker-does-not-dissolve-elements
  (testing "`flatten` would turn [:td \"x\"] into :td and \"x\" and the tree
            would stop existing"
    (is (= 4 (get-in (a11y/check [:div [:p [:span "a"] [:span "b"]]])
                     [:a11y/scanned :elements])))))

;; ---------------------------------------------------------------------------
;; Rule by rule
;; ---------------------------------------------------------------------------

(deftest label-missing
  (is (fires? :label-missing [:form [:input {:id "a" :name "a"}]]))
  (is (not (fires? :label-missing
                   [:form [:label {:for "a"} "名前"] [:input {:id "a" :name "a"}]])))
  (testing "aria-label is an acceptable name"
    (is (not (fires? :label-missing [:form [:input {:id "a" :aria-label "名前"}]]))))
  (testing "a hidden input needs none"
    (is (not (fires? :label-missing [:form [:input {:type "hidden" :name "t"}]]))))
  (testing "and select and textarea are covered too"
    (is (fires? :label-missing [:form [:select {:id "s"}]]))
    (is (fires? :label-missing [:form [:textarea {:id "t"}]]))))

(deftest duplicate-id
  (is (fires? :duplicate-id [:div [:p {:id "x"} "a"] [:p {:id "x"} "b"]]))
  (is (not (fires? :duplicate-id [:div [:p {:id "x"} "a"] [:p {:id "y"} "b"]]))))

(deftest label-for-nothing
  (is (fires? :label-for-nothing [:form [:label {:for "ghost"} "名前"]]))
  (is (not (fires? :label-for-nothing
                   [:form [:label {:for "a"} "名前"] [:input {:id "a"}]]))))

(deftest table-no-caption
  (is (fires? :table-no-caption [:table [:tbody [:tr [:td "a"]]]]))
  (is (not (fires? :table-no-caption
                   [:table [:caption "表題"] [:tbody [:tr [:td "a"]]]]))))

(deftest th-no-scope
  (is (fires? :th-no-scope [:table [:caption "c"] [:tr [:th "a"]]]))
  (is (not (fires? :th-no-scope
                   [:table [:caption "c"] [:tr [:th {:scope "col"} "a"]]]))))

(deftest heading-skip
  (is (fires? :heading-skip [:div [:h1 "a"] [:h3 "b"]]))
  (is (not (fires? :heading-skip [:div [:h1 "a"] [:h2 "b"] [:h3 "c"]])))
  (testing "going back UP is fine — a second section after a subsection"
    (is (not (fires? :heading-skip [:div [:h1 "a"] [:h2 "b"] [:h3 "c"] [:h2 "d"]])))))

(deftest multiple-h1
  (is (fires? :multiple-h1 [:div [:h1 "a"] [:h1 "b"]]))
  (is (not (fires? :multiple-h1 [:div [:h1 "a"] [:h2 "b"]]))))

(deftest positive-tabindex
  (is (fires? :positive-tabindex [:div [:a {:href "#" :tabindex "3"} "x"]]))
  (is (fires? :positive-tabindex [:div [:a {:href "#" :tabindex 3} "x"]]))
  (testing "0 and -1 are legitimate and are not reordering anything"
    (is (not (fires? :positive-tabindex [:div [:a {:href "#" :tabindex "0"} "x"]])))
    (is (not (fires? :positive-tabindex [:div [:p {:tabindex "-1"} "x"]])))))

(deftest img-no-alt
  (is (fires? :img-no-alt [:div [:img {:src "a.png"}]]))
  (is (not (fires? :img-no-alt [:div [:img {:src "a.png" :alt "図"}]])))
  (testing "an empty alt is a decision — a decorative image"
    (is (not (fires? :img-no-alt [:div [:img {:src "a.png" :alt ""}]]))))
  (is (not (fires? :img-no-alt [:div [:img {:src "a.png" :aria-hidden "true"}]]))))

(deftest state-colour-only
  (testing "the rule this repository cares most about: a state that reaches
            the operator only as a colour does not reach an operator who
            cannot see the colour, does not survive a screenshot, and does
            not survive being printed"
    (is (fires? :state-colour-only [:div [:span {:class "prov-held"}]]))
    (is (fires? :state-colour-only [:div [:span.prov-unknown]]))
    (is (fires? :state-colour-only [:div [:td {:class "amt disposition-hold"}]]))
    (is (fires? :state-colour-only [:div [:span {:class "verdict-differ"}]]))
    (testing "text is enough"
      (is (not (fires? :state-colour-only [:div [:span {:class "prov-held"} "保留"]]))))
    (testing "so is an aria-label"
      (is (not (fires? :state-colour-only
                       [:div [:span {:class "prov-held" :aria-label "保留"}]]))))
    (testing "and text anywhere underneath counts"
      (is (not (fires? :state-colour-only
                       [:div [:td {:class "prov-held"} [:span "未確定"]]]))))
    (testing "a class that is not a state class is not this rule's business"
      (is (not (fires? :state-colour-only [:div [:span {:class "amt"}]]))))))

(deftest a-title-attribute-is-not-an-accessible-name
  (testing "invisible to touch and to the keyboard; treating it as a name is
            how a control ends up nameless for exactly the people this rule
            protects"
    (is (fires? :state-colour-only
                [:div [:span {:class "prov-held" :title "保留"}]]))))

(deftest button-no-name
  (is (fires? :button-no-name [:div [:button {:type "submit"}]]))
  (is (not (fires? :button-no-name [:div [:button {:type "submit"} "送信"]])))
  (is (not (fires? :button-no-name [:div [:button {:aria-label "閉じる"}]]))))

(deftest link-no-name
  (is (fires? :link-no-name [:div [:a {:href "/x"}]]))
  (is (not (fires? :link-no-name [:div [:a {:href "/x"} "詳細"]])))
  (testing "an anchor with no href is not a link"
    (is (not (fires? :link-no-name [:div [:a {:name "top"}]])))))

;; ---------------------------------------------------------------------------
;; What it does not decide is recorded rather than implied
;; ---------------------------------------------------------------------------

(deftest the-checker-declares-what-it-cannot-decide
  (let [r (a11y/check [:div [:h1 "a"]])]
    (is (seq (:a11y/not-checked r)))
    (is (some #(re-find #"コントラスト" %) (:a11y/not-checked r)))))

(deftest the-report-leads-with-coverage
  (let [r (a11y/check [:table [:tr [:th "a"]]])]
    (is (re-find #"^要素 \d+" (a11y/report r)))
    (is (re-find #"指摘 2 件" (a11y/report r)))))
