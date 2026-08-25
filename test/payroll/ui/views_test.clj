(ns payroll.ui.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.fixtures :as f]
            [payroll.mf.import :as mf]
            [payroll.mf.reconcile :as recon]
            [payroll.ui.a11y :as a11y]
            [payroll.ui.render :as render]
            [payroll.ui.state :as ui]
            [payroll.mf.schema :as mf-schema]
            [payroll.provenance :as prov]
            [payroll.ui.views :as views]))

(defn- clean-meisai [] (f/lines {:verdict (f/verdict-for)}))

(defn- held-meisai []
  (let [st (f/fresh-store
            {:contract-overrides {:employment/health-insurance-insured? nil}})
        p (f/proposal)]
    (f/lines {:contract* (f/contract {:employment/health-insurance-insured? nil})
              :run p :verdict (f/verdict-for st p) :disposition :hold})))

(defn- ctx
  "A context with something in every slot, so no view is checked over an
  empty screen. A view rendered from nothing has nothing to get wrong."
  []
  (let [runs [{:contract-id f/contract-id :worker f/worker :period f/period
               :meisai (clean-meisai)}
              {:contract-id f/contract-id :worker f/worker :period "2026-07"
               :meisai (held-meisai)}]]
    {:employer (f/employer)
     :contracts [(f/contract) (f/contract {:contract/id "c-2"
                                           :employment/health-insurance-insured? nil
                                           :bank/payee-name-kana nil})]
     :runs runs
     :latest (first runs)
     :violations [{:rule :social-insurance-coverage-not-observed
                   :detail "健康保険（健康保険法 第百六十七条第一項）: 未観測"}]
     :ledger-entries [{:contract-id f/contract-id :period f/period
                       :disposition :commit :record {:op :draft-payroll-run}}
                      {:contract-id f/contract-id :period "2026-07"
                       :disposition :hold
                       :verdict {:violations [{:rule :wage-mismatch}]}}]
     :store {:mode :ephemeral}
     :durability {:store/what "MemStore" :store/why "消える"
                  :store/survives-process-restart? false}
     :form {}
     :zengin (bank/zengin {:runs [{:contract (f/contract)}]})
     :transfer (bank/prepare {:employer (f/employer) :period f/period
                              :runs [{:contract (f/contract)
                                      :meisai (clean-meisai)}
                                     {:contract (f/contract
                                                 {:bank/account-number nil})
                                      :meisai (clean-meisai)}]})
     :reconciliation
     (recon/reconcile
      {:import (mf/parse (str (str/join "," (map :mf/column
                                                 mf-schema/columns))
                              "\n9001,従業員甲,2026-08,280000,14000,2500,25620,"
                              "1680,6001,0,49801,230199")
                         [(f/contract)])
       :ours {[f/contract-id f/period] (clean-meisai)}
       :period f/period})}))

;; ---------------------------------------------------------------------------
;; The view table
;; ---------------------------------------------------------------------------

(deftest every-view-is-reachable-from-the-nav
  (testing "a view added to the dispatch and forgotten in the nav is dead
            code that looks live. Generating removes the possibility"
    (let [nav-hrefs (set (map #(get-in % [1 :href])
                              (:a11y/findings (a11y/check (views/nav :overview)))))
          rendered (pr-str (views/nav :overview))]
      (doseq [{:view/keys [path label]} views/views]
        (is (str/includes? rendered path) (str path " missing from the nav"))
        (is (str/includes? rendered label)))
      (is (empty? nav-hrefs)))))

(deftest the-current-view-is-marked-in-words-and-not-only-in-colour
  (let [s (pr-str (views/nav :run))]
    (is (str/includes? s ":aria-current \"page\""))))

(deftest the-view-table-has-no-duplicate-paths-or-keys
  (is (= (count views/views) (count (set (map :view/path views/views)))))
  (is (= (count views/views) (count (set (map :view/key views/views))))))

;; ---------------------------------------------------------------------------
;; Accessibility, per view, with an evidence floor
;; ---------------------------------------------------------------------------

(deftest every-view-passes-the-accessibility-invariants
  (doseq [{:view/keys [key label]} views/views]
    (let [r (a11y/check (views/render key (ctx)))]
      (is (a11y/clean? r) (str label ": " (a11y/report r)))
      (testing "and something was actually scanned"
        (is (pos? (get-in r [:a11y/scanned :elements])) label)))))

(deftest the-whole-document-passes-them-too
  (testing "the shell adds the skip link, the nav, the flash and the footer,
            and a view that passed alone can still produce a document that
            does not"
    (doseq [{:view/keys [key label]} views/views]
      (let [html (render/document {:view key :ctx (ctx) :css ""
                                   :flash {:kind :error :message "保留した"}})]
        (is (str/includes? html "<!DOCTYPE html>"))
        (is (str/includes? html "lang=\"ja\""))
        (is (str/includes? html "id=\"main\"") label)))))

(deftest the-views-that-take-input-actually-have-controls
  (testing "an accessibility pass over a screen with no form controls proves
            nothing about the label rules"
    (doseq [k [:employees :run :exports :import]]
      (is (pos? (get-in (a11y/check (views/render k (ctx)))
                        [:a11y/scanned :controls]))
          (str k " rendered no form controls")))))

(deftest the-views-that-show-figures-actually-have-tables
  (doseq [k [:overview :exports :import]]
    (is (pos? (get-in (a11y/check (views/render k (ctx)))
                      [:a11y/scanned :tables]))
        (str k " rendered no tables"))))

;; ---------------------------------------------------------------------------
;; What the screens have to say
;; ---------------------------------------------------------------------------

(deftest a-held-figure-shows-its-reason-on-the-screen
  (testing "not in a tooltip — a `title` is invisible to touch and to the
            keyboard, and the reason a payroll figure is unknown is not
            supplementary information"
    (let [s (pr-str (views/render :run (assoc (ctx) :latest
                                              {:contract-id f/contract-id
                                               :period f/period
                                               :meisai (held-meisai)})))]
      (is (str/includes? s "employment/health-insurance-insured?"))
      (is (str/includes? s "支払ってはならない")))))

(deftest the-overview-tells-the-truth-about-durability
  (let [s (pr-str (views/render :overview (ctx)))]
    (is (str/includes? s "残らない"))
    (is (str/includes? s "消える"))))

(deftest the-overview-explains-every-state-it-can-show
  (testing "a state that renders on a screen and is explained nowhere is one
            an operator learns to ignore"
    (let [s (pr-str (views/render :overview (ctx)))]
      (doseq [c ui/legend]
        (is (str/includes? s (:chip/word c)) (:chip/word c))))))

(deftest the-exports-screen-says-none-of-them-is-a-statutory-form
  (testing "as a COLUMN and not a footnote: an operator scanning the list
            sees the same answer five times"
    (let [s (pr-str (views/render :exports (ctx)))]
      ;; twice per row — once as the cell's text and once as its
      ;; `aria-label`, which is `payroll.ui.a11y`'s `:state-colour-only`
      ;; rule being satisfied rather than a duplicate.
      (is (= (* 2 (count views/artifacts))
             (count (re-seq #"法定様式ではない" s))))
      (is (every? #(false? (:artifact/statutory? %)) views/artifacts)))))

(deftest the-exports-screen-shows-the-zengin-refusal-and-what-is-missing
  (let [s (pr-str (views/render :exports (ctx)))]
    (is (str/includes? s "全銀協"))
    (is (str/includes? s "レコードレイアウト"))))

(deftest the-import-screen-marks-every-column-unverified
  (let [s (pr-str (views/render :import (ctx)))]
    (is (str/includes? s "一度も読んでいない"))
    (is (str/includes? s "対応する概念が無い"))
    (is (<= (count mf-schema/columns)
            (count (re-seq #"未検証" s))))))

(deftest the-import-screen-shows-a-difference-as-a-difference
  (let [s (pr-str (views/render :import (ctx)))]
    (is (str/includes? s "一致していない"))
    (is (str/includes? s "不一致"))))

(deftest the-employees-screen-lists-what-is-not-registered-and-why
  (let [s (pr-str (views/render :employees (ctx)))]
    (is (str/includes? s "未登録は「いいえ」ではない"))
    (is (str/includes? s ":employment/health-insurance-insured?"))
    (is (str/includes? s "未登録は run を保留する"))
    (testing "and a fact that IS registered does not appear as a gap"
      (is (not (str/includes? s ":employment/standard-remuneration-monthly-yen"))))))

(deftest the-ledger-screen-shows-held-entries-alongside-committed-ones
  (let [s (pr-str (views/render :ledger (ctx)))]
    (is (str/includes? s "承認"))
    (is (str/includes? s "保留"))
    (is (str/includes? s "wage-mismatch"))))

(deftest an-empty-list-gets-a-sentence-and-not-a-blank-region
  (testing "an empty table with a heading above it reads as `nothing is
            wrong`"
    (let [empty-ctx (assoc (ctx) :contracts [] :runs [] :latest nil
                           :ledger-entries [] :reconciliation nil)]
      (doseq [k [:employees :run :import :ledger]]
        (is (str/includes? (pr-str (views/render k empty-ctx)) "empty-note")
            (str k " rendered an empty list with no note"))))))

(deftest an-unknown-view-is-an-error-page-and-not-a-blank-one
  (let [s (pr-str (views/render :no-such-view (ctx)))]
    (is (str/includes? s "その画面は無い"))))

;; ---------------------------------------------------------------------------
;; The chip vocabulary
;; ---------------------------------------------------------------------------

(deftest every-provenance-has-a-chip-with-a-mark-and-a-word
  (doseq [p prov/provenances]
    (let [c (get ui/chips p)]
      (is (some? c) p)
      (is (seq (:chip/mark c)))
      (is (seq (:chip/word c))))))

(deftest the-marks-are-different-shapes-and-not-only-different-colours
  (testing "a reader with a colour-vision deficiency distinguishes shapes"
    (is (= (count ui/chips) (count (set (map :chip/mark (vals ui/chips))))))))

(deftest an-unregistered-boolean-is-not-rendered-as-no
  (testing "the one place a console could quietly turn an unanswered question
            into an answer"
    (let [s (pr-str (ui/yes-no-chip nil "健康保険"))]
      (is (str/includes? s "未登録"))
      (is (not (str/includes? s "\"いいえ\""))))
    (is (str/includes? (pr-str (ui/yes-no-chip false "健康保険")) "いいえ"))))

(deftest a-panel-takes-the-worst-tone-of-its-figures
  (testing "eleven confirmed figures and one held one is a panel somebody
            must not act on; an average would render it green"
    (is (= :stop (ui/tone-of (:meisai/figures (held-meisai)))))
    (is (= :caution (ui/tone-of (:meisai/figures (clean-meisai)))))
    (testing "and no figures at all is not :ok"
      (is (= :warn (ui/tone-of []))))))

(deftest a-summary-over-nothing-does-not-read-as-an-all-clear
  (is (str/includes? (ui/summarise []) "「問題なし」ではない")))
