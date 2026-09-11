(ns payroll.actor
  "PayrollActor — the ISCO-08 4313 community payroll actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors
  section). One graph run = one payroll operation request
  (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. Modeled on cloud-itonami-isco-2411's
  accounting.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                        (:ok? true)
                                            +-> :escalate -> :request-approval (:escalate?, interrupt-before)
                                            +-> :hold                          (:hard? true)
  ```

  The unconditional invariant: the PayrollAdvisor can never
  directly commit a record the PayrollClerksGovernor refuses —
  every commit-record! call is gated behind `:decide`.

  `:escalate` exists so that `awaiting a human signature` leaves a trace.
  The interrupt fires BEFORE `:request-approval`, so a node that wrote the
  ledger there would never run for a pending run — and a pending
  `:disburse-wages` would then be indistinguishable, in the only durable
  record this actor keeps, from a request nobody ever made.

  Every ledger entry is stamped with the employer, the cited contract and
  the period (`identify`). Without that stamp the ledger cannot be read
  back per employer, and a surface that served it would serve every
  employer's payroll to whoever held one valid credential."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [payroll.advisor :as advisor]
            [payroll.governor :as governor]
            [payroll.store :as store]))

(defn- identify
  "Stamp a ledger entry with whose payroll it is, which contract it cited and
  which period — read off the REQUEST, which is where the employer id is
  established (the edge takes it from the verified DID), never off the
  proposal, which an advisor writes.

  A held run may cite no contract at all; `:contract-id` is then nil and
  `payroll.store/run-history` deliberately refuses to match nil against nil.

  `:year` is carried when the request has one, which is what
  `:assess-year-end-adjustment` is identified by. That op has no `:period`,
  so without this a 年末調整 entry in the ledger could not name the year it
  assessed — and an assessment nobody can attribute to a year is not one.
  The key is absent rather than nil on the ops that have no year, so a reader
  counting years is not handed one."
  [request entry]
  (cond-> (assoc entry
                 :client-id (:client-id request)
                 :contract-id (:contract-id request)
                 :period (:period request))
    (some? (:year request)) (assoc :year (:year request))))

(defn build-graph
  "Build a compiled PayrollActor graph. `store` implements
  `payroll.store/Store`. `advisor` implements
  `payroll.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :escalate
                   (fn [{:keys [request proposal verdict]}]
                     (store/append-ledger!
                      store (identify request {:disposition :request-approval
                                               :proposal proposal
                                               :verdict verdict}))
                     {:audit [{:node :escalate :verdict verdict}]}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal verdict]}]
                     (let [record {:client-id (:client-id request)
                                    :op (:op proposal)
                                    :contract-id (:contract-id proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       ;; the ledger carries the VERDICT and not just the
                       ;; record: `what was paid` without `what was checked`
                       ;; cannot be audited afterwards, and the withholding
                       ;; coverage — `nobody looked` vs `we looked` — lives
                       ;; only in the verdict.
                       (store/append-ledger!
                        store (identify request {:disposition :commit
                                                 :record record
                                                 :verdict verdict}))
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [request proposal verdict]}]
                     ;; the PROPOSAL is on the hold entry, and it was not
                     ;; before 2026-08-25. A committed run keeps its figures
                     ;; in `:record`; a held one kept none, so the only
                     ;; durable record of a refused run said WHY it was
                     ;; refused and not WHAT was refused. `why was this run
                     ;; not paid` — the question this ledger exists to answer
                     ;; — cannot be answered by a verdict alone when the
                     ;; violation is about an amount, and an operator
                     ;; reopening a held month had to reconstruct the figures
                     ;; from a form they had already navigated away from.
                     ;;
                     ;; It is `:proposal` and NOT `:record`, so nothing that
                     ;; counts committed records counts a held one — the same
                     ;; separation `payroll.handoff` keeps by stamping
                     ;; `:disposition :handoff`.
                     (store/append-ledger!
                      store (identify request {:disposition :hold
                                               :proposal proposal
                                               :verdict verdict}))
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :escalate
           :hold)))
      (g/add-edge :escalate :request-approval)
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
