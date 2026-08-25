(ns payroll.artifact.bank-transfer
  "振込データ — the net pay of approved runs, as something a bank can be given.

  ## The 全銀 fixed-width record is REFUSED, not attempted

  Japanese banks accept 総合振込 as a fixed-width record defined by the
  全国銀行協会. **This repository has not read that specification.** It is not
  a statute on the e-Gov law API; it is a banking standard, and nothing here
  has a copy.

  `zengin` therefore returns a refusal for every input, always, and
  `zengin/emit` does not exist. That is a deliberate absence and the most
  important line of code in this namespace is the one that is not written.
  A 120-byte record assembled from memory would be accepted by the file
  picker, rejected or — far worse — MISREAD by the bank, and the failure
  would land on the day wages were due. Between refusing and guessing, on a
  file that moves somebody's salary, there is no third option worth having.

  What the refusal carries instead is the **inventory**: exactly which facts
  an operator would have to register before any such file could be built, and
  which of them are already registered. An operator told `unsupported` learns
  nothing; one told `these four fields are registered and this one is not,
  and additionally the record layout has not been read` knows both what to do
  and what is still not their problem to fix.

  ## What IS emitted, and what it does not claim

  A deterministic CSV in **this repository's own columns**. It is not a
  standard, it is not a bank's import format, and `:format/standard :none`
  says so in the artifact. It is useful for exactly two things: reading the
  payment run before it happens, and handing to a person who will key it in.

  ## The halfwidth-kana check is necessary and NOT sufficient

  A 受取人名 in a 全銀 record is halfwidth. This namespace checks that a
  registered name-kana contains only characters from the Unicode halfwidth
  block and ASCII — which is a fact about Unicode, checkable without the
  banking spec — and refuses anything else.

  It does NOT transliterate. Turning 川崎 or カワサキ into ｶﾜｻｷ is a decision
  about somebody's name that this actor is not entitled to make: the reading
  of a Japanese surname is not derivable from its characters, the bank's
  record is the one the account was opened under, and a name that does not
  match is a payment that bounces or lands somewhere else. The kana is
  REGISTERED by an operator who looked at the bank book.

  And the check is explicitly incomplete: the permitted character set of the
  actual 全銀 record — which symbols, which length — is part of the spec that
  was not read. Passing this check does not mean a bank would accept the
  name. It means the name is not obviously wrong in the one way that can be
  checked from here."
  (:require [clojure.string :as str]
            [payroll.artifact.text :as text]
            [payroll.meisai :as meisai]))

;; ---------------------------------------------------------------------------
;; The facts an operator registers
;; ---------------------------------------------------------------------------

(def payee-fields
  "What a transfer needs, per employee, and where it is registered.

  All five live on the CONTRACT record, registered by an operator, for the
  reason `:employment/recipient-residency` does: a bank account is a fact
  somebody looked at, not a fact a model proposes. None of them is derivable
  from anything this actor holds.

  `:field/checkable` is the check this repository can actually perform. Where
  it is `:none`, the field is carried through unvalidated and the artifact
  says so — a format check invented for a 金融機関コード would be this
  namespace asserting a spec it has not read."
  [{:field/key :bank/financial-institution-code
    :field/label "金融機関コード"
    :field/checkable :present-only
    :field/why "桁数・体系は全銀協の仕様であり、この repository は読んでいない"}
   {:field/key :bank/branch-code
    :field/label "支店コード"
    :field/checkable :present-only
    :field/why "同上"}
   {:field/key :bank/account-type
    :field/label "預金種目"
    :field/checkable :vocabulary
    :field/vocabulary #{:ordinary :current}
    :field/why (str "普通/当座 は operator の登録値として受け取る。"
                    "全銀レコードでこれが何番のコードになるかは未読なので、"
                    "数字に変換しない")}
   {:field/key :bank/account-number
    :field/label "口座番号"
    :field/checkable :present-only
    :field/why "桁数は金融機関により異なり、その規則をこの repository は持っていない"}
   {:field/key :bank/payee-name-kana
    :field/label "受取人名（半角カナ）"
    :field/checkable :halfwidth
    :field/why (str "半角であることだけを検査する。"
                    "全角からの変換も、漢字からの読みの推定も行わない —— "
                    "氏名の読みは文字から導けず、"
                    "口座名義と一致しない振込は着金しない")}])

(def account-type-labels
  {:ordinary "普通" :current "当座"})

;; ---------------------------------------------------------------------------
;; Halfwidth
;; ---------------------------------------------------------------------------

(defn halfwidth-char?
  "Is this character halfwidth, as Unicode defines the blocks?

  ASCII (U+0020-U+007E) and the halfwidth forms block (U+FF61-U+FF9F, which
  is where ｱ ｶ ﾞ ﾟ ｰ live). Everything else — full-width katakana, kanji,
  hiragana, full-width digits, the ideographic space — is not.

  A codepoint test rather than a regex, so it behaves the same on both
  runtimes: ClojureScript regexes are JavaScript's and JavaScript's character
  classes over non-ASCII are not the JVM's."
  [ch]
  (let [c #?(:clj (int ch) :cljs (.charCodeAt ^string (str ch) 0))]
    (or (and (>= c 0x20) (<= c 0x7E))
        (and (>= c 0xFF61) (<= c 0xFF9F)))))

(defn halfwidth?
  "Is every character of `s` halfwidth? A blank string is NOT — an empty
  payee name is a missing registration, and answering `true` for it would let
  the absence pass the one check this namespace can make."
  [s]
  (boolean (and (string? s) (seq s) (every? halfwidth-char? s))))

(def halfwidth-check-limits
  "What passing `halfwidth?` does and does not establish. Emitted into the
  artifact so the limit travels with the file."
  {:check/establishes "文字がすべて半角（ASCII または U+FF61-U+FF9F）であること"
   :check/does-not-establish
   (str "銀行がこの名義を受け付けること。"
        "全銀レコードで許される文字種・桁数・記号は全銀協の仕様にあり、"
        "この repository は読んでいない。"
        "また、この名義が口座名義と一致するかどうかは"
        "operator が通帳を見て登録した事実に依存する")})

;; ---------------------------------------------------------------------------
;; One payee
;; ---------------------------------------------------------------------------

(defn- field-problem
  "What is wrong with one registered field, or nil."
  [{:field/keys [key label checkable vocabulary]} contract]
  (let [v (get contract key)]
    (cond
      (or (nil? v) (and (string? v) (str/blank? v)))
      {:missing/key key :missing/label label
       :missing/why (str label "が契約に登録されていない")}

      (and (= :vocabulary checkable) (not (contains? vocabulary v)))
      {:missing/key key :missing/label label
       :missing/why (str label "の登録値 " (pr-str v) " は "
                         (pr-str (vec (sort-by str vocabulary)))
                         " のいずれでもない")}

      (and (= :halfwidth checkable) (not (halfwidth? v)))
      {:missing/key key :missing/label label
       :missing/why (str label "が半角ではない。"
                         "この actor は全角からの変換も読みの推定も行わない —— "
                         "口座名義の読みは operator が通帳を見て登録する")})))

(defn payee
  "One approved run as a payee line, or the reasons there is none.

    {:contract  the REGISTERED contract record
     :meisai    `payroll.meisai/lines`
     :period}

  Returns `{:payee/status :ok :payee/line {…}}` or
  `{:payee/status :refused :payee/why … :payee/missing [{…}]}`.

  The order of the refusals is deliberate. `not-payable` comes FIRST, before
  any bank field is looked at, because a run that must not be paid is not a
  run with an incomplete bank registration — telling an operator to go and
  register an account number for a payment that is on hold would send them to
  do work that changes nothing."
  [{:keys [contract meisai period]}]
  (let [net (:meisai/net meisai)]
    (cond
      (not (meisai/payable? meisai))
      {:payee/status :refused
       :payee/contract-id (:contract/id contract)
       :payee/why (str "この run は支払える状態ではない（処理: "
                       (name (or (:meisai/disposition meisai) :unknown))
                       "、差引支給額の出所: "
                       (name (:figure/provenance net))
                       "）。振込データに載せてよいのは承認され、"
                       "かつ全額が確定した run だけである")
       :payee/missing []}

      :else
      (let [problems (vec (keep #(field-problem % contract) payee-fields))]
        (if (seq problems)
          {:payee/status :refused
           :payee/contract-id (:contract/id contract)
           :payee/why (str "振込先の登録が足りない: "
                           (str/join "、" (map :missing/label problems)))
           :payee/missing problems}
          {:payee/status :ok
           :payee/contract-id (:contract/id contract)
           :payee/line
           {:period period
            :contract-id (:contract/id contract)
            :worker (:contract/worker contract)
            :bank-code (:bank/financial-institution-code contract)
            :branch-code (:bank/branch-code contract)
            :account-type (get account-type-labels (:bank/account-type contract))
            :account-number (:bank/account-number contract)
            :payee-name-kana (:bank/payee-name-kana contract)
            :amount (:figure/amount net)}})))))

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

(def format-declaration
  "What this CSV is. Emitted into the JSON companion, so a consumer cannot
  take the file for a bank format because it has the right columns."
  {:format/standard :none
   :format/why
   (str "この CSV はこの repository 独自の列であり、"
        "全銀協の総合振込フォーマットでも、特定の銀行の取込形式でもない。"
        "全銀レコードのレイアウトをこの repository は読んでいないので、"
        "そのファイルは出力しない（payroll.artifact.bank-transfer/zengin を参照）")})

(defn prepare
  "Every run for one employer and period → the payees and the refusals.

  `:refused` is a first-class half of the answer and never filtered away. A
  transfer file listing three of four employees is a file that pays three of
  four employees, and the missing one is discovered by the person who did not
  get paid."
  [{:keys [employer period runs]}]
  (let [results (mapv #(payee (assoc % :period period)) runs)
        ok (filterv #(= :ok (:payee/status %)) results)
        refused (filterv #(= :refused (:payee/status %)) results)
        lines (mapv :payee/line ok)]
    {:transfer/employer-id (:client-id employer)
     :transfer/employer-name (:name employer)
     :transfer/period period
     :transfer/run-count (count runs)
     :transfer/lines lines
     :transfer/refused refused
     :transfer/total (reduce + 0 (map :amount lines))
     :transfer/complete? (empty? refused)
     :transfer/format format-declaration}))

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
;; 全銀 — the refusal
;; ---------------------------------------------------------------------------

(defn zengin
  "The 全銀協 総合振込 fixed-width file — REFUSED, for every input, always.

  There is no argument that makes this return bytes and there is no second
  arity that does. See the namespace docstring: the record layout has not
  been read, and a file assembled from memory would move somebody's salary.

  The refusal is not a dead end. It carries:

    :zengin/registered   the payee fields that ARE registered, per contract
    :zengin/missing      the ones that are not
    :zengin/also-needed  what would still be missing after every field above
                         was registered — the layout itself

  so that an operator can finish the half of the work that is theirs and see
  precisely what would remain."
  [{:keys [runs]}]
  {:zengin/status :unsupported
   :zengin/why
   (str "全銀協が定める総合振込のレコードレイアウト（ヘッダ・データ・"
        "トレーラ・エンドの各レコード、桁位置、文字種）を"
        "この repository は読んでいない。"
        "記憶から組み立てたレコードは、銀行に受理されないか、"
        "あるいは誤って読まれる。給与が動くファイルで後者が起きたときの"
        "コストは、出力しないことのコストより桁違いに大きい")
   :zengin/also-needed
   [{:needed/what "全銀協 総合振込フォーマットのレコードレイアウト"
     :needed/why "桁位置と文字種。これが無いとレコードは組めない"}
    {:needed/what "依頼人（事業主）の委託者コード・振込指定日の扱い"
     :needed/why "ヘッダレコードの項目であり、銀行との契約で決まる"}
    {:needed/what "預金種目の数値コード"
     :needed/why "普通/当座 が何番になるかはレイアウトの一部で、未読である"}]
   :zengin/per-contract
   (vec (for [{:keys [contract]} runs]
          (let [problems (vec (keep #(field-problem % contract) payee-fields))]
            {:contract-id (:contract/id contract)
             :zengin/registered (vec (for [f payee-fields
                                           :when (not (some #(= (:field/key f)
                                                                (:missing/key %))
                                                            problems))]
                                       (:field/key f)))
             :zengin/missing (mapv :missing/key problems)})))})
