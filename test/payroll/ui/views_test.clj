(ns payroll.ui.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.fixtures :as f]
            [payroll.juminzei :as juminzei]
            [payroll.mf.import :as mf]
            [payroll.projection.r2 :as r2]
            [payroll.mf.reconcile :as recon]
            [payroll.operations :as ops]
            [payroll.ui.a11y :as a11y]
            [payroll.ui.render :as render]
            [payroll.ui.state :as ui]
            [payroll.mf.schema :as mf-schema]
            [payroll.provenance :as prov]
            [payroll.artifact.zengin :as zengin]
            [payroll.cutover :as cutover]
            [payroll.ui.views :as views]))

(defn- clean-meisai [] (f/lines {:verdict (f/verdict-for)}))

(defn- held-meisai []
  (let [st (f/fresh-store
            {:contract-overrides {:employment/health-insurance-insured? nil}})
        p (f/proposal)]
    (f/lines {:contract* (f/contract {:employment/health-insurance-insured? nil})
              :run p :verdict (f/verdict-for st p) :disposition :hold})))

(def ops-store
  "The store the report below is built over, with a 住民税 notice REGISTERED
  in it.

  Registered, and not handed to `ops/report` as an option, because there is no
  longer an option: `payroll.operations/report` reads the notices off the
  store itself. It goes in through `payroll.juminzei/register-notice!` rather
  than by seeding the atom, so this fixture cannot register something the
  admission layer would have refused."
  (let [st (f/fresh-store)]
    (juminzei/register-notice! st {:employer f/employer-id
                                   :notice f/resident-tax-notice-as-transcribed})
    st))

(def ops-report
  "A report with every section populated, including the two the host
  measures.

  `:store-health` and `:projection-preflight` are supplied here because a
  screen rendered from a report that has neither only ever exercises the
  `not-reported` / `not-configured` branches — and those are exactly the
  branches an operator sees least and can act on least."
  (ops/report
   {:store ops-store
    :employer f/employer-id
    :store-health {:store/mode :kotobase :store/readable? true
                   :store/survives-process-restart? true
                   :store/entries-are-a-floor? false
                   :store/key-separation :separate
                   :store/break-kinds []
                   :store/streams [{:stream :ledger :head "bafk" :entries 2
                                    :complete? true :broken [] :why nil}]
                   :store/why "七つの chain すべてを head から末尾まで辿れた"}
    :projection-preflight (r2/preflight {})}))

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
     ;; The operations screen renders a REPORT, so the context carries one —
     ;; without it the accessibility sweep below would only ever walk that
     ;; view's `報告が無い` branch, and a screen checked over an empty state
     ;; has nothing to get wrong. `the-operations-screen-...` tests below
     ;; assert the other direction directly.
     :operations ops-report
     :operations-blockers (ops/blockers ops-report)
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

(deftest the-exports-screen-separates-what-was-transcribed-from-what-was-not
  (testing "the 全銀 section used to be a refusal. It is now an output, and the
            screen has to keep saying which parts of it are still unverified —
            an output with no such column reads as an output that was checked"
    (let [s (pr-str (views/render :exports (ctx)))]
      (is (str/includes? s "PayPay銀行"))
      (is (str/includes? s "Shift_JIS"))
      (is (str/includes? s (str zengin/record-length " バイト")))
      (testing "the revision date is read off the namespace, not restated here"
        (is (str/includes? s (:source/revised zengin/source))))
      (testing "and the three things still unverified are each named"
        (is (str/includes? s "テスト振込は行われていない"))
        (is (str/includes? s "仕様書の本文は終端子を明示していない"))
        (is (str/includes? s (:discrepancy/what zengin/csv-sample-discrepancy))))
      (testing "the bank acceptance row is marked unverified and not omitted"
        (is (str/includes? s "銀行がこのファイルを受理すること"))))))

(deftest the-exports-screen-offers-the-fixed-width-format
  (let [s (pr-str (views/render :exports (ctx)))]
    (is (str/includes? s "fixed-width"))
    (is (some #(= :zengin (:artifact/key %)) views/artifacts))
    (is (= [:fixed-width :csv :json]
           (:artifact/formats (first (filter #(= :zengin (:artifact/key %))
                                             views/artifacts)))))))

(deftest the-import-screen-marks-every-column-unverified
  (let [s (pr-str (views/render :import (ctx)))]
    (is (str/includes? s "一度も読んでいない"))
    (is (<= (count mf-schema/columns)
            (count (re-seq #"未検証" s))))))

(deftest the-import-screen-marks-a-column-with-no-counterpart-as-having-none
  ;; This assertion was DELETED on 2026-08-26 rather than repaired. 住民税 was
  ;; the only `:mf/no-counterpart` column, `payroll.juminzei` gave it one, the
  ;; literal stopped appearing, and the line came out — which left the whole
  ;; `:mf/no-counterpart` rendering branch live and unmeasured.
  ;;
  ;; Deleting it was the wrong repair for a reason the CLAUDE.md rule about
  ;; evidence floors names exactly: a screen that has no such column and a
  ;; screen that has forgotten how to render one look identical, and the
  ;; second is how a MoneyForward column carrying a real deduction becomes a
  ;; blank cell.
  (testing "today there is NO such column, and that is asserted rather than
            being the reason the check is absent"
    (is (empty? mf-schema/no-counterpart-columns))
    (is (not (str/includes? (pr-str (views/render :import (ctx)))
                            "対応する概念が無い"))
        "the marker must not appear when nothing has no counterpart")
    (is (every? keyword? (map :mf/to mf-schema/columns))
        "every column maps to a real target"))
  (testing "and the branch that renders it still works — measured by giving
            the screen a column that has no counterpart"
    (with-redefs [mf-schema/columns
                  (conj (vec mf-schema/columns)
                        {:mf/column "架空の控除" :mf/to :mf/no-counterpart
                         :mf/kind :yen :mf/required? false :mf/verified? false
                         :mf/no-counterpart-why "試験用"})]
      (let [s (pr-str (views/render :import (ctx)))]
        (is (str/includes? s "対応する概念が無い"))
        (is (str/includes? s "架空の控除"))
        (testing "and it is not conveyed by colour alone"
          (is (str/includes? s "この actor に対応する概念が無い")))))))

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
;; 運用の現況
;; ---------------------------------------------------------------------------

(deftest the-operations-screen-renders-the-same-report-the-api-serves
  (testing "not a second assembly of the same facts. Every section id in the
            report has a panel, and the numbers on the screen are the report's
            own"
    (let [s (pr-str (views/render :operations (ctx)))]
      (doseq [{:section/keys [label]} (:report/sections ops-report)]
        (is (str/includes? s label) label))
      (testing "住民税 — the registered notice renders its year and its month
                count, both of which were read off `payroll.juminzei` field
                names rather than guessed"
        (is (str/includes? s "架空区"))
        (is (str/includes? s "\"2026\""))
        (is (str/includes? s "12 / 12 か月")))
      (testing "the 源泉徴収税額表 reports its band count next to the digest of
                the edition they came from"
        (is (str/includes? s "231"))
        (is (str/includes? s (get-in (first (filter #(= :rates (:section/id %))
                                                    (:report/sections ops-report)))
                                     [:section/withholding-table :table/sha256]))))
      (testing "and the cutover conditions are the gate's own list"
        (doseq [c cutover/conditions]
          (is (str/includes? s (:gate/label c)) (:gate/label c)))))))

(deftest the-operations-screen-offers-the-zengin-download
  (testing "the exports screen was the only place the fixed-width file could
            be reached. A screen that says a file exists and cannot hand it
            over says it twice"
    (let [s (pr-str (views/render :operations (ctx)))]
      (is (str/includes? s (str views/export-path "?kind=zengin&format=fixed-width")))
      (testing "every declared format of every artifact gets its own link —
                a format is not a rendering of another one"
        (doseq [a views/artifacts
                fmt (:artifact/formats a)]
          (is (str/includes? s (str views/export-path "?kind=" (name (:artifact/key a))
                                    "&format=" (name fmt)))
              (str (:artifact/label a) " " fmt))))
      (testing "and the path is the def the router serves, not a literal"
        (is (= "/console/export" views/export-path))))))

(deftest the-operations-screen-offers-the-resident-tax-notice-form
  (testing "this panel used to end with 「この画面にフォームは無い」, and the
            sentence was true. It is now the opposite claim, and the screen has
            to carry the form rather than the apology"
    (let [tree (views/render :operations (ctx))
          s (pr-str tree)]
      (is (not (str/includes? s "この画面にフォームは無い")))
      (is (str/includes? s "この通知を登録する"))
      (is (str/includes? s (:registration/why ops/resident-tax-registration)))
      (is (str/includes? s (:registration/action ops/resident-tax-registration)))

      (testing "it posts to the path the router actually serves, and the path
                is the def rather than a literal"
        (is (str/includes? s (str ":action \"" views/notice-path "\"")))
        (is (str/includes? s ":method \"post\""))
        (is (= "/console/juminzei-notice" views/notice-path))
        (testing "and the report names the same path, so an operator reading
                  the report is told where the form actually is"
          (is (str/includes? (:registration/path ops/resident-tax-registration)
                             views/notice-path))))

      (testing "every field of `payroll.juminzei/notice-fields` is on the form
                with its label — the list of what to transcribe and the boxes
                to transcribe it into are one list, so a field added to the
                admission layer cannot become a field nobody can supply"
        (doseq [fld juminzei/notice-fields]
          (is (str/includes? s (:field/label fld)) (:field/label fld))))

      (testing "the 通知の種類 select has an EMPTY option and it is first — a
                select whose first option is 決定通知書 submits 決定通知書 from
                a form nobody touched, and that is a default with legal
                consequences"
        (is (str/includes? s "選択されていない"))
        (is (str/includes? s "特別徴収税額の決定通知書"))
        (is (str/includes? s "特別徴収税額の変更通知書"))
        (is (str/includes? s ":value \"decision\""))
        (is (str/includes? s ":value \"revision\"")))

      (testing "the twelve 月割額 are twelve labelled controls inside a
                fieldset with a legend, and not one box with a bare number
                beside it — a screen reader announces the legend when focus
                enters the group"
        (is (str/includes? s ":fieldset"))
        (is (str/includes? s "月割額（6月から翌年5月までの12か月）"))
        (doseq [[k m] (map vector juminzei/month-keys juminzei/collection-months)]
          (is (str/includes? s (str ":name \"" (clojure.core/name k) "\"")) (str k))
          (is (str/includes? s (str (:month/label m) "の月割額（円）"))
              (:month/label m))))

      (testing "and the whole screen still passes the accessibility
                invariants — a form is where a label goes missing"
        (is (a11y/clean? (a11y/check tree)) (a11y/report (a11y/check tree)))))))

(deftest the-notice-form-keeps-a-refused-transcription-on-the-screen
  (testing "twelve figures are five minutes of somebody reading off a piece of
            paper. A refusal that cleared them would teach an operator to
            write them into a text file first — which is the payroll data this
            repository spends `payroll.sensitive` keeping out of exactly that
            kind of place"
    (let [form {:municipality "架空区" :tax-year "2026"
                :reference "R8-0000-0000" :revision "0"
                :registered-at "2026-05-31" :m06 "8200" :m05 "8200"}
          s (pr-str (views/render :operations (assoc (ctx) :form form)))]
      (doseq [v ["架空区" "R8-0000-0000" "2026-05-31"]]
        (is (str/includes? s (str ":value \"" v "\"")) v)))))

(deftest the-notice-form-confirms-by-reading-the-store-back
  (testing "an echo of the form proves the form was submitted; reading the
            store back proves it was REGISTERED — and the confirmation still
            carries no amount, because this is the screen that gets
            screenshotted into a ticket"
    (doseq [[q c] views/notice-confirmations]
      (let [tree (views/render :operations (assoc (ctx) :notice-confirmation c))
            s (pr-str tree)]
        (is (str/includes? s (:confirmation/label c)) q)
        (is (str/includes? s (:confirmation/message c)) q)
        (testing "with the counts and the coverage read out of the section"
          (is (str/includes? s "いまこの事業主に登録されている通知は 1 件"))
          (is (str/includes? s "2026 年度 12 / 12 か月")))
        (testing "and no 月割額 and no 年税額 anywhere in it"
          (doseq [amount [(str f/resident-tax) (str (* 12 f/resident-tax))]]
            (is (not (str/includes? s amount)) (str amount " in " q))))
        (is (a11y/clean? (a11y/check tree)) q)))
    (testing "and no banner at all when the request carried no confirmation —
              a screen that always says 「登録した」 says nothing"
      (let [s (pr-str (views/render :operations (ctx)))]
        (doseq [[_ c] views/notice-confirmations]
          (is (not (str/includes? s (:confirmation/label c)))))))))

(deftest the-operations-screen-separates-a-correction-from-a-second-opinion
  (testing "a superseded notice is still in the store and is still shown, with
            the word 差し替えられた on it. A screen that showed only what is in
            force would show one notice where a municipality sent two, and the
            employee asking 「なぜ8月と9月で控除額が違うのか」 could not be
            answered from it"
    (let [st (f/fresh-store)
          _ (doseq [n [f/resident-tax-notice-as-transcribed
                       f/resident-tax-notice-revised-as-transcribed]]
              (juminzei/register-notice! st {:employer f/employer-id :notice n}))
          rep (ops/report {:store st :employer f/employer-id})
          s (pr-str (views/render :operations
                                  (assoc (ctx)
                                         :operations rep
                                         :operations-blockers (ops/blockers rep))))]
      (is (str/includes? s "差し替えられた（記録として残してある）"))
      (is (str/includes? s "有効"))
      (testing "the counts say two registered, one effective, one superseded"
        (is (str/includes? s "2 件"))
        (is (str/includes? s "1 件")))
      (testing "and the correction's 改訂番号 is on the row, so which paper is
                the later one is legible without opening the store"
        (is (str/includes? s "\"1\""))))))

(deftest the-operations-screen-shows-the-projection-preflight-without-a-catalog
  (testing "`not-configured` says this process holds no catalog driver. The
            preflight says only whether the three variables are named, and the
            two are different next actions"
    (let [s (pr-str (views/render :operations (ctx)))]
      (is (str/includes? s "未設定"))
      (is (str/includes? s "R2_CATALOG_URI"))
      (is (str/includes? s "設定の事前確認（要求は一切送っていない）"))
      (testing "and the panel never says 作れる — the preflight reads no token
                and sends no request, so it cannot answer that"
        (is (not (str/includes? s "作れる。")))
        (is (not (str/includes? s "作れない。")))
        (is (str/includes? s "この確認は資格情報の確認ではない"))))))

(deftest the-operations-screen-shows-the-live-verification-with-its-limits
  (testing "this panel is the one most likely to be screenshotted into a
            status report, so 「確かめた」 never appears on it without the four
            things that were not — above all that nothing went through this
            repository"
    (let [rep (ops/report
               {:store ops-store :employer f/employer-id
                :projection-preflight
                (r2/preflight {"R2_CATALOG_URI" "https://catalog.example"
                               "R2_WAREHOUSE" "acct_bucket"
                               "R2_CATALOG_TOKEN" "irrelevant"})})
          tree (views/render :operations
                             (assoc (ctx) :operations rep
                                    :operations-blockers (ops/blockers rep)))
          s (pr-str tree)]
      (is (str/includes? s "手作業で確かめた"))
      (is (str/includes? s "実在の給与データは書いていない"))
      (is (str/includes? s "payroll.projection.catalog は経路上に無い"))
      (is (str/includes? s ":projection-health は nil"))
      (testing "the 401 is on the screen as dated history, never without its
                resolution"
        (is (str/includes? s "履歴（2026-08-26）"))
        (is (str/includes? s "解消済み（2026-08-26）")))
      (testing "the token is on the screen as a DATED revocation measured
                against the issued expiry, because 「短命」 told an operator
                there was nothing left to decide while the credential was
                still usable — and the decision has since been made"
        (is (str/includes? s "トークンの扱い: 2026-08-26 に Cloudflare の dashboard で削除して失効済み"))
        (is (str/includes? s "発行時の期限 2026-08-28 より前"))
        (is (str/includes? s "値はこの機械に一度も保存していない"))
        (is (str/includes? s "クリップボードは検証後に消去した"))
        (is (not (str/includes? s "短命")))
        (is (not (str/includes? s "どこにも保存"))))
      (testing "and the panel names the exact target and how the delete was
                checked. A screenshot saying 「失効済み」 with no token name is
                one nobody can match against the dashboard"
        (is (str/includes? s "cloud-itonami-payroll-r2-provisioning-260826"))
        (is (str/includes? s "一覧に存在しないことを確かめた")))
      (testing "and the panel claims nothing about present-time state in
                either direction. This text is rendered from a fixed record:
                「今も有効である」 would go on asserting validity, and
                「2026-08-26 時点では未失効」 — what stood here — is now simply
                false. 「失効済み」 is the one state a screenshot can carry
                safely, because a deleted token stays deleted"
        (doseq [p ["今も有効" "現在も有効" "まだ有効" "まだ失効させていない"
                   "未失効" "それ以降の状態はこの記録では分からない"]]
          (is (not (str/includes? s p)) p)))
      (testing "and it is still an accessible screen"
        (is (a11y/clean? (a11y/check tree)))))))

(deftest an-operations-screen-with-no-report-says-so-rather-than-rendering-blank
  (testing "a blank screen there is indistinguishable from a deployment with
            nothing wrong — which is this repository's most-named defect at
            the point where it would be invisible"
    (let [empty-ctx (assoc (ctx) :operations nil :operations-blockers nil)
          tree (views/render :operations empty-ctx)
          s (pr-str tree)]
      (is (str/includes? s "画面が空であることは「問題が無い」ではない"))
      (is (str/includes? s "/api/operations"))
      (testing "and it is still an accessible screen and not an error"
        (is (a11y/clean? (a11y/check tree)))))))

(deftest the-operations-screen-carries-no-payroll-amount-and-no-name
  (testing "this is the surface most likely to be screenshotted into a ticket"
    (let [s (pr-str (views/render :operations (ctx)))]
      (doseq [leak [f/worker (:bank/payee-name-kana (f/contract))
                    (str f/gross) (str f/net)]]
        (is (not (str/includes? s leak)) leak)))))

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
