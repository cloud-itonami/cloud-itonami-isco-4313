(ns payroll.governor
  "PayrollGovernor — the independent safety/traceability layer for the
  ISCO-08 4313 community payroll actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor, with the payroll-
  specific twist that the governor RECOMPUTES wages deterministically
  via `kotoba.labor` — the advisor's arithmetic is never trusted.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance    — the request's employer must be registered.
    2. no-actuation         — proposal :effect must be :propose.
    3. contract basis       — a :draft-payroll-run must cite a
                              REGISTERED contract (no invented
                              employment), belonging to this employer,
                              and valid per kotoba.labor/validate-contract.
    4. wage integrity       — the proposal's :gross must EQUAL
                              kotoba.labor/wages-for recomputed from the
                              registered timesheets, and :net must equal
                              gross − deductions. Fair pay is arithmetic,
                              not opinion: a human approver cannot
                              approve their way past a wage mismatch.
  ESCALATION invariants (:escalate? true, human sign-off):
    5. :op :disburse-wages  (real fund movement — always human).
    6. low confidence (< `confidence-floor`)."
  (:require [kotoba.labor :as labor]
            [payroll.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:disburse-wages})

(defn- hard-violations [{:keys [request proposal]} client-record contract-record store]
  (let [{:keys [op contract-id gross deductions net]} proposal
        draft? (= :draft-payroll-run op)
        validation (when contract-record (labor/validate-contract contract-record))]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 employer"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and draft? (nil? contract-id))
      (conj {:rule :no-contract :detail "payroll run は雇用契約の引用が必須（雇用の捏造禁止）"})

      (and draft? contract-id (nil? contract-record))
      (conj {:rule :unknown-contract :detail (str "未登録の契約: " contract-id)})

      (and draft? contract-record
           (not= (:contract/employer contract-record) (:client-id request)))
      (conj {:rule :contract-wrong-employer :detail "契約が別 employer のもの"})

      (and draft? contract-record validation (not (:labor/valid? validation)))
      (conj {:rule :invalid-contract :detail (str "契約が不正: " (:labor/error validation))})

      (and draft? contract-record validation (:labor/valid? validation)
           (let [expected (labor/wages-for contract-record
                                           (store/timesheets-of store (:contract/worker contract-record)))]
             (not= expected gross)))
      (conj {:rule :wage-mismatch
             :detail (str "gross " gross " ≠ 台帳 timesheet からの再計算値 "
                          (labor/wages-for contract-record
                                           (store/timesheets-of store (:contract/worker contract-record))))})

      (and draft? gross net (not= net (- gross (or deductions 0))))
      (conj {:rule :net-mismatch
             :detail (str "net " net " ≠ gross − deductions = " (- gross (or deductions 0)))}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `payroll.store/Store`. Pure — never mutates the
  store. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        contract-record (some->> (:contract-id proposal) (store/contract-of store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record contract-record store)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
