(ns payroll.artifact.payee
  "One payee line, prepared — the part `payroll.artifact.bank-transfer` (this
  repository's own CSV) and `payroll.artifact.zengin` (the 全銀協 fixed-width
  file) both need and neither should own.

  `zengin` renders the same payee lines `bank-transfer` does, so it calls
  `prepare` here rather than duplicating the registration checks below. This
  namespace requires neither of them: `bank-transfer` keeps a compatibility
  `zengin` entry point that delegates to the real `payroll.artifact.zengin`,
  so if `zengin`'s payee preparation lived inside `bank-transfer` instead of
  here, the two namespaces would require each other.

  ## The facts an operator registers

  All five payee fields live on the CONTRACT record, registered by an
  operator, for the reason `:employment/recipient-residency` does: a bank
  account is a fact somebody looked at, not a fact a model proposes. None of
  them is derivable from anything this actor holds.

  ## The halfwidth-kana check is necessary and NOT sufficient

  A 受取人名 in a 全銀 record is halfwidth. `halfwidth?` checks that a
  registered name-kana contains only characters from the Unicode halfwidth
  block and ASCII — which is a fact about Unicode, checkable without the
  banking spec.

  It does NOT transliterate. Turning 川崎 or カワサキ into ｶﾜｻｷ is a decision
  about somebody's name that this actor is not entitled to make: the reading
  of a Japanese surname is not derivable from its characters, the bank's
  record is the one the account was opened under, and a name that does not
  match is a payment that bounces or lands somewhere else. The kana is
  REGISTERED by an operator who looked at the bank book.

  And the check is explicitly incomplete: `payroll.artifact.zengin` has since
  read the full permitted-character rule (小文字のカナ・長音・中黒点 are
  refused, and are not merely halfwidth-or-not) — passing `halfwidth?` here
  does not mean a bank would accept the name."
  (:require [clojure.string :as str]
            [payroll.meisai :as meisai]))

;; ---------------------------------------------------------------------------
;; The facts an operator registers
;; ---------------------------------------------------------------------------

(def payee-fields
  "What a transfer needs, per employee, and where it is registered.

  `:field/checkable` is the check this repository can actually perform.
  Where it is `:none`, the field is carried through unvalidated and the
  artifact says so — a format check invented for a 金融機関コード would be
  this namespace asserting a spec it has not read."
  [{:field/key :bank/financial-institution-code
    :field/label "金融機関コード"
    :field/checkable :present-only
    :field/why (str "この namespace は registration の有無しか検査しない。"
                    "全銀レコードとしての桁数・体系の検査は "
                    "payroll.artifact.zengin が行う")}
   {:field/key :bank/branch-code
    :field/label "支店コード"
    :field/checkable :present-only
    :field/why "同上"}
   {:field/key :bank/account-type
    :field/label "預金種目"
    :field/checkable :vocabulary
    :field/vocabulary #{:ordinary :current}
    :field/why (str "普通/当座 は operator の登録値として受け取る。"
                    "全銀レコードでの数字コードへの変換と、その桁数の検査は "
                    "payroll.artifact.zengin が行う。"
                    "この namespace は語彙の registration しか検査しないので、"
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
        "全銀協の仕様が許す文字種・桁数・記号の正確な検査は "
        "payroll.artifact.zengin が行う —— この namespace は"
        "registration レベルの検査しか行わない。"
        "また、この名義が口座名義と一致するかどうかは"
        "operator が通帳を見て登録した事実に依存する")})

;; ---------------------------------------------------------------------------
;; One payee
;; ---------------------------------------------------------------------------

(defn field-problem
  "What is wrong with one registered field, or nil.

  Public rather than `defn-`: `payroll.artifact.bank-transfer`'s
  compatibility `zengin` entry point reuses this same registration check
  before adding the real permitted-character check on top of it."
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

(def format-declaration
  "What `payroll.artifact.bank-transfer`'s CSV is. Emitted into the JSON
  companion, so a consumer cannot take the file for a bank format because it
  has the right columns."
  {:format/standard :none
   :format/why
   (str "この CSV はこの repository 独自の列であり、"
        "全銀協の総合振込フォーマットでも、特定の銀行の取込形式でもない。"
        "全銀レコードのレイアウトが必要な場合は payroll.artifact.zengin を参照")})

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
