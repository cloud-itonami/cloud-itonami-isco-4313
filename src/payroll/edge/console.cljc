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
  refusal. `POST /console/juminzei-notice` is the third, admitting through
  `payroll.juminzei` — a different rule for a different write, and written
  before the route the same way.

  ## One route redirects, and the reason is what is in the form

  `POST /console/juminzei-notice` answers 303 on `:ok` and on `:duplicate`
  and re-renders on a refusal. That asymmetry is not a style: a success that
  stayed on a 200 is a page whose reload re-submits twelve transcribed
  figures, and a refusal that redirected would throw those twelve figures
  away. Nothing an operator typed goes into the redirect target — a query
  string lands in browser history, in proxy logs and in a screenshot of the
  address bar — so the landing page reads back what was registered out of the
  store instead.

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
            [payroll.artifact.zengin :as zengin]
            [payroll.edge.endpoints :as api]
            [payroll.juminzei :as juminzei]
            [payroll.meisai :as meisai]
            [payroll.mf.import :as mf-import]
            [payroll.mf.reconcile :as mf-reconcile]
            [payroll.operations :as operations]
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

(defn- ->resident-tax-obligation
  "`->boolean`'s shape for a select whose answers are KEYWORDS.

  Blank → **nil**, and nil must stay unregistered: an employee nobody has
  classified is not 「対象外」 and is not 特別徴収 either, so
  `payroll.juminzei/assess` answers `:municipality-not-declared` and holds
  the line. That is the point of the empty option.

  Anything else → `::bad`, and NOT nil. `->boolean` can fold a value it does
  not know back into `未登録` because its own select offers exactly three
  strings and a fourth one arriving is a broken client. Here the difference
  between the two registered answers is a deduction taken and a deduction
  not taken, so a value nobody recognises is refused and TOLD rather than
  quietly filed as 「まだ誰も分類していない」."
  [v]
  (case (str/trim (str v))
    "" nil
    "special-collection" :special-collection
    "not-special-collection" :not-special-collection
    ::bad))

(defn- ->non-negative-integer
  "A whole non-negative number field. nil for blank, `::bad` for anything
  else, and never a coerced 0.

  The rule lives here once and `->yen` is its money-shaped name. Two copies of
  the regex would be two chances for one of them to start accepting `28,000`
  — which is the one thing this function exists to refuse."
  [v]
  (let [t (str/trim (str v))]
    (cond
      (str/blank? t) nil
      (re-matches #"\d+" t) #?(:clj (parse-long t) :cljs (js/parseInt t 10))
      :else ::bad)))

(defn- ->yen
  "A number field. Returns nil for blank, `::bad` for anything that is not a
  non-negative integer.

  `::bad` and not 0. A field an operator typed `28,000` into is not an
  accounting of zero, and the console tells them so rather than filing a run
  that withheld nothing."
  [v]
  (->non-negative-integer v))

(defn- ->count
  "The same three answers as `->yen`, under its own name, for a field that is
  not money.

  改訂番号 is a COUNT of how many times a municipality has reissued one piece
  of paper. Reading it through a function called `->yen` is how a field's
  meaning quietly becomes its neighbour's — and this one ends up in
  `payroll.juminzei/notice-id`, so getting it wrong forks a correction
  history rather than mis-stating an amount."
  [v]
  (->non-negative-integer v))

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
  line was refused rather than a screen of dashes.

  `notices` are this employer's registered 住民税 決定通知書. Absent, the
  資民税 line is `:unknown` and the run is not payable — which is the
  refusal, not an omission."
  ([store entry] (run-of store entry []))
  ([store entry notices]
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
                            :juminzei (when (seq notices)
                                        (juminzei/assess
                                         {:period (:period entry)
                                          :notices notices
                                          :obligation (:employment/resident-tax-obligation
                                                       contract)}))
                            :disposition (:disposition entry)})})))

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
  "Everything the views need, read once per request.

  `extras` is what only the HOST can measure — the store's own health, the
  catalog's, the projection preflight — and it is threaded through unchanged
  to `payroll.operations/report`, which is the SAME function
  `GET /api/operations` calls. The console does not assemble a second answer:
  a screen and an endpoint that each build their own are two answers, and the
  one nobody is looking at is the one that goes stale.

  Absent extras are absent, never defaulted to a pass. `payroll.operations`
  answers `not-reported` / `not-configured` for a health nobody supplied, and
  those two values exist precisely so they cannot be read as `healthy`.

  ## The 住民税 notices are READ and are not injected

  They used to arrive as `:juminzei-notices`, on the opts or on the extras,
  and nothing outside the test suite ever put them there. So the operations
  screen said 「決定通知書が一件も登録されていない」 to employers who had
  registered notices, and every run on the 給与計算 screen carried an
  `:unknown` 住民税 line for the same reason — one absent option, two screens
  answering wrong, and a test suite that supplied it and therefore never saw
  either. The store is right here; asking it is not an extra."
  [store client-id {:keys [form flash reconciliation extras notice-confirmation]}]
  (let [entries (or (store/ledger-of store client-id) [])
        payroll (payroll-entries entries)
        contracts (contracts-of store client-id (ledger-contract-ids entries))
        ;; the same read `payroll.operations/report` makes, and refused the
        ;; same way: a store that cannot answer must not take the console
        ;; down, and an unreadable history is not an empty one — the runs
        ;; below then carry an `:unknown` 住民税 line, which is the refusal.
        notices (try (vec (store/juminzei-notices store client-id))
                     (catch #?(:clj Exception :cljs :default) _ []))
        runs (mapv #(run-of store % notices) payroll)
        report (operations/report
                (merge {:store store :employer client-id
                        :reconciliation reconciliation}
                       extras))]
    {:employer (or (store/client store client-id) {:client-id client-id})
     :operations report
     :operations-blockers (operations/blockers report)
     :contracts contracts
     :runs runs
     :ledger-entries entries
     :form (or form {})
     :flash flash
     :notice-confirmation notice-confirmation
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
           reconciliation extras notice-confirmation]}]
  (let [ctx (-> (context store client-id
                         {:form form :flash flash
                          :reconciliation reconciliation
                          :extras extras
                          :notice-confirmation notice-confirmation})
                (assoc :durability durability
                       :store {:mode store-mode}))]
    (html-response 200 (render/document {:view view :ctx ctx :css css
                                         :flash flash}))))

;; ---------------------------------------------------------------------------
;; POST /console/contract
;; ---------------------------------------------------------------------------

(defn- contract-from-form [f]
  (let [rate (->yen (get f "rate"))
        srm (->yen (get f "standard-remuneration"))
        rto (->resident-tax-obligation (get f "resident-tax-obligation"))]
    (cond
      (= ::bad rate) {:error "賃金額が非負の整数ではない"}
      (= ::bad srm) {:error "標準報酬月額が非負の整数ではない"}
      (= ::bad rto) {:error (str "住民税の特別徴収の区分が "
                                 "special-collection でも "
                                 "not-special-collection でもない。"
                                 "未登録にするなら空欄にする —— "
                                 "読めない値を未登録として保存はしない")}
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
                (->boolean (get f "year-end-declaration-filed")))
         ;; 住民税, on the same `some?` discipline and for the same reason:
         ;; `:not-special-collection` is a REGISTERED answer and must be
         ;; written, while a blank must not be — a written nil and an absent
         ;; key look the same to `assess` today, but only the absent one is
         ;; honest about nobody having classified this employee.
         (some? rto) (assoc :employment/resident-tax-obligation rto))})))

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
;; POST /console/juminzei-notice
;; ---------------------------------------------------------------------------

(def operations-path
  "Where a registration lands after the redirect.

  Read off `payroll.ui.views` rather than written here, for the reason the
  export path is: the screen, the router and this redirect are three places
  that must agree on one path, and a redirect to a path nobody serves is a
  303 into a 404 — which an operator reads as `the registration failed` when
  in fact it succeeded."
  (:view/path (views/by-key :operations)))

(defn- notice-month-key
  "`\"m06\"` → `:juminzei/m06`. The twelve field names ARE the twelve keys'
  names, so this is a lookup in `payroll.juminzei/month-keys` and not a
  keyword built from whatever arrived — `(keyword \"juminzei\" v)` would
  happily manufacture `:juminzei/m13`."
  [v]
  (first (filter #(= v (name %)) juminzei/month-keys)))

(defn- notice-from-form
  "The form as `payroll.juminzei/admit-notice`'s argument, or an error naming
  the field that could not be read.

  ## What this function does NOT do

  It does not filter employer-naming keys out of the body. It builds the map
  key by key from named fields, so there is no path by which one could arrive
  — and `payroll.juminzei/admit-registration` refuses them anyway, which is
  the check that has to hold because it is the one the API would need too.
  Filtering here would make the refusal untestable from this direction and
  leave the real gate unexercised.

  It does not default anything. An empty 通知の種類 select is nil and is
  refused by `admit-notice`; it is not 決定通知書. A blank 月割額 is OMITTED
  from `:notice/months` rather than written as 0, so a decision notice missing
  a month is refused for missing it — a zero would be a lawful-looking
  deduction of nothing that the municipality never decided.

  It does not turn a malformed number into a number. `28,000` and `八千` are
  `::bad` and are refused by name in Japanese."
  [f]
  (let [revision (->count (get f "revision"))
        annual (->yen (get f "annual-total"))
        from-raw (blank->nil (get f "effective-from"))
        from (cond (nil? from-raw) nil
                   (notice-month-key from-raw) (notice-month-key from-raw)
                   :else ::bad)
        months (for [k juminzei/month-keys] [k (->yen (get f (name k)))])
        bad-months (vec (for [[k v] months :when (= ::bad v)] k))]
    (cond
      (= ::bad revision)
      {:error "改訂番号が非負の整数ではない"}

      (= ::bad annual)
      {:error "年税額が非負の整数ではない"}

      (= ::bad from)
      {:error (str "変更の適用開始月が、6月から翌年5月までの"
                   "十二の徴収月のいずれでもない: " (pr-str from-raw))}

      (seq bad-months)
      {:error (str "月割額が非負の整数ではない: "
                   (str/join "、" (map name bad-months)))}

      :else
      {:record {:notice/kind (case (blank->nil (get f "kind"))
                               "decision" :notice/decision
                               "revision" :notice/revision
                               nil)
                :notice/municipality (blank->nil (get f "municipality"))
                :notice/tax-year (blank->nil (get f "tax-year"))
                :notice/reference (blank->nil (get f "reference"))
                :notice/revision revision
                :notice/replaces (blank->nil (get f "replaces"))
                :notice/designated-number (blank->nil (get f "designated-number"))
                :notice/months (into {} (for [[k v] months :when (some? v)]
                                          [k v]))
                :notice/annual-total annual
                :notice/effective-from from
                :notice/registered-at (blank->nil (get f "registered-at"))}})))

(defn register-juminzei-notice!
  "`POST /console/juminzei-notice`. Admits through `payroll.juminzei` or
  refuses; a refusal writes nothing.

  ## Why this one redirects and the other registrations do not

  A notice is twelve figures somebody read off a piece of paper, so the two
  failure modes are asymmetric. A refusal must NOT redirect: the transcription
  is in the form, a redirect discards it, and an operator who has lost twelve
  figures twice starts keeping them in a text file — which is the payroll data
  this repository spends `payroll.sensitive` keeping out of exactly that kind
  of place. A success must redirect, because a 200 on a POST is a page whose
  reload re-submits, and re-submitting a notice is how one paper becomes two
  entries under one `notice-id`.

  So: `:ok` and `:duplicate` return a `:redirect`, and every refusal returns a
  flash and the form back.

  ## Nothing transcribed goes in the redirect target

  `?notice=registered` and `?notice=duplicate` and nothing else — not the
  municipality, not the 通知書番号, not a figure. A query string is in the
  browser's history, in whatever proxy is in front of this, in the referrer of
  the next request and in any screenshot of the address bar, and none of those
  are places anybody chose to put an employer's tax paperwork. What was
  registered is read BACK out of the store by the page the redirect lands on."
  [{:keys [store client-id form]}]
  (let [{:keys [record error]} (notice-from-form form)]
    (if error
      {:flash {:kind :error :message error} :form form}
      (let [r (juminzei/register-notice! store {:employer client-id
                                                :notice record})]
        (case (:registration/status r)
          :ok {:redirect (str operations-path "?notice=registered")}
          :duplicate {:redirect (str operations-path "?notice=duplicate")}
          {:flash {:kind :error
                   :message (str "登録を拒否した: " (:registration/why r))}
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

      ;; ---- 全銀 総合振込 --------------------------------------------
      ;; A SEPARATE kind from `bank-transfer`, which is this repository's own
      ;; non-standard CSV. Folding the two together would put a file a bank
      ;; parses and a file nobody has ever parsed behind one word.
      ;;
      ;; The two formats are served differently on purpose:
      ;;   `fixed-width` is Shift_JIS BYTES with a charset on the content
      ;;                 type, because a 120-byte record survives exactly one
      ;;                 encoding and it is not this console's
      ;;   `csv`         is text, and stays text — the CSV variant is
      ;;                 unpadded and its trailer is a different record
      ;;                 (118 rather than 120), so it is not the same file
      ;;                 rendered another way
      (= kind "zengin")
      (let [prepared (zengin/prepare
                      {:employer employer :period period
                       :runs (for [r runs]
                               {:contract (first (filter
                                                  #(= (:contract-id r)
                                                       (:contract/id %))
                                                  (:contracts ctx)))
                                :meisai (:meisai r)})})]
        (case fmt
          "fixed-width"
          (let [d (zengin/download prepared)]
            (if (= :ok (:download/status d))
              {:status 200
               :content-type (:download/content-type d)
               :body (:download/bytes d)
               :filename (:download/filename d)}
              (bad (str "全銀の固定長ファイルは出力できない: "
                        (or (:download/why d) (:zengin/why prepared))))))

          "csv"
          (if-let [csv (zengin/->csv prepared)]
            (text "text/csv; charset=utf-8" csv zengin/csv-filename)
            (bad (str "全銀の CSV は出力できない: " (:zengin/why prepared))))

          "json" (text "application/json; charset=utf-8"
                       (zengin/->json prepared) "zengin.json")

          (bad "全銀 総合振込は fixed-width、csv または json でのみ出力する")))

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
           advisor extras]}
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
                :durability durability :store-mode store-mode
                ;; the host's measurements, passed through untouched. The
                ;; console adds nothing to them and defaults none of them.
                :extras extras}
          show (fn [view extra]
                 (page (merge base {:view view} extra)))]
      (cond
        (and (= :get method) (contains? console-paths path))
        ;; `?notice=…` is the OTHER half of the Post/Redirect/Get: the POST
        ;; answers 303 and this GET is where the operator lands. It is turned
        ;; into a flash AND passed to the view, from one map in
        ;; `payroll.ui.views` — the flash is what a screen reader announces at
        ;; the top of the document, and the banner is what is still on the
        ;; screen after a scroll, next to the counts it reads back.
        ;;
        ;; Only on the operations screen: `?notice=` on any other page is a
        ;; query parameter that page does not have, and answering it with a
        ;; confirmation banner would confirm something on a screen that does
        ;; not register anything.
        (let [v (get views/by-path path)
              c (when (= :operations (:view/key v))
                  (get views/notice-confirmations (get query "notice")))]
          (show (:view/key v)
                {:form query
                 :notice-confirmation c
                 :flash (when c {:kind :ok
                                 :message (str (:confirmation/label c) "。"
                                               (:confirmation/message c))})}))

        (= path views/export-path)
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

        (and (= :post method) (= path views/notice-path))
        (let [{:keys [flash form redirect]} (register-juminzei-notice!
                                             (assoc base :form (or form {})))]
          (if redirect
            ;; 303 and not 302: the browser must follow it with GET whatever
            ;; the original method was. A reload of the landing page then
            ;; re-reads the store rather than re-registering the notice.
            {:status 303
             :content-type "text/plain; charset=utf-8"
             :location redirect
             :body "登録の結果は 運用の現況 の画面にある"}
            ;; and a REFUSAL is not a redirect. The transcription is in
            ;; `form`, and it goes back onto the screen with the reason.
            (show :operations {:flash flash
                               :form (into {} (for [[k v] form]
                                                [(keyword k) v]))})))

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
