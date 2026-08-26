(ns payroll.artifact.zengin
  "全銀 総合振込 — the fixed-width record and the CSV variant PayPay Bank
  accepts.

  ## The layout was READ, and here is exactly what was read

  `payroll.artifact.bank-transfer/zengin` refused for every input on the
  stated grounds that *the record layout has not been read*. It has now been
  read, from the bank's own published specification:

    PayPay銀行「WEB総振（総合振込）振込データの項目説明」 2025年3月6日改定
    https://www.paypay-bank.co.jp/business/payment/transfer-all/guide/data.pdf

  and the service it belongs to:

    PayPay銀行 ビジネスアカウントプラス／WEB総振 ご利用ガイド
    https://www.paypay-bank.co.jp/business/payment/transfer-all/guide/ba-plus.pdf

  Every field name, width, fixed value, justification and pad character in
  `layout` below is transcribed from the first of those, item table by item
  table. The character set in `permitted-characters` is its 使用許容文字 page.

  **What is still NOT established by any of this: that a bank accepted a file
  this code produced.** No test transfer has been made. `docs/maturity.md`'s
  G4 asks for exactly that and it is still open; what closed is the half of
  G4 that was this repository's, which was reading the layout.

  ## Three things the specification says that a from-memory version gets wrong

  1. **The CSV trailer is not the fixed-width trailer.** 合計件数 is **6**
     digits in the fixed-width record and **4** in the CSV, and the CSV
     trailer's own stated length is 118 rather than 120. Transcribed as two
     different records rather than one record rendered two ways.
  2. **A zero-yen line is included and counted.** 「振込金額を0円で入力して
     いる箇所の振り込みは実行されません。合計件数は0円のデータを含めた件数
     を入力してください。」 So a zero line is kept, counted in 合計件数,
     contributes nothing to 合計金額, and is REPORTED — dropping it would
     change the count the bank reconciles against, and silently paying
     nothing is not the same as not being asked to pay.
  3. **Small kana, 長音 and 中黒 are not permitted characters.** ｼﾝｼﾞｭｸ is
     rejected and ｼﾝｼﾞﾕｸ is not; ﾈﾂﾄ･ｾﾝﾀｰ is rejected twice over. This is the
     rule most likely to be violated by a name somebody pasted, and the
     specification gives the substitutions rather than leaving them to be
     guessed — so this namespace REFUSES and quotes the rule, and does not
     substitute. Turning ｼﾝｼﾞｭｸ into ｼﾝｼﾞﾕｸ is a decision about somebody's
     account name, and `payroll.artifact.bank-transfer` already records why
     this actor does not make those.

  ## Bytes

  Every permitted character is one byte in Shift_JIS — ASCII digits and
  capitals below 0x80, halfwidth katakana at 0xA1–0xDF — so a 120-CHARACTER
  record is a 120-BYTE record, and `payroll.artifact.zengin-test` asserts
  that by actually encoding to Shift_JIS on the JVM and measuring. The
  namespace itself stays `.cljc` and produces characters; encoding is a host
  effect and belongs where `payroll.host.jvm` puts the other ones.

  The record terminator is CRLF. **The specification text extracted from the
  PDF does not state a terminator** — its only mention of line breaks says
  the printed sample wraps and the real data does not — so CRLF here is the
  全銀 convention for an uploaded text file and is one of the things a test
  transfer would settle. It is `line-terminator` rather than a literal for
  that reason.

  ## Generation is not payment

  This namespace builds a file. It does not approve a run, does not mark one
  paid, and is reachable only for runs `payroll.meisai/payable?` already
  admits. Nothing here writes to a store."
  (:require [clojure.string :as str]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.artifact.text :as text]
            [payroll.meisai :as meisai]))

(def source
  {:source/title "PayPay銀行 WEB総振（総合振込）振込データの項目説明"
   :source/authority "PayPay銀行"
   :source/revised "2025-03-06"
   :source/url "https://www.paypay-bank.co.jp/business/payment/transfer-all/guide/data.pdf"
   :source/read-at "2026-08-26"
   :source/also
   {:source/title "PayPay銀行 ビジネスアカウントプラス（WEB総振）ご利用ガイド"
    :source/url "https://www.paypay-bank.co.jp/business/payment/transfer-all/guide/ba-plus.pdf"}
   :source/what-it-does-not-establish
   (str "この repository が出力したファイルを銀行が受理したこと。"
        "テスト振込は行われていない（docs/maturity.md の G4）")})

(def paypay-bank-code
  "「0033」を入力してください。（固定値）「PayPay銀行」であることを意味します。"
  "0033")

(def record-length
  "120 bytes, and 120 characters, and those are the same number ONLY because
  every permitted character is one byte in Shift_JIS.

  Named once and read by `layout` (as each record's `:record/length`) and by
  `->fixed-width-bytes` (as the byte count each record is measured against),
  so the character check and the byte check cannot drift apart. The CSV
  trailer is deliberately NOT this — its own stated length is 118."
  120)

(def line-terminator
  "CRLF. See the namespace docstring — the specification text does not state
  it, so this is the convention and not a transcription."
  "\r\n")

;; ---------------------------------------------------------------------------
;; 使用許容文字
;; ---------------------------------------------------------------------------

(def permitted-symbols
  "記号: \\ , . ｢ ｣ ( ) - / and the space.

  `\\` is U+005C, which is the byte 0x5C — rendered as ¥ under JIS, which is
  what the specification's table shows. Only U+005C is accepted: U+00A5 and
  U+FFE5 look like the same character to a person and are not the same byte,
  and a byte the bank did not ask for is a field of the wrong length."
  #{\\ \, \. \｢ \｣ \( \) \- \/ \space})

(def permitted-kana
  "The katakana the specification lists, plus 濁点 and 半濁点.

  Small kana are ABSENT and that is the point: 「カナ文字は小文字を使用しない
  でください」, with ｼﾝｼﾞﾕｸｼﾃﾝ given as correct and ｼﾝｼﾞｭｸｼﾃﾝ as wrong."
  (into #{\ﾞ \ﾟ}
        "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ"))

(def permitted-digits (into #{} "0123456789"))
(def permitted-letters (into #{} "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(def permitted-characters
  (into (into (into permitted-symbols permitted-kana)
              permitted-digits)
        permitted-letters))

(def forbidden-substitutions
  "The three the specification spells out, with the replacement it gives.

  Reported on a refusal and NOT applied. The bank tells an operator what to
  write; this tells them the same thing and lets them decide, because the
  name that has to match is the one on the account and not the one this
  actor derived."
  [{:forbidden/char \･ :forbidden/name "中黒点"
    :forbidden/use-instead "." :forbidden/quote "中黒点「･」は使用できません"}
   {:forbidden/char \ー :forbidden/name "長音"
    :forbidden/use-instead "-" :forbidden/quote "長音「ー」は使用できません"}
   ;; U+FF70, the HALFWIDTH prolonged sound mark. It is inside the halfwidth
   ;; block, so `payroll.artifact.bank-transfer/halfwidth?` accepts it and a
   ;; name carrying it reaches this layer looking fine — which is exactly why
   ;; it is listed separately from the fullwidth one above rather than being
   ;; left to the generic refusal.
   {:forbidden/char \ｰ :forbidden/name "長音（半角）"
    :forbidden/use-instead "-" :forbidden/quote "長音「ー」は使用できません"}])

(def small-kana (into #{} "ｧｨｩｪｫｬｭｮｯ"))

(defn character-problem
  "Why this character may not appear, or nil."
  [ch]
  (cond
    (contains? permitted-characters ch) nil
    (contains? small-kana ch)
    {:char ch :why (str "小文字のカナ「" ch "」は使用できない"
                        "（正 ｼﾝｼﾞﾕｸｼﾃﾝ / 誤 ｼﾝｼﾞｭｸｼﾃﾝ）")}
    :else
    (if-let [f (first (filter #(= ch (:forbidden/char %)) forbidden-substitutions))]
      {:char ch :why (str (:forbidden/quote f) "。「"
                          (:forbidden/use-instead f) "」に置き換える"
                          "（この actor は置き換えない —— "
                          "口座名義は operator が通帳を見て登録する）")}
      {:char ch :why (str "「" ch "」は PayPay 銀行の使用許容文字ではない。"
                          "すべて半角の数字・英大文字・カナ・"
                          "記号（\\ , . ｢ ｣ ( ) - /）・空白のみ")})))

(defn character-problems [s]
  (vec (keep character-problem (seq (str s)))))

(defn permitted? [s] (empty? (character-problems s)))

;; ---------------------------------------------------------------------------
;; The layout, transcribed
;; ---------------------------------------------------------------------------

(def layout
  "Every record, field by field, exactly as the item tables give them.

    :f/width      桁数
    :f/kind       :numeric (数字のみ) | :text (英数カナ)
    :f/justify    :left (左詰め) | :right (右詰め)
    :f/pad        the character the specification names for the remainder
    :f/fixed      a 固定値, where the specification gives one
    :f/source     which key of the request the value comes from
    :f/required?  whether a blank value is an error the bank will raise

  A vector of vectors and not a map: the ORDER is the record."
  {:header
   {:record/label "ヘッダーレコード"
    :record/length record-length
    :record/fields
    [{:f/no 1 :f/name "データ区分" :f/width 1 :f/kind :numeric :f/fixed "1"}
     {:f/no 2 :f/name "種別コード" :f/width 2 :f/kind :numeric :f/fixed "21"}
     {:f/no 3 :f/name "コード区分" :f/width 1 :f/kind :numeric :f/fixed "0"
      :f/note "文字コードが「ＪＩＳ」であることを意味します"}
     {:f/no 4 :f/name "振込依頼人コード" :f/width 10 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""
      :f/note "未設定（WEB総振では使用しません）スペースを10桁"}
     {:f/no 5 :f/name "振込依頼人名" :f/width 40 :f/kind :text
      :f/justify :left :f/pad \space :f/source :origin/name-kana :f/required? true
      :f/note "振込依頼人名がスペースのみの場合エラーとなります"}
     {:f/no 6 :f/name "振込日" :f/width 4 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :transfer/date-mmdd :f/required? true
      :f/note "振込実行日の月日を数字4桁(MMDD)"}
     {:f/no 7 :f/name "振込元銀行コード" :f/width 4 :f/kind :numeric
      :f/fixed "0033" :f/note "PayPay銀行"}
     {:f/no 8 :f/name "振込元銀行名" :f/width 15 :f/kind :text
      :f/justify :left :f/pad \space :f/source :origin/bank-name-kana
      :f/note "任意の入力項目"}
     {:f/no 9 :f/name "振込元支店コード" :f/width 3 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :origin/branch-code :f/required? true}
     {:f/no 10 :f/name "振込元支店名" :f/width 15 :f/kind :text
      :f/justify :left :f/pad \space :f/source :origin/branch-name-kana
      :f/note "任意の入力項目"}
     {:f/no 11 :f/name "振込元預金種目" :f/width 1 :f/kind :numeric :f/fixed "1"
      :f/note "預金種目が「普通預金」であることを意味します（固定値）"}
     {:f/no 12 :f/name "振込元口座番号" :f/width 7 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :origin/account-number :f/required? true}
     {:f/no 13 :f/name "予備" :f/width 17 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}]}

   :data
   {:record/label "データレコード"
    :record/length record-length
    :record/fields
    [{:f/no 1 :f/name "データ区分" :f/width 1 :f/kind :numeric :f/fixed "2"}
     {:f/no 2 :f/name "銀行コード" :f/width 4 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :payee/bank-code :f/required? true}
     {:f/no 3 :f/name "銀行名" :f/width 15 :f/kind :text
      :f/justify :left :f/pad \space :f/source :payee/bank-name-kana}
     {:f/no 4 :f/name "支店コード" :f/width 3 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :payee/branch-code :f/required? true}
     {:f/no 5 :f/name "支店名" :f/width 15 :f/kind :text
      :f/justify :left :f/pad \space :f/source :payee/branch-name-kana}
     {:f/no 6 :f/name "手形交換所番号" :f/width 4 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}
     {:f/no 7 :f/name "預金種目" :f/width 1 :f/kind :numeric
      :f/source :payee/account-type-code :f/required? true
      :f/note "1：普通、2：当座、4：貯蓄"}
     {:f/no 8 :f/name "口座番号" :f/width 7 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :payee/account-number :f/required? true}
     {:f/no 9 :f/name "受取人名" :f/width 30 :f/kind :text
      :f/justify :left :f/pad \space :f/source :payee/name-kana :f/required? true
      :f/note (str "受取人名がスペースのみの場合エラー。"
                   "30文字を超える場合はアップロードできない")}
     {:f/no 10 :f/name "振込金額" :f/width 10 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :payee/amount :f/required? true}
     {:f/no 11 :f/name "新規コード" :f/width 1 :f/kind :numeric :f/fixed "0"
      :f/note "「その他」（固定値）"}
     {:f/no 12 :f/name "顧客コード１" :f/width 10 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}
     {:f/no 13 :f/name "顧客コード２" :f/width 10 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}
     {:f/no 14 :f/name "振込指定区分" :f/width 1 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}
     {:f/no 15 :f/name "識別表示" :f/width 1 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}
     {:f/no 16 :f/name "予備" :f/width 7 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}]}

   :trailer
   {:record/label "トレーラーレコード"
    :record/length record-length
    :record/fields
    [{:f/no 1 :f/name "データ区分" :f/width 1 :f/kind :numeric :f/fixed "8"}
     {:f/no 2 :f/name "合計件数" :f/width 6 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :total/count :f/required? true}
     {:f/no 3 :f/name "合計金額" :f/width 12 :f/kind :numeric
      :f/justify :right :f/pad \0 :f/source :total/amount :f/required? true}
     {:f/no 4 :f/name "予備" :f/width 101 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}]}

   :end
   {:record/label "エンドレコード"
    :record/length record-length
    :record/fields
    [{:f/no 1 :f/name "データ区分" :f/width 1 :f/kind :numeric :f/fixed "9"}
     {:f/no 2 :f/name "予備" :f/width 119 :f/kind :text
      :f/justify :left :f/pad \space :f/fixed ""}]}})

(def csv-trailer
  "The CSV variant's trailer, which is NOT the fixed-width one.

  合計件数 is 4 digits rather than 6 and the record's stated length is 118
  rather than 120. Transcribed as its own record because rendering the
  fixed-width trailer without padding would produce a 6-digit count the
  specification does not ask for."
  {:record/label "トレーラーレコード（CSV）"
   :record/length 118
   :record/fields
   [{:f/no 1 :f/name "データ区分" :f/width 1 :f/kind :numeric :f/fixed "8"}
    {:f/no 2 :f/name "合計件数" :f/width 4 :f/kind :numeric
     :f/source :total/count :f/required? true}
    {:f/no 3 :f/name "合計金額" :f/width 12 :f/kind :numeric
     :f/source :total/amount :f/required? true}
    {:f/no 4 :f/name "予備" :f/width 101 :f/kind :text :f/fixed ""}]})

(def account-type-codes
  "預金種目（1：普通、2：当座、4：貯蓄）, mapped from the vocabulary
  `payroll.artifact.bank-transfer/payee-fields` already admits.

  貯蓄 has no keyword here because `payee-fields` does not admit one, and
  inventing the keyword would mean inventing a registration an operator never
  made."
  {:ordinary "1" :current "2"})

;; ---------------------------------------------------------------------------
;; Rendering one field
;; ---------------------------------------------------------------------------

(defn- ->digits
  "A numeric field's value as digits, or nil if it is not one.

  A number becomes its digits; a string is accepted only if it is already
  all digits. `\"０３３\"` (full-width) and `\"33 \"` are refused rather than
  cleaned — every one of those is somebody's registration being altered on
  the way to a bank."
  [v]
  (cond
    (and (number? v) (not (neg? v)) (zero? (mod v 1)))
    (str #?(:clj (long v) :cljs v))
    (and (string? v) (re-matches #"\d+" v)) v
    :else nil))

(defn render-field
  "One field as exactly `:f/width` characters, or a problem.

  Returns `{:field/text \"…\"}` or `{:field/problem {…}}`. Never truncates: a
  受取人名 longer than 30 is an upload the specification says will be
  rejected, and a payee name silently cut to fit is a payment to a name the
  account does not have."
  [{:f/keys [name width kind justify pad fixed source required?] :as f} values]
  (let [raw (if (contains? f :f/fixed) fixed (get values source))
        pad-ch (or pad (if (= :numeric kind) \0 \space))
        just (or justify (if (= :numeric kind) :right :left))
        s (cond
            (nil? raw) ""
            (= :numeric kind) (->digits raw)
            :else (str raw))]
    (cond
      (nil? s)
      {:field/problem {:field/name name :field/source source
                       :field/why (str name "は数字のみの項目だが、"
                                       (pr-str raw) " は数字ではない")}}

      (and required? (str/blank? s))
      {:field/problem {:field/name name :field/source source
                       :field/why (str name "は必須である"
                                       (when (= source :payee/name-kana)
                                         "（受取人名がスペースのみの場合エラー）"))}}

      (> (count s) width)
      {:field/problem {:field/name name :field/source source
                       :field/why (str name "は" width "桁だが、登録値は"
                                       (count s) "文字ある。"
                                       "この actor は切り詰めない —— "
                                       "桁に収めるために名義を削れば、"
                                       "着金しない振込になる")}}

      (seq (character-problems s))
      {:field/problem {:field/name name :field/source source
                       :field/why (str name ": "
                                       (str/join "、"
                                                 (map :why (character-problems s))))}}

      :else
      {:field/text (let [fill (apply str (repeat (- width (count s)) pad-ch))]
                     (if (= :right just) (str fill s) (str s fill)))})))

(defn render-record
  "One record as a fixed-width string of exactly `:record/length` characters,
  or every problem in it.

  Every field is rendered even after the first failure. An operator told one
  missing field at a time makes one round trip per field, and the round trip
  here is a bank file on the day wages are due."
  [{:record/keys [label length fields]} values]
  (let [rendered (mapv #(render-field % values) fields)
        problems (vec (keep :field/problem rendered))]
    (if (seq problems)
      {:record/status :refused :record/label label :record/problems problems}
      (let [text (apply str (map :field/text rendered))]
        (if (= length (count text))
          {:record/status :ok :record/label label :record/text text}
          ;; Cannot happen while `layout` sums to `:record/length` — and it
          ;; is checked anyway, because a transcription error in a width is
          ;; exactly the mistake this file could contain, and a short record
          ;; is one the bank reads with every field after it shifted.
          {:record/status :refused :record/label label
           :record/problems [{:field/why (str label "の組み立て結果が "
                                             (count text) " 文字で、"
                                             "仕様の " length
                                             " 文字と一致しない")}]})))))

(defn render-csv-record
  "One record as comma-separated values, unpadded.

  The same fields and the same validation; only the padding goes away
  (「カンマで区切るため、スペースや「0」で桁数を合わせる必要がありません」).

  A comma inside a value is REFUSED even though the specification lists `,`
  as a permitted character: a comma in a CSV field is a field separator, and
  a file whose 受取人名 splits into two columns is read by the bank as a
  different record. The fixed-width variant accepts it."
  [{:record/keys [label fields]} values]
  (let [cells (for [{:f/keys [name width kind source required?] :as f} fields
                    :let [raw (if (contains? f :f/fixed) (:f/fixed f) (get values source))
                          s (cond (nil? raw) ""
                                  (= :numeric kind) (->digits raw)
                                  :else (str raw))]]
                (cond
                  (nil? s) {:field/problem
                            {:field/name name
                             :field/why (str name "は数字のみの項目だが、"
                                             (pr-str raw) " は数字ではない")}}
                  (and required? (str/blank? s))
                  {:field/problem {:field/name name
                                   :field/why (str name "は必須である")}}
                  (> (count s) width)
                  {:field/problem {:field/name name
                                   :field/why (str name "は" width "桁を超えている")}}
                  (str/includes? s ",")
                  {:field/problem
                   {:field/name name
                    :field/why (str name "に読点「,」が入っている。"
                                    "全銀の使用許容文字ではあるが、"
                                    "CSV では区切り文字になるので"
                                    "この形式では出力できない"
                                    "（固定長なら出力できる）")}}
                  (seq (character-problems s))
                  {:field/problem {:field/name name
                                   :field/why (str/join "、" (map :why (character-problems s)))}}
                  :else {:field/text s}))
        cells (vec cells)
        problems (vec (keep :field/problem cells))]
    (if (seq problems)
      {:record/status :refused :record/label label :record/problems problems}
      {:record/status :ok :record/label label
       :record/text (str/join "," (map :field/text cells))})))

;; ---------------------------------------------------------------------------
;; The origin — the employer's own account, which is REGISTERED
;; ---------------------------------------------------------------------------

(def origin-fields
  "What the header needs about the payer, and where an operator registers it.

  All on the EMPLOYER record, for the reason a payee's account is on the
  contract: a bank account is a fact somebody looked at.

  振込元銀行コード is not here — it is the fixed value `0033`, because this
  file is PayPay Bank's. 振込元預金種目 is not here either: the specification
  fixes it to `1`（普通）, so a deployment whose payroll account is a 当座
  cannot use this format and would be told so by the bank rather than by a
  field this actor invented."
  [{:field/key :zengin/origin-name-kana :field/label "振込依頼人名（半角カナ）"
    :field/required? true :field/width 40
    :field/why "スペースのみだと銀行がエラーにする"}
   {:field/key :zengin/origin-branch-code :field/label "振込元支店コード"
    :field/required? true :field/width 3}
   {:field/key :zengin/origin-account-number :field/label "振込元口座番号"
    :field/required? true :field/width 7}
   {:field/key :zengin/origin-bank-name-kana :field/label "振込元銀行名（任意）"
    :field/required? false :field/width 15
    :field/why "任意。入力するなら ﾍﾟｲﾍﾟｲｷﾞﾝｺｳ"}
   {:field/key :zengin/origin-branch-name-kana :field/label "振込元支店名（任意）"
    :field/required? false :field/width 15 :field/why "任意"}])

(def transfer-date-key
  "振込指定日 — MMDD, registered per file rather than per employer.

  Not derived from the period. This actor holds no calendar (the reason
  `payroll.shakai-hoken` refuses to derive 前月 from a run's period), and the
  guide says the date entered in the data must equal the one entered on the
  upload screen — which is a fact about a screen this actor cannot see."
  :zengin/transfer-date-mmdd)

(defn- origin-problems [employer date]
  (vec (concat
        (for [{:field/keys [key label required? why]} origin-fields
              :when (and required? (str/blank? (str (get employer key))))]
          {:missing/key key :missing/label label
           :missing/why (str label "が事業主に登録されていない"
                             (when why (str "。" why)))})
        (when-not (re-matches #"\d{4}" (str date))
          [{:missing/key transfer-date-key
            :missing/label "振込指定日（MMDD）"
            :missing/why (str "振込指定日が MMDD の4桁で登録されていない。"
                              "この actor は期間から日付を導出しない —— "
                              "暦を持たず、"
                              "アップロード画面で入力する日付と一致させるのは"
                              "operator の仕事である")}]))))

;; ---------------------------------------------------------------------------
;; Assembly
;; ---------------------------------------------------------------------------

(defn payee-values
  "One `payroll.artifact.bank-transfer/payee` line as this layout's values."
  [line]
  {:payee/bank-code (:bank-code line)
   :payee/bank-name-kana (:bank-name-kana line)
   :payee/branch-code (:branch-code line)
   :payee/branch-name-kana (:branch-name-kana line)
   :payee/account-type-code (get account-type-codes (:account-type-key line))
   :payee/account-number (:account-number line)
   :payee/name-kana (:payee-name-kana line)
   :payee/amount (:amount line)})

(defn prepare
  "Every payable run for one employer, period and transfer date → the records.

    {:employer  the REGISTERED employer record
     :period
     :transfer-date-mmdd  \"MMDD\", or read off the employer's registration
     :runs      [{:contract  the REGISTERED contract
                  :meisai    `payroll.meisai/lines`}]}

  Returns

    {:zengin/status  :ok | :refused
     :zengin/records {:header … :data […] :trailer … :end …}   rendered text
     :zengin/refused [payee refusals, verbatim from bank-transfer]
     :zengin/zero-amount [contract ids whose 振込金額 is 0]
     :zengin/total-count / :zengin/total-amount}

  `:zengin/status :refused` when ANY run cannot be rendered. A partial 総合振込
  file is the failure mode `payroll.artifact.bank-transfer/prepare` names: it
  pays three of four employees, and the fourth finds out."
  [{:keys [employer period runs transfer-date-mmdd]}]
  (let [date (or transfer-date-mmdd (get employer transfer-date-key))
        base (bank/prepare {:employer employer :period period :runs runs})
        ;; the account-type KEYWORD, which `bank/prepare` renders as a label —
        ;; this layout needs the code, and mapping back from 「普通」 would be
        ;; the round trip through a display string that loses 貯蓄.
        lines (mapv (fn [{:keys [contract meisai]}]
                      (merge (first (filter #(= (:contract/id contract)
                                                (:contract-id %))
                                            (:transfer/lines base)))
                             {:account-type-key (:bank/account-type contract)
                              :bank-name-kana (:bank/financial-institution-name-kana contract)
                              :branch-name-kana (:bank/branch-name-kana contract)
                              :meisai/payable? (meisai/payable? meisai)}))
                    runs)
        payable (filterv :contract-id lines)
        og (origin-problems employer date)
        origin {:origin/name-kana (get employer :zengin/origin-name-kana)
                :origin/branch-code (get employer :zengin/origin-branch-code)
                :origin/account-number (get employer :zengin/origin-account-number)
                :origin/bank-name-kana (get employer :zengin/origin-bank-name-kana)
                :origin/branch-name-kana (get employer :zengin/origin-branch-name-kana)
                :transfer/date-mmdd date}
        total-count (count payable)
        ;; 「合計件数は0円のデータを含めた件数を入力してください」— a zero line
        ;; is counted here and contributes nothing below.
        total-amount (reduce + 0 (map #(or (:amount %) 0) payable))
        header (render-record (:header layout) origin)
        data (mapv #(render-record (:data layout) (payee-values %)) payable)
        trailer (render-record (:trailer layout)
                               {:total/count total-count :total/amount total-amount})
        end (render-record (:end layout) {})
        record-problems (vec (concat (:record/problems header)
                                     (mapcat :record/problems data)
                                     (:record/problems trailer)))]
    {:zengin/status (if (and (empty? og)
                             (empty? record-problems)
                             (:transfer/complete? base)
                             (pos? total-count))
                      :ok :refused)
     :zengin/employer-id (:client-id employer)
     :zengin/period period
     :zengin/transfer-date date
     :zengin/source source
     :zengin/records {:header header :data data :trailer trailer :end end}
     :zengin/origin origin
     :zengin/lines payable
     :zengin/origin-missing og
     :zengin/refused (:transfer/refused base)
     :zengin/record-problems record-problems
     :zengin/total-count total-count
     :zengin/total-amount total-amount
     :zengin/zero-amount (vec (for [l payable :when (= 0 (:amount l))]
                                {:contract-id (:contract-id l)
                                 :why (str "振込金額が0円。"
                                           "「振込金額を0円で入力している箇所の"
                                           "振り込みは実行されません」——"
                                           "件数には含めるが、支払は行われない")}))
     :zengin/why
     (cond
       (seq og) (str "依頼人（事業主）の登録が足りない: "
                     (str/join "、" (map :missing/label og)))
       (seq (:transfer/refused base))
       (str (count (:transfer/refused base))
            " 件の run が振込データに載せられない。"
            "一部だけのファイルは、一部だけ支払うファイルである")
       (seq record-problems)
       (str/join "。" (map :field/why record-problems))
       (zero? total-count)
       (str "データレコードが 0 件である。"
            "空のファイルは「支払うものが無い」ではなく「作れていない」")
       :else (str total-count " 件・合計 " total-amount " 円。"
                  "銀行がこのファイルを受理したことは、"
                  "この repository は確かめていない"))}))

(defn ->fixed-width
  "The whole file, CRLF-terminated. nil unless `prepare` said `:ok` — there is
  no arity that emits a partial file."
  [{:zengin/keys [status records]}]
  (when (= :ok status)
    (str/join line-terminator
              (concat [(:record/text (:header records))]
                      (map :record/text (:data records))
                      [(:record/text (:trailer records))
                       (:record/text (:end records))
                       ""]))))

;; ---------------------------------------------------------------------------
;; Bytes — Shift_JIS, and why the encoder is here rather than on the host
;; ---------------------------------------------------------------------------

(def encoding
  "The character encoding the bank's specification names, as the label that
  goes on the wire (`charset=`) and in the JSON companion.

  `Shift_JIS` and not `windows-31j`: the two differ only outside the
  permitted set, and the name in the document is the one an operator will
  compare against."
  "Shift_JIS")

(def halfwidth-katakana-range
  "The Unicode block whose characters are ONE byte in Shift_JIS.

  U+FF61 `｡` through U+FF9F `ﾟ` map contiguously to 0xA1–0xDF. Every kana,
  the 濁点/半濁点 and the two brackets `｢｣` this layout permits are inside
  it; every fullwidth form of the same character is not, which is the whole
  reason a name pasted from a spreadsheet fails."
  {:from 0xFF61 :to 0xFF9F :byte-from 0xA1})

(defn shift-jis-byte
  "One character as its single Shift_JIS byte, or nil.

  Two ranges and no table, because those are the only two ranges the
  permitted set occupies: ASCII below 0x80 is byte-identical, and the
  halfwidth katakana block is a fixed offset. A character outside them has
  NO single-byte encoding, so there is no answer to give and nil is the
  honest one — see `->shift-jis-bytes`, which refuses rather than
  substituting.

  `\\` (U+005C) is byte 0x5C, which is what the specification's table shows
  as `¥`. U+00A5 and U+FFE5 look identical to a person and are neither
  ASCII nor halfwidth katakana, so they arrive here and get nil — which is
  the intended outcome, because a byte the bank did not ask for makes the
  field the wrong length."
  [ch]
  (let [c #?(:clj (int ch) :cljs (.charCodeAt (str ch) 0))]
    (cond
      (< c 0x80) c
      (and (>= c (:from halfwidth-katakana-range))
           (<= c (:to halfwidth-katakana-range)))
      (+ (:byte-from halfwidth-katakana-range)
         (- c (:from halfwidth-katakana-range)))
      :else nil)))

(defn ->shift-jis-bytes
  "A string as Shift_JIS bytes, or a refusal naming every character that has
  none.

    {:bytes/status :ok :bytes/bytes [n …] :bytes/count n}
    {:bytes/status :refused :bytes/problems [{:char :position :why} …]}

  ## Why the encoder is written here and not left to the host

  This namespace is `.cljc` and `payroll.host.jvm` could call
  `String.getBytes` with a Shift_JIS charset. It does not, for three
  reasons:

  1. **The permitted set is exactly two contiguous ranges.** There is no
     table to get wrong; the whole encoder is two comparisons and an offset,
     and `payroll.artifact.zengin-test` cross-checks every one of its 79
     permitted characters against the JVM's own `Charset` — so this is a
     transcription that is MEASURED against the platform rather than one
     that replaces it.
  2. **A platform encoder substitutes.** `String.getBytes` answers `?`
     (0x3F) for a character the charset cannot represent, silently, and the
     record stays 120 bytes long — so the one failure mode this whole
     namespace exists to prevent (a name that is not the name on the
     account) would pass every length check. This refuses instead, and names
     the character and its position.
  3. **`.cljc` means the property is testable everywhere the code runs.**
     A JVM-only encoding step would make `120 bytes` a claim that holds on
     one runtime and is untested on the others.

  Refusal carries the character's POSITION as well as the character: a
  40-character 振込依頼人名 with one wrong kana in the middle is otherwise a
  hunt."
  [s]
  (let [cs (vec (str s))
        problems (vec (for [i (range (count cs))
                            :let [ch (nth cs i)]
                            :when (nil? (shift-jis-byte ch))]
                        {:char ch :position i
                         :why (or (:why (character-problem ch))
                                  (str "「" ch "」は Shift_JIS で1バイトに"
                                       "ならない。この様式は1文字1バイトを"
                                       "前提に桁数を数えている"))}))]
    (if (seq problems)
      {:bytes/status :refused :bytes/problems problems
       :bytes/why (str (count problems) " 文字が Shift_JIS の1バイト文字ではない: "
                       (str/join "、" (map :why problems)))}
      (let [bs (mapv shift-jis-byte cs)]
        {:bytes/status :ok :bytes/bytes bs :bytes/count (count bs)}))))

(def crlf-bytes
  "The record terminator as bytes. See `line-terminator` — CRLF here is the
  全銀 convention for an uploaded text file and is one of the things a test
  transfer would settle."
  [0x0D 0x0A])

(defn ->fixed-width-bytes
  "The whole file as Shift_JIS bytes, or a refusal.

    {:file/status :ok
     :file/bytes    [n …]           the whole file including the final CRLF
     :file/records  [{:record/label :record/bytes n} …]
     :file/encoding \"Shift_JIS\"
     :file/line-terminator :crlf}

  **Each record is measured at 120 bytes individually, and the whole file is
  checked against `records × 122`.** Both, because they fail differently: a
  single over-long record is a field somebody registered wrong, and a
  total that does not match the sum is a terminator that went missing or
  doubled. A file whose total is right because one record borrowed a byte
  from the next is a file the bank rejects with an offset, and the operator
  reading that offset has nothing here to compare it to.

  Refuses when `prepare` refused — there is no arity that emits a partial
  file, for `->fixed-width`'s reason."
  [{:zengin/keys [status records] :as prepared}]
  (if (not= :ok status)
    {:file/status :refused
     :file/why (or (:zengin/why prepared)
                   "prepare が :ok を返していないファイルは出力しない")}
    (let [texts (concat [(:record/text (:header records))]
                        (map :record/text (:data records))
                        [(:record/text (:trailer records))
                         (:record/text (:end records))])
          labels (concat [(:record/label (:header records))]
                         (map :record/label (:data records))
                         [(:record/label (:trailer records))
                          (:record/label (:end records))])
          encoded (mapv ->shift-jis-bytes texts)
          bad (vec (keep-indexed (fn [i e]
                                   (when (not= :ok (:bytes/status e))
                                     {:record/label (nth labels i)
                                      :record/why (:bytes/why e)
                                      :record/problems (:bytes/problems e)}))
                                 encoded))
          wrong-length
          (vec (keep-indexed (fn [i e]
                               (when (and (= :ok (:bytes/status e))
                                          (not= record-length (:bytes/count e)))
                                 {:record/label (nth labels i)
                                  :record/why (str (nth labels i) "は "
                                                   (:bytes/count e)
                                                   " バイトで、仕様の "
                                                   record-length
                                                   " バイトと一致しない")}))
                             encoded))]
      (cond
        (seq bad)
        {:file/status :refused :file/records bad
         :file/why (str/join "。" (map :record/why bad))}

        (seq wrong-length)
        {:file/status :refused :file/records wrong-length
         :file/why (str/join "。" (map :record/why wrong-length))}

        :else
        (let [bs (vec (mapcat #(concat (:bytes/bytes %) crlf-bytes) encoded))
              expected (* (count encoded) (+ record-length (count crlf-bytes)))]
          (if (not= expected (count bs))
            {:file/status :refused
             :file/why (str "ファイル全体が " (count bs) " バイトで、"
                            (count encoded) " レコード × "
                            (+ record-length (count crlf-bytes))
                            " バイト = " expected " と一致しない")}
            {:file/status :ok
             :file/bytes bs
             :file/encoding encoding
             :file/line-terminator :crlf
             :file/record-length record-length
             :file/records (mapv (fn [l e] {:record/label l
                                            :record/bytes (:bytes/count e)})
                                 labels encoded)}))))))

(def fixed-width-filename "furikomi-zengin.txt")
(def csv-filename "furikomi-zengin.csv")

(defn download
  "The fixed-width file as a download, or a refusal.

    {:download/status :ok
     :download/bytes […]
     :download/content-type \"text/plain; charset=Shift_JIS\"
     :download/filename …}

  The content type carries the charset because the bytes are NOT UTF-8 and a
  browser told nothing assumes they are — the halfwidth katakana would be
  rendered as replacement characters, and an operator who then saved the page
  would upload a file the bank cannot parse. `payroll.host.jvm` writes
  `:download/bytes` verbatim; a host that re-encoded the string would undo
  exactly this."
  [prepared]
  (let [f (->fixed-width-bytes prepared)]
    (if (= :ok (:file/status f))
      {:download/status :ok
       :download/bytes (:file/bytes f)
       :download/content-type (str "text/plain; charset=" encoding)
       :download/filename fixed-width-filename
       :download/records (:file/records f)}
      {:download/status :refused :download/why (:file/why f)})))

(defn ->csv
  "The variable-length variant. Same fields, same validation, no padding, and
  the CSV trailer — which is a DIFFERENT record (`csv-trailer`).

  Rebuilt from the values `prepare` already resolved rather than by stripping
  the fixed-width text: un-padding a rendered record would need to know which
  pad characters were data.

  ⚠ The specification's own printed samples carry one more trailing comma
  than its item tables have fields (`8,1,1000,,` for a four-field trailer).
  This emits the field count the ITEM TABLES define, which is the normative
  part of the document; the discrepancy is recorded here and in
  `->json` rather than resolved, because resolving it needs an upload."
  [{:zengin/keys [status total-count total-amount origin lines]}]
  (when (= :ok status)
    (let [rows (concat
                [(render-csv-record (:header layout) origin)]
                (for [l lines] (render-csv-record (:data layout) (payee-values l)))
                [(render-csv-record csv-trailer
                                    {:total/count total-count
                                     :total/amount total-amount})
                 (render-csv-record (:end layout) {})])]
      (when (every? #(= :ok (:record/status %)) rows)
        (str (str/join line-terminator (map :record/text rows))
             line-terminator)))))

(def csv-sample-discrepancy
  "What was observed in the source document and NOT resolved.

  Emitted into the JSON companion so it travels with the file. A reader who
  finds the bank rejecting the CSV has the one open question in front of
  them rather than having to re-read the PDF."
  {:discrepancy/what
   (str "仕様書の見本行は、項目説明の表が定める項目数より読点が一つ多い"
        "（例: トレーラーは4項目だが見本は 8,1,1000,, ）")
   :discrepancy/what-this-emits "項目説明の表が定める項目数"
   :discrepancy/why-unresolved
   (str "どちらが受理されるかは実際にアップロードしないと分からない。"
        "この repository はアップロードしていない")})

(defn ->json
  "The machine-readable companion. Carries the source, the totals, the zero
  lines, the refusals and what is still unestablished — never an account
  number and never a payee name.

  That omission is `payroll.sensitive`'s rule and is deliberate even though
  the FILE itself carries both: the file goes to a bank over a channel
  somebody chose, and the JSON is what gets attached to a ticket."
  [{:zengin/keys [status employer-id period transfer-date total-count
                  total-amount zero-amount refused origin-missing
                  record-problems why]}]
  (text/json-document
   [[:document_type "zengin_soogoo_furikomi"]
    [:status (name status)]
    [:why why]
    [:employer_id employer-id]
    [:period period]
    [:transfer_date transfer-date]
    [:bank_code paypay-bank-code]
    [:record_length 120]
    [:encoding "Shift_JIS"]
    [:line_terminator "CRLF"]
    [:source (text/json-object-of
              [[:title (:source/title source)]
               [:url (:source/url source)]
               [:revised (:source/revised source)]
               [:read_at (:source/read-at source)]
               [:does_not_establish (:source/what-it-does-not-establish source)]])]
    [:csv_sample_discrepancy
     (text/json-object-of
      [[:what (:discrepancy/what csv-sample-discrepancy)]
       [:emitted (:discrepancy/what-this-emits csv-sample-discrepancy)]
       [:why_unresolved (:discrepancy/why-unresolved csv-sample-discrepancy)]])]
    [:total_count total-count]
    [:total_amount total-amount]
    [:zero_amount_lines
     (vec (for [z zero-amount]
            (text/json-object-of [[:contract_id (:contract-id z)]
                                  [:why (:why z)]])))]
    [:origin_missing
     (vec (for [m origin-missing]
            (text/json-object-of [[:key (:missing/key m)]
                                  [:label (:missing/label m)]
                                  [:why (:missing/why m)]])))]
    [:record_problems
     (vec (for [p record-problems]
            (text/json-object-of [[:field (:field/name p)]
                                  [:why (:field/why p)]])))]
    [:payees_refused
     (vec (for [r refused]
            (text/json-object-of [[:contract_id (:payee/contract-id r)]
                                  [:why (:payee/why r)]])))]]))
