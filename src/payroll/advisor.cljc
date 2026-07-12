(ns payroll.advisor
  "PayrollAdvisor — proposes a payroll operation (draft a payroll run
  from a registered contract + timesheets, reconcile timesheets,
  disburse wages) for a registered employer. Swappable: `mock-advisor`
  (deterministic, default) or `llm-advisor`. Either way the advisor
  ONLY produces a PROPOSAL; `payroll.governor` independently recomputes
  the wages via `kotoba.labor` and holds any mismatch. Modeled on
  cloud-itonami-isco-4311's bookkeeping.advisor.

  A proposal is a map:
    {:op :draft-payroll-run|:reconcile-timesheets|:disburse-wages
     :effect :propose
     :contract-id str-or-nil
     :period str
     :gross n :deductions n :net n
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}"
  (:require [kotoba.labor :as labor]
            [payroll.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference. For :draft-payroll-run the honest
  advisor computes gross via kotoba.labor from the registered contract
  and timesheets (an LLM advisor would do the same tool-call); the
  governor recomputes independently either way."
  [store {:keys [op stake contract-id period deductions] :as request}]
  (let [base {:op op
              :effect :propose
              :stake (or stake :low)
              :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
              :rationale (str "proposed " (name op) " for employer " (:client-id request))}]
    (if (= :draft-payroll-run op)
      (let [c (store/contract-of store contract-id)
            gross (when c (labor/wages-for c (store/timesheets-of store (:contract/worker c))))
            ded (or deductions 0)]
        (assoc base
               :contract-id contract-id
               :period period
               :gross gross
               :deductions ded
               :net (when gross (- gross ded))))
      base)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a payroll advisor. Given an operation request, propose an
   :op, the cited :contract-id, gross/deductions/net computed from the
   registered timesheets, an honest :confidence and a :stake. Never
   invent hours, contracts or amounts.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`; decoupled from any concrete
  model beyond the protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
