(ns payroll.artifact.bank-transfer
  "振込データ — the net pay of approved runs, as something a bank can be given.

  ## What IS emitted, and what it does not claim

  A deterministic CSV in **this repository's own columns**. It is not a
  standard, it is not a bank's import format, and `:format/standard :none`
  says so in the artifact. It is useful for exactly two things: reading the
  payment run before it happens, and handing to a person who will key it in.

  The payee-field registration checks (`payee-fields`, `halfwidth?`, `payee`,
  `prepare`) live in `payroll.artifact.payee` rather than here, because
  `payroll.artifact.zengin` — the namespace that actually reads the 全銀協
  fixed-width layout — needs the same payee lines this CSV does, and this
  namespace's `zengin` below requires `payroll.artifact.zengin` back. Two
  namespaces cannot require each other, so the shared preparation sits
  beneath both instead.

  ## `zengin` is now a compatibility entry point, not a refusal

  `payroll.artifact.zengin` has read the 全銀協 total-transfer record layout
  from PayPay Bank's own published specification and can build the real
  file, given an employer, a period, a payable run and a transfer date. The
  `zengin` function below is not that: it is the per-payee bank-field
  inventory `payroll.edge.console` and the operations screen render before
  any of those exist — while an operator is still registering account
  numbers — so it keeps its old `{:runs […]}` shape and delegates the one
  check it can still make honestly to the real implementation: whether a
  registered 受取人名 survives `payroll.artifact.zengin`'s permitted-character
  rule, not merely `halfwidth?`, which was always necessary and never
  sufficient."
  (:require [clojure.string :as str]
            [payroll.artifact.payee :as payee]
            [payroll.artifact.text :as text]
            [payroll.artifact.zengin :as zengin]))

;; ---------------------------------------------------------------------------
;; The facts an operator registers — `payroll.artifact.payee`, re-exposed
;; ---------------------------------------------------------------------------

(def payee-fields payee/payee-fields)
(def account-type-labels payee/account-type-labels)
(def halfwidth-char? payee/halfwidth-char?)
(def halfwidth? payee/halfwidth?)
(def halfwidth-check-limits payee/halfwidth-check-limits)
(def payee payee/payee)
(def format-declaration payee/format-declaration)
(def prepare payee/prepare)

;; ---------------------------------------------------------------------------
;; The file
;; ---------------------------------------------------------------------------

(def columns
  [{:column/key :period :column/header "対象期間"}
   {:column/key :contract-id :column/header "雇用契約"}
   {:column/key :worker :column/header "従業員"}
   {:column/key :bank-code :column/header "金融機関コード"}
   {:column/key :branch-code :column/header "支店コード"}
   {:column/key :account-type :column/header "預金種目"}
   {:column/key :account-number :column/header "口座番号"}
   {:column/key :payee-name-kana :column/header "受取人名"}
   {:column/key :amount :column/header "振込金額"}])

(defn ->csv [{:transfer/keys [lines]}]
  (text/csv {:columns columns :rows lines}))

(defn ->json
  [{:transfer/keys [employer-id employer-name period run-count lines refused
                    total complete?]}]
  (text/json-document
   [[:document_type "bank_transfer"]
    [:format_standard "none"]
    [:format_why (:format/why format-declaration)]
    [:employer_id employer-id]
    [:employer_name employer-name]
    [:period period]
    [:runs_considered run-count]
    [:payees (count lines)]
    [:complete (boolean complete?)]
    [:total total]
    [:halfwidth_check
     (text/json-object-of
      [[:establishes (:check/establishes halfwidth-check-limits)]
       [:does_not_establish (:check/does-not-establish halfwidth-check-limits)]])]
    [:lines (vec (for [l lines]
                   (text/json-object-of
                    (for [c columns] [(:column/key c) (get l (:column/key c))]))))]
    [:refused
     (vec (for [r refused]
            (text/json-object-of
             [[:contract_id (:payee/contract-id r)]
              [:why (:payee/why r)]
              [:missing (mapv (fn [m]
                                (text/json-object-of
                                 [[:key (:missing/key m)]
                                  [:label (:missing/label m)]
                                  [:why (:missing/why m)]]))
                              (:payee/missing r))]])))]]))

;; ---------------------------------------------------------------------------
;; 全銀 — the compatibility inventory
;; ---------------------------------------------------------------------------

(defn zengin
  "The per-payee 全銀協 registration inventory `payroll.edge.console` and the
  operations screen render while an operator is still registering account
  numbers — NOT the file itself.

  `payroll.artifact.zengin/prepare` builds the real 総合振込 file, but it
  needs an employer, a period, a payable run and a transfer date. This
  function keeps the old `{:runs […]}` shape those callers already pass,
  so it cannot build bytes and does not try to. What it CAN say honestly,
  per contract:

    :zengin/registered   the payee fields that ARE registered
    :zengin/missing      the ones that are not — including a 受取人名 that
                         is registered but would not survive
                         `payroll.artifact.zengin`'s permitted-character
                         rule, not merely `halfwidth?`, which was always
                         necessary and never sufficient

  so that an operator can finish the half of the work that is theirs before
  reaching the screen that calls `payroll.artifact.zengin/prepare`."
  [{:keys [runs]}]
  {:zengin/status :unsupported
   :zengin/bytes nil
   :zengin/why
   (str "このエントリポイントは run と contract しか受け取らない。"
        "実際のファイルを組むには事業主の振込元情報（振込依頼人名・支店・"
        "口座）と振込指定日が要る —— それらは "
        "payroll.artifact.zengin/prepare が受け取る"
        "employer・period・transfer-date-mmdd に載る")
   :zengin/also-needed
   [{:needed/what "依頼人（事業主）の振込元情報・振込指定日"
     :needed/why (str "payroll.artifact.zengin/prepare の employer・period・"
                      "transfer-date-mmdd に載る。この関数は受け取らない")}]
   :zengin/per-contract
   (vec (for [{:keys [contract]} runs]
          (let [problems (vec (keep #(payee/field-problem % contract) payee-fields))
                name-kana (:bank/payee-name-kana contract)
                already-missing? (some #(= :bank/payee-name-kana (:missing/key %))
                                       problems)
                permitted-problem
                (when (and (not already-missing?) (not (zengin/permitted? name-kana)))
                  {:missing/key :bank/payee-name-kana
                   :missing/label "受取人名（半角カナ）"
                   :missing/why (str/join "、" (map :why (zengin/character-problems
                                                          name-kana)))})
                all-problems (cond-> problems permitted-problem (conj permitted-problem))]
            {:contract-id (:contract/id contract)
             :zengin/registered (vec (for [f payee-fields
                                           :when (not (some #(= (:field/key f)
                                                                (:missing/key %))
                                                            all-problems))]
                                       (:field/key f)))
             :zengin/missing (mapv :missing/key all-problems)})))})
