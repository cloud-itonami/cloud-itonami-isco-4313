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
         |:assess-year-end-adjustment
     :effect :propose
     :contract-id str-or-nil
     :period str
     :gross n :deductions n :net n
     :income-tax-withheld n-or-nil
     :health-insurance-withheld       n-or-nil
     :care-insurance-withheld         n-or-nil
     :employees-pension-withheld      n-or-nil
     :employment-insurance-withheld   n-or-nil
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}

  `:income-tax-withheld` is carried through from the request, never
  computed. The advisor does not know the tax tables and neither does the
  governor — `kotoba.taxlaw` read 所得税法 第百八十三条第一項 but not
  別表第二 / 別表第五, so what is gated is that the run ACCOUNTS for withheld
  income tax, not that the amount is right. An advisor that invented a
  plausible figure here would be inventing exactly the thing nothing
  downstream can check.

  ## The four 社会保険 amounts are carried through the same way, and are FOUR

  `:health-insurance-withheld`, `:care-insurance-withheld`,
  `:employees-pension-withheld` and `:employment-insurance-withheld` are
  carried from the request and computed by nobody here. They are four fields
  and not one, because 雇用保険料 is computed on a different base from the
  other three (賃金, not 標準報酬月額) and the employee's share of it is not
  half — 労働保険徴収法 第三十一条第一項第一号. A single combined number
  could not be checked against either rule. See `payroll.shakai-hoken`.

  Only ONE of the four has a rate this workspace read from a statute
  (厚生年金保険法 第八十一条第四項), and the governor bounds that one against
  the registered 標準報酬月額. The advisor still does not compute it: an
  advisor that produced the figure the governor checks would be marking its
  own work.

  ## `:assess-year-end-adjustment` carries nothing at all

  The advisor produces only the base proposal for that op — no contract, no
  year, none of the three facts 所得税法 第百九十条 turns on. Those are read
  by `payroll.governor` off the REGISTERED contract and off the REQUEST,
  never off the proposal, so an advisor cannot choose whose 年末調整 is looked
  at nor whether the 申告書 was filed. This is not an omission to be filled in
  later: `payroll.governor-test/an-advisor-cannot-move-the-year-end-answer`
  pins that an advisor emitting all of those keys changes the answer by
  nothing."
  (:require [kotoba.labor :as labor]
            [payroll.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference. For :draft-payroll-run the honest
  advisor computes gross via kotoba.labor from the registered contract
  and timesheets (an LLM advisor would do the same tool-call); the
  governor recomputes independently either way."
  [store {:keys [op stake contract-id period deductions income-tax-withheld
                 health-insurance-withheld care-insurance-withheld
                 employees-pension-withheld employment-insurance-withheld]
          :as request}]
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
               ;; carried through, not invented — nil when the request did
               ;; not say, which is the case the governor holds. The same is
               ;; true of all four 社会保険 amounts below: a nil is what makes
               ;; rule 14 fire, so defaulting any of them to 0 here would
               ;; convert an unanswered question into an assertion that the
               ;; contribution was zero.
               :income-tax-withheld income-tax-withheld
               :health-insurance-withheld health-insurance-withheld
               :care-insurance-withheld care-insurance-withheld
               :employees-pension-withheld employees-pension-withheld
               :employment-insurance-withheld employment-insurance-withheld
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
    (let [p (edn/read-string content)]
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
