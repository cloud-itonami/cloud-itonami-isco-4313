(ns payroll.handoff
  "受領記録 — what the ledger actor did with an entry this actor handed it.

  `payroll.shiwake` turns an approved payroll run into the `:draft-entry`
  request `cloud-itonami-isco-4311` accepts. **Converted is not posted.**
  The ledger actor can post it, find it already there, hold it against a
  rule, park it for a human signature, or refuse the request outright —
  and until this namespace existed all five looked identical from here,
  because nothing recorded any of them.

  That is this plane's recurring defect: a step that cannot fail visibly.
  A handoff whose only trace is the request implies success by having no
  other trace, and the question the loop exists to answer — *was this run
  posted?* — had nowhere to be answered.

  ## Every outcome is a fact, including the good one

  A ledger that recorded only refusals could not answer that question
  either; it could only answer *what went wrong*, which is a different
  question and not the one anybody asks first. So `handoff-fact` returns a
  fact for every response, and the caller appends it with the store's
  existing `append-ledger!`.

  ## `:duplicate` is not `:posted`

  One wrote; one confirmed something already there. To whoever is
  reconciling wages against a ledger those are different events — a
  `:duplicate` means the wage was already recorded, possibly by a carrier
  nobody here knows about, and a run showing two `:posted` facts is a
  double payment while a run showing `:posted` then `:duplicate` is a safe
  retry. Folding them loses precisely that distinction.

  ## An unrecognised shape is never a success

  A response this namespace does not understand becomes
  `:unknown-response` carrying an excerpt of what actually arrived — never
  `:posted`. Defaulting the unknown to the good outcome is how a ledger
  fills with postings that were never made. A `200` that does not say
  `:ok true`, or that carries no boolean `:duplicate?`, is one of these:
  without that flag `wrote` and `found already there` cannot be told
  apart, and guessing either way would fabricate the distinction rather
  than record it.

  ## It makes no call

  It interprets a response something else obtained: no HTTP, no client, no
  store — asserted by a test that scans this namespace's own source, the
  same discipline `payroll.shiwake` keeps. This actor proposes and records;
  writing into another actor's ledger, or reading it, would be the
  actuation the design refuses."
  (:require [clojure.string :as str]))

(def outcomes
  "Every outcome a fact can name.

  The first five are the ledger actor's own vocabulary, reported by its
  batch route verbatim. `:unknown-response` is this namespace's, and is
  what anything else becomes — it exists so that `we could not read the
  answer` has a name of its own instead of borrowing one of the five."
  #{:posted :duplicate :held :awaiting-approval :rejected :unknown-response})

(def ^:private excerpt-limit
  "How much of an unreadable response a fact carries. Bounded because a
  ledger is append-only and a hostile or merely enormous body would
  otherwise be permanent; long enough that the status and the first keys —
  which is where the disagreement always is — survive."
  500)

(defn- excerpt [x]
  (let [s (pr-str x)]
    (if (<= (count s) excerpt-limit)
      {:handoff/response s}
      {:handoff/response (str (subs s 0 excerpt-limit) "…")
       :handoff/response-truncated? true})))

(defn- nothing? [x] (str/blank? (str x)))

(defn- identify
  "Stamp the fact with whose payroll it is, which contract it cited and
  which period — the same three keys `payroll.actor/identify` stamps,
  because a reconciliation record that cannot be joined back to the thing
  it reconciles is not one. `payroll.store/run-history` and `ledger-of`
  read exactly these.

  `submission` may be a `payroll.shiwake/entry-requests` `:ok` entry with
  the employer assoc'd on, in which case the contract and period are read
  off the run it carries; or it may state them itself.

  A submission that identifies neither an employer nor a contract still
  produces a fact — losing the outcome would be worse than recording an
  unjoinable one — but the fact says `:handoff/unidentified? true` rather
  than leaving a reader to notice the nils."
  [submission]
  (let [run (get-in submission [:shiwake/run :run])
        request (:shiwake/request submission)
        client (:client-id submission)
        contract (first (remove nothing? [(:contract-id submission)
                                          (:contract-id run)
                                          (:source-doc request)]))
        period (first (remove nothing? [(:period submission) (:period run)]))]
    (cond-> {:client-id client :contract-id contract :period period}
      (or (nothing? client) (nothing? contract))
      (assoc :handoff/unidentified? true))))

(defn- stamp
  "The identity half of a fact: the three-key stamp plus the source
  document the request actually cited. Both are kept — the stamp is how
  the ledger is read back, the source-doc is what the ledger actor was
  answering about, and a disagreement between them is worth being able to
  see."
  [submission]
  (merge {:disposition :handoff}
         (identify submission)
         (when-let [sd (:source-doc (:shiwake/request submission))]
           {:handoff/source-doc sd})))

;; ---------------------------------------------------------------------------
;; One response
;; ---------------------------------------------------------------------------

(defn- interpret
  "One `POST /api/entry` response as the outcome half of a fact.

      200 :ok true :duplicate? false   -> :posted
      200 :ok true :duplicate? true    -> :duplicate
      202                              -> :awaiting-approval
      409                              -> :held, with the violations
      400 / 403 / 503                  -> :rejected, with the error
      anything else                    -> :unknown-response, with an excerpt"
  [response]
  (let [status (:status response)
        body (:body response)
        m (when (map? body) body)]
    (cond
      (not (and (map? response) (integer? status) (some? m)))
      (merge {:handoff/outcome :unknown-response
              :handoff/why (str "not a response this actor can read: expected a map "
                                "carrying an integer :status and a map :body")}
             (excerpt response))

      (= 200 status)
      (cond
        (not (true? (:ok m)))
        (merge {:handoff/outcome :unknown-response
                :handoff/why "200 whose body does not say :ok true"}
               (excerpt response))

        (not (boolean? (:duplicate? m)))
        (merge {:handoff/outcome :unknown-response
                :handoff/why (str "200 carrying no boolean :duplicate?, so `wrote` and "
                                  "`found already there` cannot be told apart")}
               (excerpt response))

        :else
        (cond-> {:handoff/outcome (if (:duplicate? m) :duplicate :posted)}
          (some? (:posting m)) (assoc :handoff/posting (:posting m))
          ;; The ledger actor reports this rather than omitting it, and so
          ;; does this fact: an entry that committed without producing a
          ;; posting is exactly what somebody has to be able to see.
          (nil? (:posting m)) (assoc :handoff/why "committed without producing a posting")))

      (= 202 status)
      (cond-> {:handoff/outcome :awaiting-approval}
        (some? (:reason m)) (assoc :handoff/reason (:reason m)))

      (= 409 status)
      ;; An empty vector means the refusal named no rule, which is itself
      ;; worth recording; it does not mean there was no refusal.
      {:handoff/outcome :held
       :handoff/violations (vec (:violations m))}

      (contains? #{400 403 503} status)
      (cond-> {:handoff/outcome :rejected}
        (some? (:error m)) (assoc :handoff/error (:error m))
        (some? (:hint m)) (assoc :handoff/hint (:hint m))
        (nil? (:error m)) (merge (excerpt response)))

      :else
      (merge {:handoff/outcome :unknown-response
              :handoff/why (str "unrecognised status " (pr-str status))}
             (excerpt response)))))

(defn handoff-fact
  "One ledger-actor response as one ledger fact, ready for
  `payroll.store/append-ledger!`.

      {:disposition :handoff
       :client-id … :contract-id … :period …   ← how it is read back
       :handoff/source-doc …                   ← what was submitted
       :handoff/status 200
       :handoff/outcome :posted
       :handoff/posting \"…\"}

  `:disposition` is `:handoff` and never one of the actor's own three, so
  a reader counting commits does not count handoffs; the question this
  fact answers is asked of `:handoff/outcome`.

  Makes no call — `response` is a `{:status :body}` map something else
  obtained."
  [submission response]
  (merge (stamp submission)
         (when (integer? (:status response)) {:handoff/status (:status response)})
         (interpret response)))

;; ---------------------------------------------------------------------------
;; A batch
;; ---------------------------------------------------------------------------

(def ^:private outcome-agrees-with-status?
  "The status each named outcome must have arrived with. The ledger actor
  derives one from the other, so they always agree there — checking it
  here is what catches a response mangled, proxied or invented between the
  two actors."
  {:posted #(= 200 %)
   :duplicate #(= 200 %)
   :awaiting-approval #(= 202 %)
   :held #(= 409 %)
   :rejected #(and (integer? %) (not (contains? #{200 202 409} %)))})

(defn- interpret-result
  "One entry of a `207` `:results` vector as the outcome half of a fact.

  The ledger actor names the outcome itself here, so this trusts the name
  — but only after checking it is one of the five and that it agrees with
  the status it arrived with. An outcome this actor does not know becomes
  `:unknown-response`, not a sixth thing quietly recorded as if understood."
  [result]
  (let [outcome (:outcome result)
        agrees? (get outcome-agrees-with-status? outcome)]
    (cond
      (not (map? result))
      (merge {:handoff/outcome :unknown-response
              :handoff/why "batch result is not a map"}
             (excerpt result))

      (nil? agrees?)
      (merge {:handoff/outcome :unknown-response
              :handoff/why (str "batch result names an outcome this actor does not "
                                "know: " (pr-str outcome))}
             (excerpt result))

      (not (agrees? (:status result)))
      (merge {:handoff/outcome :unknown-response
              :handoff/why (str "batch result says " outcome " with status "
                                (pr-str (:status result)) ", which do not agree")}
             (excerpt result))

      :else
      (cond-> {:handoff/outcome outcome}
        (some? (:posting result)) (assoc :handoff/posting (:posting result))
        (= :held outcome) (assoc :handoff/violations (vec (:violations result)))
        (some? (:error result)) (assoc :handoff/error (:error result))))))

(defn- mispairing
  "The first position whose answer cites a different source document than
  the submission at that position — direct evidence that pairing by
  position is wrong, which a count alone cannot give."
  [submissions results]
  (first
   (keep-indexed
    (fn [i [s r]]
      (let [submitted (:source-doc (:shiwake/request s))
            answered (:source-doc r)]
        (when (and (some? submitted) (some? answered) (not= submitted answered))
          {:index i :submitted submitted :answered answered})))
    (map vector submissions results))))

(defn handoff-facts
  "A whole `POST /api/entries` response as one fact per submission.

      {:handoff/status :ok               :handoff/facts [...]}
      {:handoff/status :batch-refused    :handoff/facts [...]}  400 / 403 / 503
      {:handoff/status :unknown-response :handoff/facts [...]}
      {:handoff/status :length-mismatch}                        no facts
      {:handoff/status :source-doc-mismatch}                    no facts

  ## Pairing, and refusing to guess at it

  Results come back in submission order, so position is the only thing
  joining an outcome to the run it belongs to. **If the counts differ,
  something is already wrong and pairing by position would misattribute
  every outcome after the gap** — one employer's hold recorded against
  another employer's run. So a mismatch is refused outright rather than
  zipped: no facts, and a status naming the two counts.

  The same refusal fires when a result cites a different source document
  than the submission at its position. That is stronger evidence than the
  count, because counts can agree while the order does not.

  A refusal produces NO facts, which is the one place this namespace
  deliberately records nothing: a fact attributed to the wrong run is
  worse than an absent one, because it is wrong in a way that reads as
  settled.

  ## A batch refused before it was read

  `400`, `403` and `503` refuse the whole batch, so no entry was looked at.
  Every submission still gets a fact — otherwise the runs are invisible
  again, which is the defect this namespace exists to close — carrying
  `:handoff/scope :batch` so that `the ledger actor refused this entry` and
  `the ledger actor never saw this entry` stay distinguishable.

  Makes no call."
  [submissions batch-response]
  (let [subs (vec submissions)
        status (:status batch-response)
        body (:body batch-response)
        results (:results body)]
    (cond
      (and (= 207 status) (vector? results))
      (let [mismatch (mispairing subs results)]
        (cond
          (not= (count subs) (count results))
          {:handoff/status :length-mismatch
           :handoff/submitted (count subs)
           :handoff/answered (count results)
           :handoff/why (str "the ledger actor answered " (count results) " of "
                             (count subs) " submissions; pairing by position "
                             "would misattribute every outcome")}

          (some? mismatch)
          {:handoff/status :source-doc-mismatch
           :handoff/mismatch mismatch
           :handoff/why (str "result " (:index mismatch) " answers source-doc "
                             (pr-str (:answered mismatch)) " but that position "
                             "submitted " (pr-str (:submitted mismatch)))}

          :else
          {:handoff/status :ok
           :handoff/facts (mapv (fn [s r]
                                  (merge (stamp s)
                                         (when (integer? (:status r))
                                           {:handoff/status (:status r)})
                                         (interpret-result r)))
                                subs results)}))

      (and (contains? #{400 403 503} status) (map? body))
      {:handoff/status :batch-refused
       :handoff/why (:error body)
       :handoff/facts (mapv (fn [s]
                              (merge (stamp s)
                                     {:handoff/status status
                                      :handoff/scope :batch
                                      :handoff/outcome :rejected}
                                     (when (some? (:error body))
                                       {:handoff/error (:error body)})))
                            subs)}

      :else
      (merge {:handoff/status :unknown-response
              :handoff/facts (mapv (fn [s]
                                     (merge (stamp s)
                                            {:handoff/scope :batch
                                             :handoff/outcome :unknown-response
                                             :handoff/why (str "the batch response is not "
                                                               "one this actor can read")}
                                            (excerpt batch-response)))
                                   subs)}
             (excerpt batch-response)))))
