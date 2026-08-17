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
    5. checked jurisdiction — a :draft-payroll-run for an employer that
                              DECLARES a jurisdiction `kotoba.taxlaw` does
                              not cover is HELD. An unchecked jurisdiction
                              is a hold, not a pass.
    6. income tax withheld  — where the jurisdiction obliges the payer to
                              withhold income tax on this payment
                              (所得税法 第百八十三条第一項), a run that does
                              not account for withheld income tax is HELD.
                              A missing amount is not a zero-tax run; it is
                              an unanswered question.
  ESCALATION invariants (:escalate? true, human sign-off):
    7. :op :disburse-wages  (real fund movement — always human).
    8. low confidence (< `confidence-floor`).

  ## What is this actor's, and what is the fleet's

  Rules 1, 2, the registered-contract half of 3, and the verdict assembly
  are not payroll rules — every actor in this fleet has them, and they were
  hand-copied into 376 governors, one of which silently drifted into
  reporting a HARD violation as escalatable. They now come from
  `kotoba-lang/governor`:

    :no-client               gov/missing-subject
    :no-actuation            gov/no-actuation
    :unknown-contract        gov/unknown-scope
    :contract-wrong-employer gov/scope-owner-mismatch  (see below)

  `:contract-wrong-employer` is why that library grew a `:scope-key`. A
  `kotoba.labor` contract carries ownership as `:contract/employer`; the
  request carries `:client-id`. The shared rule previously read one key off
  both sides, so this actor could not use it and hand-rolled the comparison
  — which is the mechanism that produced the 376 copies. Generalising the
  library was the correct fix; copying the rule a 377th time was not.

  What stays here is payroll: that a run cites a contract at all, that the
  contract is valid per `kotoba.labor/validate-contract`, and the two
  arithmetic identities the governor recomputes rather than trusts.

  ## 源泉徴収 (rules 5 and 6) — and where they are NOT

  The withholding law is not this actor's either. It lives in
  `kotoba-lang/taxlaw` alongside the インボイス rules 4311 and `tehai` use,
  and it was READ before it was enforced: 所得税法 第百八十三条第一項,
  retrieved from the e-Gov law API on 2026-08-17 and quoted verbatim in
  taxlaw's catalog. taxlaw's standing rule is that a claim it enforces must be
  read, not merely cited, and this governor is the thing that acts on it.

  Both rules fire ONLY on a `:draft-payroll-run` whose EMPLOYER RECORD
  declares a `:jurisdiction`. A payroll run for an employer that declares none
  is not held — nobody asserted where these wages are paid, so no withholding
  law was consulted — but the verdict says so in `:extra`, rather than letting
  `nobody looked` and `we looked and it was fine` produce the same output.
  Widening these to every run would be a separate decision with its own
  evidence, exactly as 4311 scopes its tax rules to proposals that claim
  `:tax-treatment :input-tax-credit`.

  The jurisdiction is read off the EMPLOYER, never off the proposal, for
  4311's reason: an advisor that could pick its own jurisdiction could pick
  one whose rules it satisfies. Residency and place of payment come off the
  REGISTERED contract for the same reason — they are facts an operator
  registers, not facts a model proposes. The withheld amount is the one input
  taken from the proposal, because whether the proposal accounts for it is
  precisely the question.

  What this governor does NOT do:

    - check the AMOUNT withheld. taxlaw did not read 別表第二 / 別表第五, and
      every result it returns says `:taxlaw/amount-checked? false`. This
      governor holds a run that does not account for withholding at all; it
      does not certify one that does.
    - check 年末調整 (所得税法 第百九十条). taxlaw has read and catalogued it,
      but this actor has no year-end op and a payroll-run draft asserts
      nothing about the year's final payment. `:extra` records that this was
      not evaluated, and why — an unevaluated rule that leaves no trace is
      indistinguishable from a satisfied one."
  (:require [kotoba.labor :as labor]
            [kotoba.taxlaw :as taxlaw]
            [payroll.store :as store]
            [governor.core :as gov]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:disburse-wages})

(defn- payment-of
  "The payment record `kotoba.taxlaw` reads, assembled from what was
  REGISTERED plus the one number the proposal claims.

  `:payment-kind` defaults to `:employment-income` because a
  `:draft-payroll-run` must cite a registered `kotoba.labor` employment
  contract to get this far (see `:no-contract`), and wages under an
  employment contract are 給与等. A contract may override it — a store that
  knows better should say so rather than have this default guess for it.

  Residency and place are left nil when the contract does not declare them,
  which taxlaw treats as IN scope rather than as the article's exclusion.
  That is the conservative direction: an unstated residency has not
  established that the payment falls outside 第百八十三条第一項."
  [contract-record proposal]
  {:payment-kind (get contract-record :employment/payment-kind :employment-income)
   :recipient-residency (:employment/recipient-residency contract-record)
   :paid-in (:employment/paid-in contract-record)
   :income-tax-withheld (:income-tax-withheld proposal)})

(defn- hard-violations [{:keys [request proposal]} client-record contract-record store]
  (let [{:keys [op contract-id gross deductions net]} proposal
        draft? (= :draft-payroll-run op)
        validation (when contract-record (labor/validate-contract contract-record))
        ;; the EMPLOYER's jurisdiction, never the proposal's.
        juris (:jurisdiction client-record)
        ;; nil unless the employer declared where it pays wages — that
        ;; declaration is the asserted condition these two rules fire on.
        withholding (when (and draft? juris)
                      (taxlaw/withholding-obligation
                       juris (payment-of contract-record proposal)))]
    (gov/violations
     ;; --- the fleet's, from kotoba-lang/governor -------------------------
     (gov/missing-subject client-record {:detail "未登録 employer"})
     (gov/no-actuation proposal
                       {:detail "effect は :propose のみ許可（直接書込禁止）"})
     ;; citing no contract and citing one that does not exist are different
     ;; failures; only the second is unknown-scope.
     (gov/unknown-scope contract-record
                        {:applies? (boolean (and draft? contract-id))
                         :rule :unknown-contract
                         :detail (str "未登録の契約: " contract-id)})
     (when draft?
       (gov/scope-owner-mismatch contract-record request
                                 {:owner-key :client-id
                                  :scope-key :contract/employer
                                  :rule :contract-wrong-employer
                                  :detail "契約が別 employer のもの"}))

     ;; --- payroll's own ---------------------------------------------------
     (cond-> []
      (and draft? (nil? contract-id))
      (conj {:rule :no-contract :detail "payroll run は雇用契約の引用が必須（雇用の捏造禁止）"})

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
             :detail (str "net " net " ≠ gross − deductions = " (- gross (or deductions 0)))})

      ;; 5. the employer declared a jurisdiction nobody has catalogued. An
      ;; unchecked jurisdiction is a hold, not a pass — otherwise declaring
      ;; an unknown one would be the cheapest way to skip rule 6.
      (= :none (:taxlaw/coverage withholding))
      (conj {:rule :unchecked-jurisdiction
             :detail (str "employer が法域 " (pr-str juris)
                          " を宣言しているが kotoba.taxlaw に無い"
                          "（未検査は合格ではない）")})

      ;; 6. the jurisdiction obliges the payer to withhold income tax on this
      ;; payment, and the proposal does not account for any. 所得税法
      ;; 第百八十三条第一項: 「その支払の際、その給与等について所得税を徴収し」.
      ;; A missing withheld amount is not a zero-tax payroll run; it is an
      ;; unanswered question, and no confidence answers it.
      (and (= :checked (:taxlaw/coverage withholding))
           (false? (:taxlaw/accounted-for? withholding)))
      (conj {:rule :income-tax-not-withheld
             :detail (str (:taxlaw/provision withholding)
                          "（" (name (:taxlaw/reason withholding)) "）: "
                          "給与等の支払には源泉徴収した所得税の計上が要る。"
                          "proposal の :income-tax-withheld: "
                          (pr-str (:income-tax-withheld proposal))
                          "（納付期限 " (:taxlaw/remittance-deadline withholding) "）")})))))

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
        draft? (= :draft-payroll-run (:op proposal))
        juris (:jurisdiction client-record)]
    (gov/verdict
     {:violations hard
      :confidence (:confidence proposal)
      :escalating-op? (contains? escalating-ops (:op proposal))
      :confidence-floor confidence-floor
      ;; What taxlaw was and was NOT able to say. A run for an employer that
      ;; declares no jurisdiction is not held — nothing was asserted — but the
      ;; verdict must not look identical to one where the law was consulted
      ;; and satisfied. Same device as 4311's `:tax`, kintai's `:unevaluated`
      ;; and tehai's `:tax`.
      :extra
      (when draft?
        {:tax
         {:jurisdiction juris
          :withholding
          (if juris
            (taxlaw/withholding-obligation
             juris (payment-of contract-record proposal))
            {:taxlaw/coverage :not-declared
             :taxlaw/why (str "employer 記録に :jurisdiction が無い。"
                              "どこで支払われる給与かが宣言されていないので、"
                              "源泉徴収の法令は一切参照していない"
                              "（適用なしの判断ではない）")})
          ;; 所得税法 第百九十条 is read and catalogued in kotoba.taxlaw, and
          ;; nothing here consumes it. Recorded rather than omitted: a rule
          ;; that exists upstream and is silently never called looks exactly
          ;; like a rule that was called and passed.
          :year-end-adjustment
          {:taxlaw/coverage :not-evaluated
           :taxlaw/why (str "この actor に年末調整の op が無く、"
                            ":draft-payroll-run はその年最後の給与等の支払か"
                            "どうかを何も主張しない。所得税法 第百九十条 は"
                            "kotoba.taxlaw に読み込み済みだが、ここでは"
                            "呼んでいない")}}})})))
