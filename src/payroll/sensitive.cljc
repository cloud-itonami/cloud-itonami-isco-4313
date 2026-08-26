(ns payroll.sensitive
  "One vocabulary of what must not leave this actor, read by everything that
  has a boundary.

  Three surfaces can leak a payroll record and each of them was, until this
  namespace existed, deciding for itself what `sensitive` meant:

    · the analytical projection (`payroll.projection.*`) ships rows to a
      catalog outside the operational store
    · the operator/health surfaces (`payroll.host.config/health`,
      `payroll.kotobase.transport/describe`) print what a deployment is
    · the statutory artifacts (`payroll.artifact.gensen`) legitimately CARRY
      a My Number and an address, and must therefore never be logged and
      never be projected

  Three places deciding separately is three chances for one of them to be
  wrong, and the one that is wrong is discovered by somebody reading a log.

  ## Two different rules, not one

  `forbidden-outside` is what may never leave the operational store at all —
  the identifiers that make a row a person. `never-logged` is a superset: it
  additionally covers things that may be exported to a bank or a tax office
  in a document, but must not appear in a log line, a health body, an error
  message or a projection.

  A key is matched by its NAME, not by its value, and the match is on the
  qualified keyword and on the bare name. A projection driver renaming
  `:bank/account-number` to `\"account_number\"` on its way out is exactly the
  move this check has to survive, so `blocked-name?` normalises both."
  (:require [clojure.string :as str]))

(def forbidden-outside
  "Keys whose VALUE may never leave the operational store.

  Each entry says what it is and why it is here, because a denylist whose
  entries have no reasons is a denylist somebody eventually shortens."
  [{:sensitive/key :bank/account-number
    :sensitive/label "口座番号"
    :sensitive/why "口座番号と支店コードが揃えば、その口座に振り込める"}
   {:sensitive/key :bank/branch-code
    :sensitive/label "支店コード"
    :sensitive/why "口座番号と組で口座を一意にする"}
   {:sensitive/key :bank/payee-name-kana
    :sensitive/label "受取人名（口座名義）"
    :sensitive/why "口座名義は本人特定に足りる"}
   {:sensitive/key :employment/my-number
    :sensitive/label "個人番号（マイナンバー）"
    :sensitive/why (str "番号法 第十九条 は特定個人情報の提供を限定列挙し、"
                        "分析用の複製はそのいずれでもない")}
   {:sensitive/key :employment/address
    :sensitive/label "住所"
    :sensitive/why "住所は単独で個人を特定しうる"}
   {:sensitive/key :contract/worker
    :sensitive/label "従業員名"
    :sensitive/why (str "氏名は分析に不要であり、契約 ID の擬名で足りる。"
                        "名前を出した時点で行は個人データになる")}
   {:sensitive/key :employment/employee-name
    :sensitive/label "従業員氏名"
    :sensitive/why "同上"}
   {:sensitive/key :ts/worker
    :sensitive/label "勤怠の従業員名"
    :sensitive/why "同上"}])

(def auth-keys
  "Credential-shaped keys. Never logged, never projected, never described —
  and not part of `forbidden-outside` only because they are not payroll data:
  they are the thing that would let somebody else read all of it."
  [:auth/token :auth/secret :auth/bearer :auth/password :auth/api-key
   :r2/token :kotobase/token :envelope/key :envelope/secret])

(def never-logged
  "Everything in `forbidden-outside`, plus the credentials, plus the amounts.

  Amounts are here and NOT in `forbidden-outside` because the projection's
  whole purpose is to reconcile figures, so a total must be able to leave —
  what must not is a figure attached to a name. A LOG line is different: a
  payroll amount in a log is a payroll amount in whatever ships logs, and
  nobody chose that."
  (into (into (mapv :sensitive/key forbidden-outside) auth-keys)
        [:gross :net :income-tax-withheld :health-insurance-withheld
         :care-insurance-withheld :employees-pension-withheld
         :employment-insurance-withheld :resident-tax-withheld]))

(defn- normalise
  "A key as a comparable name: `:bank/account-number`, `\"account_number\"`
  and `\"accountNumber\"` all reduce to `bankaccountnumber` / `accountnumber`.

  Underscores and hyphens are removed rather than translated, because a
  driver that renames on the way out is the case this has to catch."
  [k]
  (-> (cond
        (keyword? k) (str (namespace k) "/" (name k))
        :else (str k))
      str/lower-case
      (str/replace #"[-_\s]" "")))

(defn- tail [k] (last (str/split (normalise k) #"/")))

(defn- blocked?
  [blocked k]
  (let [n (normalise k) t (tail k)]
    (boolean (some (fn [b]
                     (let [bn (normalise b) bt (tail b)]
                       (or (= n bn) (= t bt) (= n bt) (= t bn))))
                   blocked))))

(defn blocked-name?
  "Would this key carry something that must not leave the operational store?"
  [k]
  (blocked? (map :sensitive/key forbidden-outside) k))

(defn loggable-name?
  "May a value under this key appear in a log line, a health body or an error?"
  [k]
  (not (blocked? never-logged k)))

(defn violations
  "Every key of `m` that must not leave. Returns `[{:sensitive/key
  :sensitive/label :sensitive/why} …]`, empty when the map is clean.

  Reported rather than stripped. A projection that silently dropped a
  forbidden column would produce a row that looks de-identified and a caller
  who believes the column was never there; the row this actor emits is
  refused instead, and the refusal names the column."
  [m]
  (vec (for [k (keys m)
             :when (blocked-name? k)]
         (let [hit (first (filter #(blocked? [(:sensitive/key %)] k)
                                  forbidden-outside))]
           {:sensitive/key k
            :sensitive/label (:sensitive/label hit)
            :sensitive/why (:sensitive/why hit)}))))

(defn log-violations
  "Every key of `m` that must not be logged. Same shape, wider net."
  [m]
  (vec (for [k (keys m) :when (not (loggable-name? k))]
         {:sensitive/key k
          :sensitive/why "この鍵の値はログ・健全性応答・エラー本文に出してはならない"})))
