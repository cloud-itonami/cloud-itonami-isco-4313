(ns payroll.touroku
  "登録 — admitting the facts an operator registers, before they reach a store.

  ## Why this exists at all

  `payroll.edge.endpoints` records the rule that kept `:reconcile-timesheets`
  off the network: *opening a port to a write the safety layer has no rule
  about is a decision to make by writing the rule first, not by opening the
  port.* An operator console needs to register employers, contracts and
  timesheets, and `payroll.store`'s `register-*!` functions have no safety
  layer at all. So the rule is written first, and it lives here.

  ## This is NOT a second governor, and the difference is the point

  The governor decides about RUNS. This decides about REGISTRATIONS. The two
  must never blur, because every one of the governor's holds turns on a fact
  an operator registered, and an admission layer that was allowed to invent
  one could dissolve a hold without anybody observing anything.

  Three consequences, each of which is a rule here:

  - **Nothing is defaulted.** An absent `:employment/health-insurance-insured?`
    is admitted as ABSENT, never as `false`. `payroll.shakai-hoken` holds a run
    for an unobserved coverage flag, and a registration layer that helpfully
    filled it in with `false` would convert `nobody looked` into `not a
    被保険者` — the exact substitution the whole actor refuses.
  - **Nothing is coerced.** `\"true\"` is not `true` and `\"280000\"` is not
    `280000`. `payroll.shakai-hoken/declared` already normalises a non-boolean
    to nil, and `payroll.mf.schema/parse-yen` already refuses a string it has
    not seen; this refuses at the door as well, so the operator is TOLD rather
    than having their typo silently become an unobserved fact.
  - **Ownership comes from the verified caller.** `:contract/employer` is
    stamped, and a body naming one is REFUSED rather than dropped — the
    discipline `payroll.edge.endpoints` states for the run route, at the other
    place a caller can hand this actor an employer id.

  ## What is checked, and by whom

  Contract validity is `kotoba.labor/validate-contract`'s, not retyped here.
  The bank fields' checks are `payroll.artifact.bank-transfer`'s, for the same
  reason: the halfwidth rule and the reasons it is insufficient live where the
  transfer file is built, and a second copy here would be the one that goes
  stale. The 標準報酬月額 month format is `payroll.shakai-hoken/month?`.

  What is NEW here is only the field inventory and the refusal to default."
  (:require [clojure.string :as str]
            [kotoba.labor :as labor]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.chingin :as chingin]
            [payroll.shakai-hoken :as hoken]))

(def employer-naming-keys
  "Keys by which a registration body could try to name whose payroll this is.
  Refused outright — the employer comes from the verified caller.

  The same set `payroll.edge.endpoints` uses, plus `:contract/employer`, which
  is already in it there. Kept as its own name rather than required across, so
  the two surfaces can diverge if one ever needs to and the divergence is
  visible."
  #{:client-id :employer :employer-id :contract/employer})

;; ---------------------------------------------------------------------------
;; The field inventory
;; ---------------------------------------------------------------------------

(defn- boolean-or-absent [v] (or (nil? v) (boolean? v)))

(defn- positive-yen-or-absent [v]
  (or (nil? v) (and (number? v) (pos? v) (zero? (mod v 1)))))

(defn- non-negative-number-or-absent [v]
  (or (nil? v) (and (number? v) (not (neg? v)))))

(defn- month-or-absent [v] (or (nil? v) (hoken/month? v)))

(def contract-fields
  "Every fact that may be registered on a contract, what it is for, and what
  admits it.

  `:field/required?` is true only for what `kotoba.labor/contract` itself
  needs. Everything else is OPTIONAL and its absence is a live answer
  elsewhere — an unregistered coverage flag holds a run, an unregistered bank
  account refuses a transfer line — so demanding it here would move a refusal
  from the place that explains it to a place that does not."
  [{:field/key :contract/id :field/label "契約 ID" :field/required? true
    :field/admits string? :field/why "空でない文字列"}
   {:field/key :contract/worker :field/label "従業員" :field/required? true
    :field/admits string? :field/why "空でない文字列"}
   {:field/key :contract/role :field/label "職種" :field/required? false
    :field/admits #(or (nil? %) (string? %)) :field/why "文字列"}
   {:field/key :contract/wage-type :field/label "賃金形態" :field/required? true
    :field/admits #(contains? labor/wage-types %)
    :field/why ":hourly または :monthly"}
   {:field/key :contract/rate :field/label "賃金額" :field/required? true
    :field/admits #(and (number? %) (not (neg? %)))
    :field/why "非負の数（時給契約なら時給、月給契約なら月額）"}
   {:field/key :contract/currency :field/label "通貨" :field/required? false
    :field/admits #(or (nil? %) (string? %)) :field/why "文字列"}

   ;; 所得税法 第百八十三条第一項 の適用条件（operator が登録する事実）
   {:field/key :employment/recipient-residency :field/label "居住者区分"
    :field/required? false
    :field/admits #(contains? #{nil :resident :non-resident} %)
    :field/why ":resident / :non-resident / 未登録"
    :field/holds "未登録は「非居住者ではない」ではなく、条文の適用範囲内として扱われる"}
   {:field/key :employment/paid-in :field/label "支払地"
    :field/required? false
    :field/admits #(contains? #{nil :domestic :overseas} %)
    :field/why ":domestic / :overseas / 未登録"}

   ;; 社会保険（payroll.shakai-hoken の被保険者資格）
   {:field/key :employment/health-insurance-insured? :field/label "健康保険 被保険者"
    :field/required? false :field/admits boolean-or-absent
    :field/why "true / false / 未登録"
    :field/holds "未登録は run を保留する。「該当しない」ではない"}
   {:field/key :employment/care-insurance-second-category?
    :field/label "介護保険 第二号被保険者"
    :field/required? false :field/admits boolean-or-absent
    :field/why "true / false / 未登録"
    :field/holds (str "未登録は run を保留する。年齢からは導けない"
                      "（介護保険法 第九条第二号 は住所地も要求する）")}
   {:field/key :employment/employees-pension-insured? :field/label "厚生年金 被保険者"
    :field/required? false :field/admits boolean-or-absent
    :field/why "true / false / 未登録"
    :field/holds "未登録は run を保留する"}
   {:field/key :employment/employment-insurance-insured? :field/label "雇用保険 被保険者"
    :field/required? false :field/admits boolean-or-absent
    :field/why "true / false / 未登録"
    :field/holds "未登録は run を保留する"}
   {:field/key :employment/standard-remuneration-monthly-yen
    :field/label "標準報酬月額（円）"
    :field/required? false :field/admits positive-yen-or-absent
    :field/why "正の整数（円）/ 未登録"
    :field/holds (str "保険者等が決定する額であり、この actor は計算しない"
                      "（健保法 第四十一条第一項 / 厚年法 第二十一条第一項）")}
   {:field/key :employment/standard-remuneration-month
    :field/label "標準報酬月額の対象月"
    :field/required? false :field/admits month-or-absent
    :field/why "YYYY-MM / 未登録"
    :field/holds "条文が控除を認めるのは「前月の」標準報酬月額に係る保険料である"}

   ;; 年末調整（所得税法 第百九十条）
   {:field/key :employment/year-end-declaration-filed?
    :field/label "扶養控除等申告書の提出" :field/required? false
    :field/admits boolean-or-absent :field/why "true / false / 未登録"
    :field/holds "紙の書類であり software からは観測できない。未登録は保留"}

   ;; 賃金の基礎（payroll.chingin）
   {:field/key :contract/allowances :field/label "諸手当"
    :field/required? false :field/admits non-negative-number-or-absent
    :field/why "非負の数 / 未登録"
    :field/holds "登録すると、割増賃金の算定基礎の規則が未読なので run は保留される"}
   {:field/key :contract/commuting-allowance :field/label "通勤手当"
    :field/required? false :field/admits non-negative-number-or-absent
    :field/why "非負の数 / 未登録"
    :field/holds "登録すると、非課税限度額が未読なので run は保留される"}

   ;; 振込先（payroll.artifact.bank-transfer）
   {:field/key :bank/financial-institution-code :field/label "金融機関コード"
    :field/required? false :field/admits #(or (nil? %) (string? %))
    :field/why "文字列 / 未登録"}
   {:field/key :bank/branch-code :field/label "支店コード"
    :field/required? false :field/admits #(or (nil? %) (string? %))
    :field/why "文字列 / 未登録"}
   {:field/key :bank/account-type :field/label "預金種目"
    :field/required? false
    :field/admits #(contains? #{nil :ordinary :current} %)
    :field/why ":ordinary / :current / 未登録"}
   {:field/key :bank/account-number :field/label "口座番号"
    :field/required? false :field/admits #(or (nil? %) (string? %))
    :field/why "文字列 / 未登録"}
   {:field/key :bank/payee-name-kana :field/label "受取人名（半角カナ）"
    :field/required? false
    :field/admits #(or (nil? %) (bank/halfwidth? %))
    :field/why "半角のみ（ASCII または U+FF61-U+FF9F）/ 未登録"
    :field/holds (str "全角からの変換も漢字からの読みの推定もしない。"
                      "通帳の名義を operator が登録する")}

   ;; MoneyForward 突合
   {:field/key :mf/employee-number :field/label "MoneyForward 従業員番号"
    :field/required? false :field/admits #(or (nil? %) (string? %) (number? %))
    :field/why "文字列または数値 / 未登録"
    :field/holds "未登録だと MoneyForward の行と突合できない（氏名では照合しない）"}])

(def employer-fields
  [{:field/key :name :field/label "事業主名" :field/required? true
    :field/admits string? :field/why "空でない文字列"}
   {:field/key :jurisdiction :field/label "法域" :field/required? false
    :field/admits #(or (nil? %) (vector? %) (keyword? %))
    :field/why "[:jp] のようなベクタ、またはキーワード / 未登録"
    :field/holds (str "未登録だと源泉徴収も社会保険も一切参照されない。"
                      "それは「義務が無い」ではなく「調べていない」である")}])

(def timesheet-fields
  (into [{:field/key :ts/worker :field/label "従業員" :field/required? true
          :field/admits string? :field/why "空でない文字列"}
         {:field/key :ts/date :field/label "日付" :field/required? true
          :field/admits string? :field/why "空でない文字列"}
         {:field/key :ts/hours :field/label "労働時間" :field/required? true
          :field/admits #(and (number? %) (not (neg? %)))
          :field/why "非負の数"}
         {:field/key :ts/break :field/label "休憩" :field/required? false
          :field/admits non-negative-number-or-absent :field/why "非負の数 / 未登録"}]
        ;; the premium facts, generated from `payroll.chingin/premiums` rather
        ;; than retyped: a fact added there must be registerable here, and a
        ;; hand-kept second list is the one that goes stale.
        (for [p chingin/premiums
              :when (= :timesheet (:premium/on p))]
          {:field/key (:premium/key p)
           :field/label (:premium/label p)
           :field/required? false
           :field/admits non-negative-number-or-absent
           :field/why "非負の数 / 未登録"
           :field/holds (str "登録すると run は保留される: "
                             (:premium/provision-not-read p) " が未読")})))

;; ---------------------------------------------------------------------------
;; Admission
;; ---------------------------------------------------------------------------

(defn- blank-string? [v] (and (string? v) (str/blank? v)))

(defn- field-violations [fields m]
  (vec (for [{:field/keys [key label required? admits why]} fields
             :let [present? (contains? m key)
                   v (get m key)]
             :when (or (and required? (or (not present?) (nil? v) (blank-string? v)))
                       (and present? (not (admits v))))]
         {:touroku/key key
          :touroku/label label
          :touroku/why (if (and required? (or (not present?) (nil? v)))
                         (str label "は必須である")
                         (str label "の値 " (pr-str v) " は受け付けない（" why "）"))})))

(defn- unknown-keys
  "Keys the body carried that this layer does not know.

  REPORTED and REJECTED, not dropped. A registration carrying
  `:employment/health_insurance_insured?` — an underscore where a hyphen
  belongs — would otherwise be admitted, write a key nothing reads, and leave
  the operator looking at a screen that says the coverage is still
  unregistered while their form said it went through."
  [fields m]
  (let [known (into #{} (map :field/key) fields)]
    (vec (remove known (keys m)))))

(defn- admit
  [fields m {:keys [kind stamp]}]
  (cond
    (some #(contains? m %) employer-naming-keys)
    {:touroku/status :refused
     :touroku/kind kind
     :touroku/why (str "登録の所有者は検証済みの呼び出し元から取る。"
                       "本文で employer を名乗ることはできない")
     :touroku/violations []}

    :else
    (let [unknown (unknown-keys fields m)
          violations (field-violations fields m)]
      (cond
        (seq unknown)
        {:touroku/status :refused
         :touroku/kind kind
         :touroku/why (str "この登録層が知らないキーがある: " (pr-str unknown)
                           "。読まれないキーを黙って受け入れると、"
                           "登録したつもりの事実が登録されない")
         :touroku/unknown-keys unknown
         :touroku/violations []}

        (seq violations)
        {:touroku/status :refused
         :touroku/kind kind
         :touroku/why (str/join "、" (map :touroku/why violations))
         :touroku/violations violations}

        :else
        {:touroku/status :ok
         :touroku/kind kind
         ;; `merge` and NOT `(merge defaults m)`: nothing is filled in. The
         ;; stamp is the only thing added, and it is ownership, which the
         ;; caller may not supply.
         :touroku/record (merge m stamp)
         :touroku/violations []}))))

(defn admit-employer
  "An employer registration. `client-id` comes from the verified caller."
  [client-id m]
  (admit employer-fields m
         {:kind :employer :stamp {:client-id client-id}}))

(defn admit-contract
  "A contract registration. `:contract/employer` is stamped from the verified
  caller, and `kotoba.labor/validate-contract` gets the last word — it is the
  same function the governor's rule 3 runs, so a contract admitted here cannot
  be one the governor would later call invalid."
  [client-id m]
  (let [r (admit contract-fields m
                 {:kind :contract :stamp {:contract/employer client-id}})]
    (if (= :ok (:touroku/status r))
      (let [v (labor/validate-contract (:touroku/record r))]
        (if (:labor/valid? v)
          r
          {:touroku/status :refused
           :touroku/kind :contract
           :touroku/why (str "kotoba.labor/validate-contract が拒否した: "
                             (pr-str (:labor/error v)))
           :touroku/violations []}))
      r)))

(defn admit-timesheet
  "A timesheet entry. Admitted only for a worker who is on one of THIS
  employer's registered contracts.

  That check is here and not in the store because it is the tenant boundary
  in registration form: a timesheet is the only admissible basis for an hourly
  wage, so an entry admitted against another employer's worker would move that
  worker's gross and hold their honest run for `:wage-mismatch` — the failure
  `payroll.store`'s docstring describes, reached from the write side."
  [client-id m contracts-of-employer]
  (let [r (admit timesheet-fields m {:kind :timesheet :stamp {}})]
    (if (= :ok (:touroku/status r))
      (if (some #(= (:ts/worker m) (:contract/worker %)) contracts-of-employer)
        r
        {:touroku/status :refused
         :touroku/kind :timesheet
         :touroku/why (str "従業員 " (pr-str (:ts/worker m))
                           " は " client-id " の登録契約に居ない。"
                           "勤怠は賃金の唯一の根拠なので、"
                           "他社の従業員の勤怠を登録させない")
         :touroku/violations []})
      r)))

;; ---------------------------------------------------------------------------
;; What is still missing — the operator's checklist
;; ---------------------------------------------------------------------------

(defn registration-gaps
  "Which optional facts are not registered on this contract, and what each
  absence costs.

  This is the console's checklist and it is generated from `contract-fields`,
  so a fact added to the inventory appears in the checklist without a second
  edit. `:field/holds` is what makes it actionable: a list of unregistered
  keys is a chore, and a list of unregistered keys each saying which refusal
  it causes is a work queue in priority order."
  [contract]
  (vec (for [{:field/keys [key label required? holds]} contract-fields
             :when (and (not required?) (nil? (get contract key)))]
         {:gap/key key
          :gap/label label
          :gap/consequence (or holds
                               (str label "が未登録。"
                                    "この事実を要する出力はその分だけ出せない"))})))

(defn employer-gaps [employer]
  (vec (for [{:field/keys [key label required? holds]} employer-fields
             :when (and (not required?) (nil? (get employer key)))]
         {:gap/key key
          :gap/label label
          :gap/consequence (or holds (str label "が未登録"))})))
