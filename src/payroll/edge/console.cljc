(ns payroll.edge.console
  "The operator console as request-in / response-out, with no host effects.

  Same shape as `payroll.edge.endpoints` and the same three gates: an absent
  allow-list serves 503, a caller not on it serves 403, and everything is
  scoped to that caller's employer. Nothing here relaxes any of them, and the
  employer is never read from a form.

  ## It does not reimplement the run route

  `POST /console/run` builds an EDN body and calls
  `payroll.edge.endpoints/submit-payroll-run-core!`. The console gets exactly
  the validation, the governor and the dispositions the API gets, because it
  IS the API — a console with its own validation path is a second place for
  the rules to be, and the second place is the one that drifts.

  ## Registration goes through `payroll.touroku`, which is a rule written first

  `payroll.edge.endpoints` records why `:reconcile-timesheets` has no route:
  *opening a port to a write the safety layer has no rule about is a decision
  to make by writing the rule first.* Registration needs the same treatment
  and now has it. `POST /console/contract` and `POST /console/timesheet`
  admit through `payroll.touroku` or refuse; neither reaches the store on a
  refusal.

  ## What a form cannot do

  - name an employer (`:client-id` &c are refused, not dropped)
  - register a fact this repository does not read (an unknown key is refused,
    so a typo cannot become a fact that is silently never looked at)
  - default anything — an empty select stays ABSENT and is not `false`

  That last one is the console's most important behaviour and it is a single
  line: `blank->nil` returns nil, and nothing downstream turns a nil coverage
  flag into a `false`. An operator who has not checked whether somebody is a
  被保険者 leaves the field alone, the run is held, and the hold says which
  key to register.

  ## CSRF, and why the check is on `Origin`

  A form POST from a browser is the one thing on this surface a page on
  another origin could try to trigger. `same-origin?` refuses a POST whose
  `Origin` is neither absent nor this deployment's own.

  It is `Origin` and not a token because there is no session to hang a token
  on — the caller's identity is a verified DID supplied by the host, not a
  cookie — and a synchroniser token without a session is theatre.

  ⚠ The host must NOT send `Referrer-Policy: no-referrer`. A page carrying it
  sends `Origin: null` on its OWN same-origin form POST, so every action on
  this console would be refused, and an HTTP client in a test would pass
  because it sends whatever Origin the test gave it (CLAUDE.md records this
  measurement). `payroll.host.config/security-headers` sets `same-origin`."
  (:require [clojure.string :as str]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.artifact.chingin-daicho :as daicho]
            [payroll.artifact.deduction-summary :as summary]
            [payroll.artifact.payslip :as payslip]
            [payroll.edge.endpoints :as api]
            [payroll.meisai :as meisai]
            [payroll.mf.import :as mf-import]
            [payroll.mf.reconcile :as mf-reconcile]
            [payroll.shiwake :as shiwake]
            [payroll.store :as store]
            [payroll.touroku :as touroku]
            [payroll.ui.render :as render]
            [payroll.ui.views :as views]))

;; ---------------------------------------------------------------------------
;; Form and query decoding
;; ---------------------------------------------------------------------------

(defn- decode-component [s]
  #?(:clj (java.net.URLDecoder/decode ^String s "UTF-8")
     :cljs (js/decodeURIComponent s)))

(defn url-decode
  "`application/x-www-form-urlencoded` one value.

  `+` is turned into `%20` BEFORE decoding rather than into a space after,
  because a literal plus arrives as `%2B` and a post-decode replacement would
  turn it into a space — silently corrupting any field an operator typed a
  plus into."
  [s]
  (if (str/blank? (str s))
    ""
    (try (decode-component (str/replace (str s) "+" "%20"))
         (catch #?(:clj Exception :cljs :default) _ ""))))

(defn parse-form
  "`\"a=1&b=%E3%81%82\"` → `{\"a\" \"1\" \"b\" \"あ\"}`.

  A repeated key keeps the FIRST value. Not the last, and not a vector:
  nothing on this console has a repeated field, so a repeat is either a bug
  or somebody trying one — and taking the first makes appending an override
  to a submitted body not work."
  [s]
  (if (str/blank? (str s))
    {}
    (reduce (fn [acc pair]
              (let [i (str/index-of pair "=")
                    k (url-decode (if i (subs pair 0 i) pair))
                    v (url-decode (if i (subs pair (inc i)) ""))]
                (if (or (str/blank? k) (contains? acc k))
                  acc
                  (assoc acc k v))))
            {}
            (str/split (str s) #"&"))))

(defn- blank->nil [v] (when-not (str/blank? (str v)) (str v)))

(defn- ->boolean
  "A tri-state select value.

  `\"true\"` → true, `\"false\"` → false, anything else → **nil**. The empty
  option is `未登録（観測していない）` and it must stay unregistered — see the
  namespace docstring."
  [v]
  (case (str v) "true" true "false" false nil))

(defn- ->yen
  "A number field. Returns nil for blank, `::bad` for anything that is not a
  non-negative integer.

  `::bad` and not 0. A field an operator typed `28,000` into is not an
  accounting of zero, and the console tells them so rather than filing a run
  that withheld nothing."
  [v]
  (let [t (str/trim (str v))]
    (cond
      (str/blank? t) nil
      (re-matches #"\d+" t) #?(:clj (parse-long t) :cljs (js/parseInt t 10))
      :else ::bad)))

;; ---------------------------------------------------------------------------
;; Gates
;; ---------------------------------------------------------------------------

(defn same-origin?
  "Is this POST from this deployment's own page?

  An ABSENT `Origin` is allowed: a non-browser client (curl, a test, an
  operator's script) sends none, and refusing those would make the console
  unusable from anything but a browser without adding any protection — the
  attack this refuses is a page on another origin, and a page always sends
  one."
  [origin self-origin]
  (or (str/blank? (str origin))
      (and (some? self-origin) (= origin self-origin))))

(defn- forbidden [why]
  {:status 403 :content-type "text/plain; charset=utf-8"
   :body (str "refused: " why)})

;; ---------------------------------------------------------------------------
;; Reading the world for a view
;; ---------------------------------------------------------------------------

(defn contracts-of
  "Every contract this employer has registered.

  Read out of the LEDGER's contract ids plus a registry the store does not
  index by employer. `payroll.store` has no `contracts-of` — it looks up one
  contract by id — so the console keeps its own index by reading back the ids
  it has seen. That is a real limitation and is recorded in the README's
  matrix rather than papered over: an employer whose contracts were
  registered by another process and never used in a run will not appear here."
  [store client-id contract-ids]
  (vec (for [id (distinct contract-ids)
             :let [c (store/contract-of store id)]
             :when (and c (= client-id (:contract/employer c)))]
         c)))

(defn- ledger-contract-ids [entries]
  (keep :contract-id entries))

(defn run-of
  "One ledger entry as a run with its `payroll.meisai` lines.

  A held entry has a `:proposal` and no `:record`; a committed one has both.
  Reading `(or record-payload proposal)` is what lets a held run show WHICH
  line was refused rather than a screen of dashes."
  [store entry]
  (let [contract (some->> (:contract-id entry) (store/contract-of store))
        ts (when contract
             (store/timesheets-of store (:contract/worker contract)))
        run (or (get-in entry [:record :payload]) (:proposal entry))]
    {:contract-id (:contract-id entry)
     :worker (:contract/worker contract)
     :period (:period entry)
     :meisai (meisai/lines {:contract contract
                            :timesheets (or ts [])
                            :run run
                            :verdict (:verdict entry)
                            :disposition (:disposition entry)})}))

(defn- payroll-entries
  "Ledger entries that are payroll runs. Handoff facts and 年末調整
  assessments are excluded — a 年末調整 record has no gross and no period, and
  rendering one as a run is the ledger's worst possible lie (the reason both
  read routes carry `:op`)."
  [entries]
  (filterv (fn [e]
             (and (nil? (:handoff/outcome e))
                  (some? (:period e))
                  (not= :assess-year-end-adjustment (get-in e [:record :op]))))
           entries))

(defn context
  "Everything the views need, read once per request."
  [store client-id {:keys [form flash reconciliation]}]
  (let [entries (or (store/ledger-of store client-id) [])
        payroll (payroll-entries entries)
        contracts (contracts-of store client-id (ledger-contract-ids entries))
        runs (mapv #(run-of store %) payroll)]
    {:employer (or (store/client store client-id) {:client-id client-id})
     :contracts contracts
     :runs runs
     :ledger-entries entries
     :form (or form {})
     :flash flash
     :reconciliation reconciliation
     :store {:mode nil}
     :durability nil
     :latest (peek runs)
     :violations (get-in (peek payroll) [:verdict :violations])
     :zengin (bank/zengin {:runs (for [c contracts] {:contract c})})
     :transfer (bank/prepare {:employer {:client-id client-id}
                              :period nil
                              :runs (for [r runs]
                                      {:contract (first (filter #(= (:contract-id r)
                                                                    (:contract/id %))
                                                                contracts))
                                       :meisai (:meisai r)})})}))

;; ---------------------------------------------------------------------------
;; Pages
;; ---------------------------------------------------------------------------

(defn- html-response [status body]
  {:status status
   :content-type "text/html; charset=utf-8"
   :body body})

(defn page
  "Render one view as a full document."
  [{:keys [store client-id view css durability store-mode form flash
           reconciliation]}]
  (let [ctx (-> (context store client-id {:form form :flash flash
                                          :reconciliation reconciliation})
                (assoc :durability durability
                       :store {:mode store-mode}))]
    (html-response 200 (render/document {:view view :ctx ctx :css css
                                         :flash flash}))))

;; ---------------------------------------------------------------------------
;; POST /console/contract
;; ---------------------------------------------------------------------------

(defn- contract-from-form [f]
  (let [rate (->yen (get f "rate"))
        srm (->yen (get f "standard-remuneration"))]
    (cond
      (= ::bad rate) {:error "賃金額が非負の整数ではない"}
      (= ::bad srm) {:error "標準報酬月額が非負の整数ではない"}
      :else
      {:record
       (cond-> {:contract/id (blank->nil (get f "contract-id"))
                :contract/worker (blank->nil (get f "worker"))
                :contract/wage-type (case (get f "wage-type")
                                      "hourly" :hourly
                                      "monthly" :monthly
                                      nil)
                :contract/rate rate
                :contract/currency "JPY"}
         (blank->nil (get f "role")) (assoc :contract/role (blank->nil (get f "role")))
         srm (assoc :employment/standard-remuneration-monthly-yen srm)
         (blank->nil (get f "standard-remuneration-month"))
         (assoc :employment/standard-remuneration-month
                (blank->nil (get f "standard-remuneration-month")))
         (blank->nil (get f "mf-employee-number"))
         (assoc :mf/employee-number (blank->nil (get f "mf-employee-number")))
         ;; the five tri-state facts. `some?` and not truthiness: `false` is a
         ;; registered answer and must be written, while nil must NOT be.
         (some? (->boolean (get f "health-insurance-insured")))
         (assoc :employment/health-insurance-insured?
                (->boolean (get f "health-insurance-insured")))
         (some? (->boolean (get f "care-insurance-second-category")))
         (assoc :employment/care-insurance-second-category?
                (->boolean (get f "care-insurance-second-category")))
         (some? (->boolean (get f "employees-pension-insured")))
         (assoc :employment/employees-pension-insured?
                (->boolean (get f "employees-pension-insured")))
         (some? (->boolean (get f "employment-insurance-insured")))
         (assoc :employment/employment-insurance-insured?
                (->boolean (get f "employment-insurance-insured")))
         (some? (->boolean (get f "year-end-declaration-filed")))
         (assoc :employment/year-end-declaration-filed?
                (->boolean (get f "year-end-declaration-filed"))))})))

(defn register-contract!
  "`POST /console/contract`. Admits through `payroll.touroku` or refuses; a
  refusal writes nothing."
  [{:keys [store client-id form]}]
  (let [{:keys [record error]} (contract-from-form form)]
    (if error
      {:flash {:kind :error :message error} :form form}
      (let [r (touroku/admit-contract client-id record)]
        (if (= :ok (:touroku/status r))
          (do (store/register-contract! store (:touroku/record r))
              {:flash {:kind :ok
                       :message (str "契約 " (:contract/id record) " を登録した。"
                                     "未登録の任意項目は未登録のままである")}
               :form {}})
          {:flash {:kind :error
                   :message (str "登録を拒否した: " (:touroku/why r))}
           :form form})))))

;; ---------------------------------------------------------------------------
;; POST /console/timesheet
;; ---------------------------------------------------------------------------

(defn register-timesheet!
  [{:keys [store client-id form contracts]}]
  (let [hours (->yen (get form "hours"))
        ot (->yen (get form "overtime-hours"))]
    (cond
      (= ::bad hours) {:flash {:kind :error :message "労働時間が非負の整数ではない"}
                       :form form}
      (= ::bad ot) {:flash {:kind :error :message "時間外労働時間が非負の整数ではない"}
                    :form form}
      :else
      (let [entry (cond-> {:ts/worker (blank->nil (get form "worker"))
                           :ts/date (blank->nil (get form "date"))
                           :ts/hours hours}
                    ot (assoc :ts/overtime-hours ot))
            r (touroku/admit-timesheet client-id entry contracts)]
        (if (= :ok (:touroku/status r))
          (do (store/register-timesheet! store (:touroku/record r))
              {:flash {:kind :ok :message "勤怠を登録した"} :form {}})
          {:flash {:kind :error :message (str "登録を拒否した: " (:touroku/why r))}
           :form form})))))

;; ---------------------------------------------------------------------------
;; POST /console/run
;; ---------------------------------------------------------------------------

(def run-amount-fields
  [["income-tax-withheld" :income-tax-withheld]
   ["health-insurance-withheld" :health-insurance-withheld]
   ["care-insurance-withheld" :care-insurance-withheld]
   ["employees-pension-withheld" :employees-pension-withheld]
   ["employment-insurance-withheld" :employment-insurance-withheld]
   ["deductions" :deductions]])

(defn run-body
  "The form as the EDN body `payroll.edge.endpoints` expects, or an error.

  An amount left blank is OMITTED from the body rather than sent as nil. Both
  reach the governor as an unaccounted contribution and are held identically;
  omitting is what keeps the EDN body the same shape a caller would write by
  hand, so the console and the API are exercising one path and not two."
  [f]
  (let [amounts (for [[k target] run-amount-fields] [target (->yen (get f k))])
        bad (vec (for [[target v] amounts :when (= ::bad v)] target))]
    (if (seq bad)
      {:error (str "金額が非負の整数ではない: " (pr-str bad))}
      {:body (pr-str
              (into (cond-> {:period (or (blank->nil (get f "period")) "")}
                      (blank->nil (get f "contract-id"))
                      (assoc :contract-id (blank->nil (get f "contract-id"))))
                    (for [[target v] amounts :when (some? v)] [target v])))})))

(defn submit-run!
  [{:keys [store store-mode allowlist caller-did form advisor]}]
  (let [{:keys [body error]} (run-body form)]
    (if error
      {:flash {:kind :error :message error} :form form}
      (let [r (api/submit-payroll-run-core! store store-mode allowlist
                                            caller-did body advisor)
            d (get-in r [:body :disposition])]
        {:flash {:kind (if (= :commit d) :ok :error)
                 :message (case d
                            :commit "承認し、台帳に記録した"
                            :request-approval "人の署名待ちとして記録した。まだ支払われていない"
                            :hold (str "保留した。理由は下の一覧にある（"
                                       (count (get-in r [:body :violations]))
                                       " 件）")
                            (str "受け付けられなかった: "
                                 (or (get-in r [:body :error]) "不明")))}
         :form form
         :api-response r}))))

;; ---------------------------------------------------------------------------
;; POST /console/mf
;; ---------------------------------------------------------------------------

(defn reconcile!
  "Parse an MF export and reconcile it against this employer's runs.

  **Nothing is written.** Not the file, not a row, not a run. The report is
  rendered and discarded, which is what makes pasting a payroll export into
  this box a bounded act."
  [{:keys [store client-id form]}]
  (let [period (blank->nil (get form "period"))
        ctx (context store client-id {})
        report (mf-import/parse (get form "csv") (:contracts ctx))
        ours (into {} (for [r (:runs ctx)]
                        [[(:contract-id r) (:period r)] (:meisai r)]))
        recon (mf-reconcile/reconcile {:import report :ours ours :period period})]
    {:reconciliation recon
     :form form
     :flash {:kind (if (:reconcile/reconciled? recon) :ok :error)
             :message (:reconcile/why recon)}}))

;; ---------------------------------------------------------------------------
;; GET /console/export
;; ---------------------------------------------------------------------------

(def journal-mapping-note
  "Why the journal export refuses without a chart of accounts.

  `payroll.shiwake` will not choose the accounts and neither will this — the
  accounts are the employer's chart, and `kotoba-lang/shohyo` refuses to guess
  what an account is because a statement that guessed still balances."
  (str "仕訳の勘定科目は事業主の勘定体系であり、この actor は選ばない。"
       "勘定科目の対応表が登録されるまで、仕訳は出力されない"))

(defn export
  "`GET /console/export?kind=…&format=…&contract-id=…&period=…`

  Returns `{:status :content-type :body :filename}`. A kind or format this
  console does not produce is a 400 naming what it does produce — never an
  empty file, which is the failure mode an operator discovers after emailing
  it."
  [{:keys [store client-id query css]}]
  (let [kind (get query "kind")
        fmt (get query "format" "json")
        contract-id (blank->nil (get query "contract-id"))
        period (blank->nil (get query "period"))
        ctx (context store client-id {})
        employer (:employer ctx)
        runs (cond->> (:runs ctx)
               contract-id (filterv #(= contract-id (:contract-id %)))
               period (filterv #(= period (:period %))))
        text (fn [ct s name*]
               {:status 200 :content-type ct :body s :filename name*})
        bad (fn [why]
              {:status 400 :content-type "text/plain; charset=utf-8" :body why})]
    (cond
      (empty? runs)
      (bad (str "該当する run が無い（契約 " (pr-str contract-id)
                "・期間 " (pr-str period)
                "）。run が無いことと、出力が空であることは違う"))

      (= kind "payslip")
      (let [r (first runs)
            contract (first (filter #(= (:contract-id r) (:contract/id %))
                                    (:contracts ctx)))
            slip (payslip/record {:employer employer :contract contract
                                  :period (:period r) :meisai (:meisai r)})]
        (case fmt
          "json" (text "application/json; charset=utf-8" (payslip/->json slip)
                       "payslip.json")
          "html" (text "text/html; charset=utf-8"
                       (render/payslip-document {:hiccup (payslip/->hiccup slip)
                                                 :css css})
                       "payslip.html")
          (bad "給与支払明細書は json または html でのみ出力する")))

      (= kind "wage-ledger")
      (let [rows (mapv daicho/row runs)]
        (case fmt
          "csv" (text "text/csv; charset=utf-8" (daicho/->csv rows) "chingin-daicho.csv")
          "json" (text "application/json; charset=utf-8" (daicho/->json rows)
                       "chingin-daicho.json")
          (bad "賃金台帳は csv または json でのみ出力する")))

      (= kind "deduction-summary")
      (let [s (summary/summarise {:employer employer :period period :runs runs})]
        (case fmt
          "csv" (text "text/csv; charset=utf-8" (summary/->csv s) "koujo-shukei.csv")
          "json" (text "application/json; charset=utf-8" (summary/->json s)
                       "koujo-shukei.json")
          (bad "控除額集計は csv または json でのみ出力する")))

      (= kind "bank-transfer")
      (let [t (bank/prepare
               {:employer employer :period period
                :runs (for [r runs]
                        {:contract (first (filter #(= (:contract-id r)
                                                      (:contract/id %))
                                                  (:contracts ctx)))
                         :meisai (:meisai r)})})]
        (case fmt
          "csv" (text "text/csv; charset=utf-8" (bank/->csv t) "furikomi.csv")
          "json" (text "application/json; charset=utf-8" (bank/->json t)
                       "furikomi.json")
          (bad "振込データは csv または json でのみ出力する")))

      (= kind "journal")
      ;; No chart of accounts is registered anywhere in this repository, so
      ;; every run converts to `:no-mapping`. That refusal is the answer and
      ;; is served as one — not as an empty array, which would read as
      ;; `nothing to post`.
      (let [rs (shiwake/entry-requests
                (for [r runs]
                  {:disposition (get-in r [:meisai :meisai/disposition])
                   :run (merge {:contract-id (:contract-id r)
                                :period (:period r)}
                               (select-keys
                                (into {} (for [d (get-in r [:meisai :meisai/deductions])]
                                           [(:line/key d)
                                            (get-in d [:line/figure :figure/amount])]))
                                [:income-tax-withheld :health-insurance-withheld
                                 :care-insurance-withheld :employees-pension-withheld
                                 :employment-insurance-withheld])
                               {:gross (get-in r [:meisai :meisai/gross :figure/amount])
                                :net (get-in r [:meisai :meisai/net :figure/amount])
                                :currency "JPY"})})
                {})]
        (text "application/json; charset=utf-8"
              (str "{\"document_type\":\"journal_handoff\","
                   "\"note\":\"" journal-mapping-note "\","
                   "\"ok\":" (count (:ok rs)) ","
                   "\"skipped\":" (count (:skipped rs)) "}")
              "shiwake.json"))

      :else
      (bad (str "その書類は無い。出せるのは "
                (str/join "、" (map #(name (:artifact/key %)) views/artifacts)))))))

;; ---------------------------------------------------------------------------
;; The surface
;; ---------------------------------------------------------------------------

(def console-paths (into #{} (map :view/path) views/views))

(defn route
  "The console, as data in and data out.

  `request` is
  `{:method :get|:post :path \"…\" :query {…} :form {…} :origin \"…\"}`.
  Returns `{:status :content-type :body :filename?}`.

  The gates are checked in the same order as `payroll.edge.endpoints/route`
  and for the same reasons; a path this console does not have is 404 and a
  method it does not have on a real path is 405."
  [{:keys [store store-mode allowlist caller-did css durability self-origin
           advisor]}
   {:keys [method path query form origin]}]
  (cond
    (or (nil? store-mode) (nil? store))
    {:status 503 :content-type "text/plain; charset=utf-8"
     :body (str "no store configured — "
                (get-in (api/store-unconfigured-response) [:body :hint]))}

    (nil? allowlist)
    {:status 503 :content-type "text/plain; charset=utf-8"
     :body "no allow-list configured"}

    (nil? (api/employer-for allowlist caller-did))
    (forbidden "caller not permitted")

    (and (= :post method) (not (same-origin? origin self-origin)))
    (forbidden (str "cross-origin form post (Origin: " (pr-str origin) ")"))

    :else
    (let [client-id (api/employer-for allowlist caller-did)
          base {:store store :client-id client-id :css css
                :durability durability :store-mode store-mode}
          show (fn [view extra]
                 (page (merge base {:view view} extra)))]
      (cond
        (and (= :get method) (contains? console-paths path))
        (show (:view/key (get views/by-path path)) {:form query})

        (= path "/console/export")
        (if (= :get method)
          (export (assoc base :query (or query {})))
          {:status 405 :content-type "text/plain; charset=utf-8"
           :body "method not allowed"})

        (and (= :post method) (= path "/console/contract"))
        (let [{:keys [flash form]} (register-contract!
                                    (assoc base :form (or form {})))]
          (show :employees {:flash flash :form (into {} (for [[k v] form]
                                                          [(keyword k) v]))}))

        (and (= :post method) (= path "/console/timesheet"))
        (let [ctx (context store client-id {})
              {:keys [flash form]} (register-timesheet!
                                    (assoc base :form (or form {})
                                           :contracts (:contracts ctx)))]
          (show :employees {:flash flash :form (into {} (for [[k v] form]
                                                          [(keyword k) v]))}))

        (and (= :post method) (= path "/console/run"))
        (let [{:keys [flash form]} (submit-run!
                                    (assoc base :allowlist allowlist
                                           :caller-did caller-did
                                           :advisor advisor
                                           :form (or form {})))]
          (show :run {:flash flash :form (into {} (for [[k v] form]
                                                    [(keyword k) v]))}))

        (and (= :post method) (= path "/console/mf"))
        (let [{:keys [flash form reconciliation]} (reconcile!
                                                   (assoc base :form (or form {})))]
          (show :import {:flash flash
                         :reconciliation reconciliation
                         :form (into {} (for [[k v] form] [(keyword k) v]))}))

        (contains? console-paths path)
        {:status 405 :content-type "text/plain; charset=utf-8"
         :body "method not allowed"}

        :else
        {:status 404 :content-type "text/plain; charset=utf-8"
         :body "no such console page"}))))
