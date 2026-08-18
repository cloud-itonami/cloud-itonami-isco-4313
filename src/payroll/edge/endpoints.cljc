(ns payroll.edge.endpoints
  "The HTTP surface this payroll actor exposes — exactly three routes:

      POST /api/payroll-run             draft a payroll run
      GET  /api/payroll-run/:contract-id  the whole life of one contract's runs
      GET  /api/ledger                  the caller's own slice of the ledger

  and nothing else. Per `manifest/repository-rules.edn` an itonami actor is
  `:on-demand`: it answers a request and stops.

  Portable `.cljc` request→response functions. No host effects, no framework,
  no platform types — a response is a map `{:status n :body {...}}` and the
  caller's DID arrives already verified. CACAO verification is
  `kotoba-lang/org-chainagnostic-cacao`'s job and is not reimplemented here
  (ADR-2607268000); mounting these functions on Cloudflare Pages Functions is a
  host binding this repo does not yet carry, and inventing an untested one
  would be worse than saying so.

  ## Why one write route, and why the other two ops have none

  Of this actor's three ops only `:draft-payroll-run` both needs a network path
  and is something the governor actually checks:

    :draft-payroll-run     the employer's timekeeping system has to reach the
                           actor, and a clean run auto-commits. The governor
                           recomputes the wage from the registered timesheets
                           and reads 所得税法 第百八十三条第一項 over it, so a
                           request arriving here meets six HARD rules  → exposed

    :disburse-wages        real money leaves the employer's account. It is in
                           `payroll.governor/escalating-ops`, so it ALWAYS
                           escalates and can only complete when a person
                           resumes the thread — and there is no request that
                           can substitute for that person. Putting it behind a
                           socket would mean the only thing standing between a
                           stolen credential and a payment run is that the
                           thief must also wait for a human to click. Wages
                           reach a bank because somebody signed
                                                                → NOT exposed

    :reconcile-timesheets  writes a record that the governor recomputes
                           NOTHING for: `hard-violations` gates the contract
                           basis and both arithmetic identities behind
                           `draft?`, so a reconciliation is checked only for
                           employer registration and `:effect :propose`.
                           Exposing it would put a write into the operating
                           record behind a socket that the actor's own safety
                           layer has no rule about. That is a decision to make
                           by writing the rule first, not by opening the port
                                                                → NOT exposed

  `submit-payroll-run-core!` hard-codes `:op :draft-payroll-run`. It does not
  read an op out of the body, so a request naming `:disburse-wages` drafts a
  run — it cannot reach the money-moving path by asking to.

  ## Three gates, and none of them is optional

  1. CACAO signature + temporal window — the host's, before these functions.
  2. The verified DID must be on the allow-list, which maps DID → employer id.
     **An absent allow-list serves 503, never an open endpoint.** An open
     payroll endpoint is an open write path into somebody's wage record.
  3. Reads are scoped to the caller's own employer id. Knowing a contract id is
     not enough to read another employer's payroll — the same property the
     governor's `:contract-wrong-employer` rule enforces on the write side.

  ## The employer is the DID's, and a body that names one is REFUSED

  Not ignored. Every model on this fleet's accounting plane takes the subject
  id from the verified DID and silently drops a body that names one; this
  surface returns 400 instead, because dropping it leaves a caller believing
  they wrote to an employer they did not. A payroll run posted \"for emp-2\"
  that lands under emp-1 is a wage record filed against the wrong company, and
  the response that reported it said 200.

  ## No `:ok` boolean on the run routes

  A payroll run has three outcomes and a boolean has two.
  `submit-payroll-run-core!` reports `:disposition` (`:commit` /
  `:request-approval` / `:hold`) with the HTTP status to match (200 / 202 /
  409), because collapsing `awaiting a human signature` into either `paid` or
  `refused` is a lie in whichever direction it is told. `:ok` survives only on
  the responses that are about the REQUEST rather than about the run (503 /
  403 / 400 / 404 / 405), where there is genuinely nothing three-valued to
  report.

  For the same reason every run response carries `:withholding` — the coverage
  summary of what 所得税法 could and could not say. A 200 that did not say so
  would render `we read 第百八十三条第一項 and this run satisfies it` and
  `nobody has read the withholding law where this employer pays` as the same
  green tick."
  (:require [payroll.actor :as actor]
            [payroll.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

;; ---------------------------------------------------------------------------
;; Allow-list
;; ---------------------------------------------------------------------------

(defn parse-allowlist
  "`\"did:key:z6Mk…=emp-1,did:key:z6Ml…=emp-2\"` -> `{did employer-id}`, or nil
  when absent, blank or wholly malformed — `nobody is allowed` and `nothing was
  configured` are different deployment states and get different status codes."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did emp] (map #(.trim %) (.split entry "="))]
                          (when (and did emp (seq did) (seq emp))
                            [did emp])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn employer-for [allowlist did] (get allowlist did))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `PAYROLL_STORE` env var.

    nil          nothing configured
    :ephemeral   `MemStore` — does not survive the process
    :datomic     `DatomicStore` over langchain.db

  Returns nil for anything else, including an unrecognised value — a typo in a
  deployment variable must not silently select a storage mode, and on this
  actor the mode decides whether the record of what was refused outlives the
  restart.

  Portable (takes a plain map) so the decision is testable without a platform."
  [env]
  (case (some-> (get env "PAYROLL_STORE") .trim)
    "ephemeral" :ephemeral
    "datomic" :datomic
    nil))

(defn store-for
  "A store for `mode`, or nil when nothing is configured."
  [mode]
  (case mode
    :ephemeral (store/mem-store)
    :datomic (store/datomic-store)
    nil))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store has no
  registered employer, so EVERY request fails the governor's provenance check
  and the caller is told `:no-client` — blamed for a deployment that has no
  store at all. Misattributed blame is worse than a refusal: the operator goes
  looking at their own registration while the actual fault is here."
  []
  {:status 503
   :body {:ok false :error "no store configured"
          :hint (str "set PAYROLL_STORE=datomic for the langchain.db backend,"
                     " or PAYROLL_STORE=ephemeral for a non-persisting"
                     " smoke test")}})

;; ---------------------------------------------------------------------------
;; Body parsing — structure only, plus the one thing the body may not name
;; ---------------------------------------------------------------------------

(def employer-naming-keys
  "Keys by which a body could try to name whose payroll this is. All of them
  are refused outright; the employer comes from the verified DID."
  #{:client-id :employer :employer-id :contract/employer})

(def ^:private stakes
  ;; `payroll.advisor/infer` maps stake to confidence with a `case` that has no
  ;; default, so an unrecognised stake THROWS inside the graph and a structural
  ;; error surfaces as a 500. That makes the stake vocabulary structural here.
  #{:low :medium :high})

(defn- money? [x] (and (number? x) (not (neg? x))))

(defn parse-run-body
  "EDN request body -> `{:fields {…}}`, or `{:error \"…\"}` with the reason.

  Read with `clojure.edn/read-string`, which evaluates nothing.

  This validates STRUCTURE and the ownership rule, and nothing else. Whether a
  run is admissible is the governor's question, and pre-empting it here would
  replace six cited invariants with an uncited one.

  What is structural, and why each one:

    :client-id &c  REFUSED. Whose payroll this is comes from the verified DID.
                   400 rather than a silent drop, so a caller cannot come away
                   believing they filed against another employer.
    :period        a non-blank string. Required, because a payroll run nobody
                   can name by period is a run nobody can look up afterwards,
                   and this actor will not mint an identity on the caller's
                   behalf and hand it back as though the caller had chosen it.
    :deductions    a non-negative number when present. The advisor computes
                   `(- gross (or deductions 0))`, so a string here throws
                   inside the graph.
    :stake         one of #{:low :medium :high} when present. See `stakes`.

  What is deliberately NOT structural, because the governor has a cited rule
  that answers it better than a flat 400 would:

    :contract-id           absent → `:no-contract` (雇用の捏造禁止);
                           unknown → `:unknown-contract`; another employer's
                           → `:contract-wrong-employer`. Three different
                           refusals, each naming what was wrong.
    :income-tax-withheld   a negative or non-numeric amount is not an
                           accounting, and 所得税法 第百八十三条第一項 is what
                           says so. Flattening it to 400 would lose the
                           article."
  [s]
  (let [m (try (edn/read-string s)
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (cond
      (not (map? m))
      {:error "invalid request body"}

      (some #(contains? m %) employer-naming-keys)
      {:error (str "the employer is taken from the verified caller, not the"
                   " request body")}

      (not (and (string? (:period m)) (seq (.trim (:period m)))))
      {:error "invalid request body"}

      (not (or (nil? (:deductions m)) (money? (:deductions m))))
      {:error "invalid request body"}

      (not (or (nil? (:stake m)) (contains? stakes (:stake m))))
      {:error "invalid request body"}

      :else
      {:fields (select-keys m [:contract-id :period :deductions
                               :income-tax-withheld :stake])})))

;; ---------------------------------------------------------------------------
;; What 所得税法 could and could not say
;; ---------------------------------------------------------------------------

(defn withholding-coverage
  "The verdict's `:tax` report reduced to a response body. Every value is
  descriptive and none of them reads as approval.

  `:withholding` is FOUR-VALUED where the article was reachable, plus one
  value for the ops that never reach it:

    :not-assessed  the op asserts no payment of 給与等 (or there is no verdict
                   at all), so nothing under the article was reached
    :not-declared  the employer declares no jurisdiction; **no withholding law
                   was consulted.** Not a finding that nothing is owed
    :none          the employer declares a jurisdiction nobody has catalogued;
                   the law was not read. This is a HARD hold, never a pass
    :out-of-scope  declared outside the one article that WAS read
                   (non-resident recipient, or paid abroad). The provisions
                   governing those were never read either
    :checked       第百八十三条第一項 was applied, and answered

  `:amount` stays separate and is never `:checked` today: taxlaw read
  第百八十三条第一項 but not 別表第二 / 別表第五, so a run accounting for 1 yen
  on 28,000 of wages satisfies this gate. Reporting coverage without reporting
  that would let a reader take a 200 as `the tax was right`.

  `:year-end-adjustment` is `:not-evaluated` on every draft: 所得税法
  第百九十条 is read and catalogued upstream and this actor has no year-end op.
  A rule that is silently never called looks exactly like one that was called
  and passed."
  [verdict]
  (let [tax (:tax verdict)
        w (:withholding tax)]
    {:assessed (some? tax)
     :jurisdiction (:jurisdiction tax)
     :withholding (or (:taxlaw/coverage w) :not-assessed)
     ;; taxlaw names the article it read under two DIFFERENT keys depending on
     ;; which branch answered: `:taxlaw/read-provision` when the payment fell
     ;; outside it, `:taxlaw/provision` when it fell inside and an obligation
     ;; attached. It is the same article both times, and reading only one key
     ;; would leave every `:checked` response unable to name what was read —
     ;; which is the whole point of naming it.
     :read-provision (or (:taxlaw/read-provision w) (:taxlaw/provision w))
     :amount (case (:taxlaw/amount-checked? w)
               true :checked
               false :never-certified
               :not-assessed)
     :year-end-adjustment (or (get-in tax [:year-end-adjustment :taxlaw/coverage])
                              :not-assessed)}))

(defn- violation-summary [verdict]
  (mapv #(select-keys % [:rule :detail]) (:violations verdict)))

;; ---------------------------------------------------------------------------
;; POST /api/payroll-run
;; ---------------------------------------------------------------------------

(defn submit-payroll-run-core!
  "`POST /api/payroll-run`. `caller-did` is already verified.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, a body naming an employer, no `:period`, or a
         non-numeric `:deductions` / unrecognised `:stake`
    200  committed        (`:disposition :commit`)
    202  awaiting a human (`:disposition :request-approval`) — accepted and
         recorded in the ledger, NOT paid. 202 rather than 200 because the
         run's outcome is still open, and rather than 409 because nothing was
         refused.
    409  held             (`:disposition :hold`), with the violations

  The op is `:draft-payroll-run` unconditionally and the employer comes from
  the DID. Neither is read from the body.

  The optional `advisor` is the actor's swappable `payroll.advisor/Advisor`;
  nil takes the graph's default `mock-advisor`. It is a parameter rather than a
  constant because the 202 branch is otherwise unreachable through this
  surface and would therefore be untested: the exposed op never escalates under
  the mock advisor (its lowest confidence for any stake is 0.7, above the
  governor's 0.6 floor) and `:disburse-wages` is not exposed at all. A
  deployment that swaps in `llm-advisor` reaches it on the first low-confidence
  answer, and the branch it lands in must already have been exercised."
  ([store mode allowlist caller-did raw-body]
   (submit-payroll-run-core! store mode allowlist caller-did raw-body nil))
  ([store mode allowlist caller-did raw-body advisor]
   (cond
     (nil? allowlist)
     {:status 503 :body {:ok false :error "no allow-list configured"}}

     (nil? (employer-for allowlist caller-did))
     {:status 403 :body {:ok false :error "caller not permitted"}}

     :else
     (let [{:keys [fields error]} (parse-run-body raw-body)]
       (if error
         {:status 400 :body {:ok false :error error}}
         (let [client-id (employer-for allowlist caller-did)
               g (actor/build-graph (cond-> {:store store}
                                      advisor (assoc :advisor advisor)))
               r (actor/run-request! g (assoc fields
                                              :client-id client-id
                                              :op :draft-payroll-run)
                                     {} (str "edge-" client-id "-"
                                             (:contract-id fields) "-"
                                             (:period fields)))
               verdict (get-in r [:state :verdict])
               disposition (get-in r [:state :disposition])
               base {:employer client-id
                     :contract-id (:contract-id fields)
                     :period (:period fields)
                     :store mode
                     :disposition disposition
                     :withholding (withholding-coverage verdict)}]
           (case disposition
             :commit {:status 200
                      :body (assoc base
                                   :gross (get-in r [:state :record :payload :gross])
                                   :net (get-in r [:state :record :payload :net])
                                   :income-tax-withheld
                                   (get-in r [:state :record :payload
                                              :income-tax-withheld]))}
             :request-approval {:status 202
                                :body (assoc base
                                             :awaiting :human-approval
                                             :reason (:escalation-reason verdict))}
             {:status 409
              :body (assoc base :violations (violation-summary verdict))})))))))

;; ---------------------------------------------------------------------------
;; GET /api/payroll-run/:contract-id
;; ---------------------------------------------------------------------------

(defn run-verdict-core
  "`GET /api/payroll-run/:contract-id`. Every payroll run filed against one
  contract, oldest first, with the verdict of the latest.

    503  no allow-list configured
    403  caller not on the allow-list
    400  blank contract id
    404  no run under that contract is visible to this caller
    200  the ledger entries for it, and the latest disposition

  A contract belonging to ANOTHER employer returns the same 404 as one that
  never existed, byte for byte. A 403 there would confirm the contract exists,
  and a contract id would become something a competitor can probe for — the
  employment relationships of a company are not public. The two cases are
  indistinguishable to the caller and distinguishable in the ledger, which is
  the right way round.

  An unknown contract is never an empty 200. `no runs` and `no such contract`
  are the same bytes if the answer is a list, and only one of them means this
  actor knows anything about it."
  [store allowlist caller-did contract-id]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employer-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    (not (and (string? contract-id) (seq (.trim contract-id))))
    {:status 400 :body {:ok false :error "missing contract id"}}

    :else
    (let [client-id (employer-for allowlist caller-did)
          history (filterv #(= client-id (:client-id %))
                           (store/run-history store contract-id))]
      (if (empty? history)
        {:status 404 :body {:ok false :error "no such payroll run for this caller"}}
        (let [latest (peek history)]
          {:status 200
           :body {:employer client-id
                  :contract-id contract-id
                  :disposition (:disposition latest)
                  :withholding (withholding-coverage (:verdict latest))
                  :violations (violation-summary (:verdict latest))
                  :history (mapv #(hash-map :disposition (:disposition %)
                                            :period (:period %)
                                            :gross (get-in % [:record :payload :gross]))
                                 history)}})))))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(defn ledger-core
  "`GET /api/ledger`. The caller's OWN slice of the append-only ledger, oldest
  first — every disposition, commit and escalation and hold alike.

    503  no allow-list configured
    403  caller not on the allow-list
    200  the caller's entries, possibly none

  The whole ledger has no HTTP representation. It is every employer's payroll,
  which is both their staffing and their wage bill, and an actor that hands
  that to whoever holds one valid DID has published its clients' books. An
  employer with no entries is a complete answer and gets 200 with an empty
  vector — unlike an unknown contract id, `you have filed nothing` is something
  this actor actually knows.

  `:scope` is on the body so nobody mistakes this for the whole ledger."
  [store allowlist caller-did]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (employer-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [client-id (employer-for allowlist caller-did)
          entries (store/ledger-of store client-id)]
      {:status 200
       :body {:employer client-id
              :scope :caller-only
              :count (count entries)
              :entries (mapv (fn [e]
                               {:contract-id (:contract-id e)
                                :period (:period e)
                                :disposition (:disposition e)
                                :gross (get-in e [:record :payload :gross])
                                :withholding (withholding-coverage (:verdict e))
                                :violations (violation-summary (:verdict e))})
                             entries)}})))

;; ---------------------------------------------------------------------------
;; The surface itself
;; ---------------------------------------------------------------------------

(def run-path-prefix "/api/payroll-run/")

(defn route
  "The whole surface, as data in and data out.

  `request` is `{:method :get|:post :path \"/api/…\" :body \"…\"}`. Returns
  `{:status n :body {...}}`.

  `store` and `mode` are passed IN rather than built here. A dispatcher that
  called `(store-for mode)` per request would give every read an empty store —
  the write route would work, both read routes would answer 404 and an empty
  ledger forever, and the deployment would look healthy while remembering
  nothing. The host owns the store's lifetime; this function owns the routing.

  A nil `mode` still serves 503 here rather than at the host, so the decision
  not to guess a storage backend is testable without a platform.

  Having one dispatcher rather than three exported handlers is what makes
  `there are exactly three routes` a testable claim instead of a sentence in a
  README. A path nobody declared is 404 and a method nobody declared is 405 —
  distinct, because `POST /api/ledger` is a caller using the wrong verb on a
  real route and `POST /api/disburse` is a caller inventing one that this actor
  refuses to have."
  [store mode allowlist caller-did {:keys [method path body]}]
  (if (or (nil? mode) (nil? store))
    (store-unconfigured-response)
    (cond
      (= path "/api/payroll-run")
      (if (= method :post)
        (submit-payroll-run-core! store mode allowlist caller-did body)
        {:status 405 :body {:ok false :error "method not allowed" :allow [:post]}})

      (= path "/api/ledger")
      (if (= method :get)
        (ledger-core store allowlist caller-did)
        {:status 405 :body {:ok false :error "method not allowed" :allow [:get]}})

      (and (string? path) (.startsWith path run-path-prefix))
      (if (= method :get)
        (run-verdict-core store allowlist caller-did
                          (subs path (count run-path-prefix)))
        {:status 405 :body {:ok false :error "method not allowed" :allow [:get]}})

      :else
      {:status 404 :body {:ok false :error "no such route"}})))
