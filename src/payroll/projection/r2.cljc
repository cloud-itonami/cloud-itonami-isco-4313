(ns payroll.projection.r2
  "The adapter contract for Cloudflare R2 Data Catalog, what a person measured
  against the live catalog by hand, and the distance between the two.

  ## No client, and no token, ever

  There is no HTTP here. What is here is the CONFIGURATION a driver needs,
  read from the environment by NAME, plus the request plan a driver would
  execute — so that an operator can see what would be sent before anything
  is, and so that a deployment can be checked without a credential.

  `R2_CATALOG_TOKEN` is **not read by this namespace**. `token-provider` is
  the name of where a token comes from, and `payroll.phase2-test` puts a
  recognisable secret in the environment and asserts it appears nowhere in
  the returned configuration.

  ## The 401 was real, is dated, and is resolved (2026-08-26)

  An earlier attempt on 2026-08-26 measured:

      create_namespace   → succeeded
      create_table       → **401**

  The namespace call is served by the catalog's own control plane; the table
  call additionally writes table metadata into the R2 bucket that backs the
  warehouse. A token with catalog permission and **no R2 storage permission**
  therefore passes the first and fails the second — which reads as
  `the token is wrong` when it is `the token is short of one permission`.

  That is `historical-blocker`, and it is kept because the diagnosis is the
  useful part: anybody who issues a catalog-only token reproduces it exactly.
  It was **resolved later the same day** by issuing a token carrying both
  *Workers R2 Data Catalog Edit* and *Workers R2 Storage Edit*. It is no
  longer a current fact about this repository, and this namespace no longer
  reports it as one.

  ## What was verified live, by hand, and what that does not establish

  `live-verification` records what a person did on 2026-08-26 through the
  documented PyIceberg REST operator path: the namespace and all three tables
  were created, and a clearly SYNTHETIC row was appended to each, read back as
  exactly one row, deleted, and read back as zero. No real payroll data was
  written.

  The token itself is recorded exactly, because every short summary of it so
  far has been wrong. It was issued with a **dashboard expiry of 2026-08-28**
  — a date it never reached. On **2026-08-26, after the verification, the
  operator deleted it in the Cloudflare dashboard** and confirmed the delete;
  the API-token list read back afterwards no longer contains the exact target
  `cloud-itonami-payroll-r2-provisioning-260826`. So `:credential/revoked?`
  is `true`, dated by `:credential/revoked-on`, and the issued expiry stays in
  the record as **issuance metadata** rather than as a forecast: what ended
  this credential was the deletion, not the calendar.

  This is a durable record, and a revocation is the one credential fact that
  is safe in one. `未失効` had to be dated because a credential that is live
  today can be dead tomorrow; a deleted token stays deleted, so `失効済み`
  cannot rot the way its opposite did. The date is kept regardless, because
  *when* — and specifically *before the expiry* — is the part an auditor
  reads. The remaining facts are local to the operator's machine and equally
  terminal: the value was never saved there, and the clipboard copy made to
  paste it was cleared after the verification.

  What that establishes is that the shape in `payroll.projection.schema` is
  one this catalog accepts and round-trips. **It is not a deployment and not
  a production verification of anything here**, and the four limits travel
  with the record rather than being left for a reader to reconstruct:

    · **nothing went through this repository.** `payroll.projection.catalog`
      was not on the path; a person ran PyIceberg. No write and no read-back
      has been made through the application adapter
    · this repository still **constructs no live catalog driver**, so
      `:projection-health` is nil in every deployment it ships
    · the token was one **operator's** — issued with an expiry of 2026-08-28
      and revoked on 2026-08-26, before it. It says nothing about whether any
      deployment's token carries those permissions
    · the **cutover gate is not satisfied**: no real MoneyForward cycle
      exists, so condition 6 has nothing to read back

  ## Preflight is configuration, and configuration is not a credential

  `preflight` answers exactly one question — *does this environment name the
  three things a driver would need* — and it is careful to answer no more,
  because a green preflight next to a red `create_table` is how the 401 above
  came to look like a mystery. It reads no token and makes no request, so it
  **cannot** tell an operator whether a build would succeed, and it does not
  return a field that could be mistaken for saying so.

  ## Compatible with the PyIceberg configuration an operator already has

  The three names below are the ones Cloudflare's own documentation and the
  PyIceberg REST catalog configuration use, so a deployment that already has
  a working `pyiceberg` setup can be pointed at this without renaming
  anything:

      R2_CATALOG_URI    the REST catalog endpoint
      R2_WAREHOUSE      `<account_id>_<bucket>`
      R2_CATALOG_TOKEN  read by the DRIVER, never by this repository"
  (:require [clojure.string :as str]
            [payroll.projection.schema :as schema]))

(def verified-on
  "The date the manual verification below was performed. One place, because a
  date repeated in five maps is a date four of which go stale."
  "2026-08-26")

(def token-expires-on
  "The dashboard expiry the operator's token was ISSUED with. **Issuance
  metadata, and nothing more** — the token did not live to this date.

  It is kept because it is what the dashboard was actually set to, and because
  it is what makes `token-revoked-on` legible: the token was deleted two days
  BEFORE this, so treating this date as *how long the credential existed*
  overstates it by two days. Nothing waits for this date and nothing here is
  scheduled against it.

  It is a date and not an adjective on purpose. `short-lived` was the word
  that stood here and was read as *gone by now* while the credential was in
  fact usable; the fix was never a better adjective, it was dates a reader can
  compare to each other.

  Not a secret: an expiry says when a credential would have stopped working,
  which is the opposite of saying what it is."
  "2026-08-28")

(def token-revoked-on
  "The day the operator's token was revoked — deleted in the Cloudflare
  dashboard, with the delete explicitly confirmed, after the verification
  below and before `token-expires-on`.

  This is the date `:credential/revoked? true` is scoped to. It carries its
  own name rather than reusing `verified-on`, even though they are the same
  day: a record that read the revocation off the verification date would move
  the revocation silently the next time somebody verified something.

  Not a secret, and not reversible. Everything else this namespace says about
  that credential is, from this date on, a sentence about the past."
  "2026-08-26")

(def token-dashboard-name
  "The dashboard NAME of the token that was revoked — the label in Cloudflare's
  API-token list, not the token.

  It is here so that the revocation names its exact target: `a token was
  deleted` is not checkable, and an operator holding several would not know
  which one this record is about. A name is what the list is indexed by, so it
  is also what re-reading the list after the delete could be checked against.

  Not a secret. A token's label is chosen to be readable in a list; the value
  is the part that never appears in this repository at all."
  "cloud-itonami-payroll-r2-provisioning-260826")

(def config-keys
  [{:config/env "R2_CATALOG_URI"
    :config/key :r2/catalog-uri
    :config/label "REST catalog エンドポイント"
    :config/secret? false
    :config/why "PyIceberg の REST catalog `uri` と同じ値"}
   {:config/env "R2_WAREHOUSE"
    :config/key :r2/warehouse
    :config/label "warehouse（<account_id>_<bucket>）"
    :config/secret? false
    :config/why "Iceberg のメタデータが載る R2 バケットを指す"}
   {:config/env "R2_CATALOG_TOKEN"
    :config/key :r2/token-provider
    :config/label "トークンの取得元の名前"
    :config/secret? true
    :config/why (str "値そのものはこの repository が読まない。"
                     "driver が読む。ここに来るのは"
                     "「どこから取るか」の名前だけである")}])

(def required-permissions
  "What a token must carry to build the projection, and what was observed of
  ONE token on one day.

  `:permission/observed` is deliberately not a bare `:granted`. It is a fact
  about ONE operator's token on `verified-on` — not a property of any
  deployment, and not a property of this repository, neither of which has ever
  held a token at all. That token was revoked on `token-revoked-on` — deleted
  before the `token-expires-on` it was issued with — which changes nothing
  here in either direction: a permission granted to it was never a permission
  granted to a deployment, and revoking it does not withdraw the measurement."
  [{:permission/scope "R2 Data Catalog"
    :permission/level "admin read & write（Workers R2 Data Catalog Edit）"
    :permission/why "create_namespace と create_table はどちらも書き込みである"
    :permission/observed :granted-to-one-operator-token
    :permission/observed-on verified-on}
   {:permission/scope "R2 storage（R2_WAREHOUSE が指すバケット）"
    :permission/level "object read & write（Workers R2 Storage Edit）"
    :permission/why (str "Iceberg のテーブルメタデータとマニフェストは"
                         "そのバケット上のオブジェクトである。"
                         "catalog 権限だけのトークンは "
                         "create_namespace を通し create_table で 401 になる")
    :permission/observed :granted-to-one-operator-token
    :permission/observed-on verified-on}])

(def historical-blocker
  "The 401, dated and RESOLVED. Kept, and kept as history.

  The diagnosis is the part worth keeping: a catalog-only token reproduces
  this exactly, so the next person to see `create_namespace ok / create_table
  401` can read the reason instead of re-deriving it. What it is not is a
  current fact about this repository, and `:blocker/resolved?` is in the map
  rather than in a comment so that nothing can render the diagnosis without
  also being able to render the resolution."
  {:blocker/observed-on verified-on
   :blocker/create-namespace :succeeded
   :blocker/create-table :http-401
   :blocker/diagnosis
   (str "トークンに R2 storage のデータプレーン権限が無かった。"
        "catalog の制御面だけで足りる create_namespace は通り、"
        "warehouse バケットへ書く create_table が 401 になった")
   :blocker/resolution
   (str "R2 Data Catalog の admin read & write と、"
        "R2_WAREHOUSE が指すバケットに対する object read & write の"
        "両方を持つトークンを発行する")
   :blocker/resolved? true
   :blocker/resolved-on verified-on
   :blocker/resolved-how
   (str "Workers R2 Data Catalog Edit と Workers R2 Storage Edit の"
        "両方を付与したトークンを発行し、同じ日に手作業で解消した")
   :blocker/scope-of-resolution
   (str "解消したのは担当者が発行した 1 本のトークンについてであって、"
        "配備のトークンについては何も述べていない。"
        "この repository はトークンを読まないので、"
        "権限が付いているかどうかをここから確かめることはできない")
   :blocker/still-true
   (str "この repository は 401 を再試行しない —— "
        "権限の不足は再試行で解けず、"
        "四回の失敗は一つの理由より読みにくい")})

(def live-verification
  "What a person did against the live catalog on `verified-on`, and what it
  does not establish.

  The limits are IN the record. A verification whose caveats live in a
  document beside it is a verification that gets quoted without them — and
  `作れることが確かめられた` is exactly the sentence somebody would otherwise
  lift out of here into a status report about a deployment."
  {:verification/on verified-on
   :verification/by :manual-operator
   :verification/path :pyiceberg-rest
   :verification/account "ai-gftd-cloud"
   :verification/warehouse-bucket "cloud-itonami-datalake"
   :verification/namespace schema/namespace-name
   ;; the shape that was actually created, recorded independently of
   ;; `schema/tables` so that the two can be compared. A column added to the
   ;; schema after this date makes the live tables no longer the tables that
   ;; were verified, and `payroll.phase2-test` fails rather than letting the
   ;; date go on standing for a shape it never saw.
   :verification/tables
   [{:table/name "payroll_run_projection"
     :table/columns 19
     :table/partition-by ["employer_id" "period"]}
    {:table/name "parallel_reconciliation_projection"
     :table/columns 14
     :table/partition-by ["employer_id" "period"]}
    {:table/name "resident_tax_notice_projection"
     :table/columns 12
     :table/partition-by ["employer_id" "tax_year"]}]
   :verification/partition-kind :identity
   ;; append 1 → read back 1 → delete → read back 0, per table.
   :verification/row-kind :synthetic-not-real
   :verification/rows-appended 1
   :verification/rows-read-back 1
   :verification/rows-after-delete 0
   :verification/real-payroll-data-written? false
   ;; `token-handling` and not `token`: `payroll.sensitive` blocks any key
   ;; whose tail is `token`, so a key by that name would be silently dropped
   ;; by `payroll.operations/redact` — and this value is a description of how
   ;; a credential was handled, which is the opposite of a credential. The
   ;; nested keys are `:credential/…` for the same reason: `redact` walks
   ;; recursively, so a caveat nested under a blocked name is a caveat that
   ;; leaves the screen.
   ;;
   ;; This was `:short-lived-not-persisted`, one keyword asserting two things
   ;; that are not true. The token carried a dashboard expiry of
   ;; `token-expires-on`; and `not persisted` claimed of Cloudflare's records
   ;; something only this machine can answer. Fields, each separately
   ;; checkable, replace it — and none of them is the value, which is the one
   ;; thing that is nowhere.
   ;;
   ;; `:credential/revoked?` was the one fact here that DRIFTED, and it is no
   ;; longer: the token was deleted on `token-revoked-on`, and a deleted token
   ;; stays deleted. It keeps its dates anyway. `:credential/revoked-on` is
   ;; the act; `:credential/revoked-observed-on` is the list being re-read
   ;; afterwards, which is what makes the act checkable rather than asserted;
   ;; `:credential/revoked-before-expiry?` is what stops `token-expires-on`
   ;; from being read as the end of this credential when the delete was.
   :verification/token-handling
   {:credential/dashboard-name token-dashboard-name
    :credential/expires-on token-expires-on
    :credential/expiry-source :cloudflare-dashboard
    :credential/expiry-is-issuance-metadata? true
    :credential/expiry-reached? false
    :credential/revoked? true
    :credential/revoked-on token-revoked-on
    :credential/revoked-observed-on token-revoked-on
    :credential/revoked-before-expiry? true
    :credential/revocation-method :cloudflare-dashboard-delete
    :credential/revocation-confirmed? true
    ;; how the delete was checked rather than assumed. A dashboard button that
    ;; was clicked is not a revocation; a list that no longer contains the
    ;; named target is, and the name is what distinguishes the target from
    ;; every other token in that account.
    :credential/revocation-verified-how
    (str "dashboard の削除確認に明示的に同意したうえで削除し、"
         "その後 API トークン一覧を読み直して、"
         "対象である " token-dashboard-name " が"
         "一覧に存在しないことを確かめた")
    :credential/value-saved-locally? false
    :credential/local-clipboard-cleared? true
    ;; stated as what IS true, and dated where the truth is dated. The
    ;; discarded adjective is not quoted here even to deny it: a denial
    ;; survives a screenshot as the word itself.
    :credential/why
    (str "このトークン（" token-dashboard-name "）は "
         token-expires-on " の期限で発行されたが、"
         "その期限より前の " token-revoked-on " に、検証を終えたのち "
         "Cloudflare の dashboard で削除して失効させた。"
         "削除後に API トークン一覧を読み直し、"
         "対象が一覧に存在しないことを確認している。"
         "期限は発行時に設定した値としてのみ残してあり、"
         "このトークンが終わったのは期限ではなく削除による。"
         "保存について言えるのはこの機械の側だけで、"
         "値は一度も保存しておらず、"
         "貼り付けに使ったクリップボードは検証後に消去した。"
         "値そのものはこの repository のどこにも無い")}
   ;; ---------------------------------------------------------------------
   ;; The four limits, each of which somebody could otherwise read past.
   ;; ---------------------------------------------------------------------
   :verification/through-this-repository? false
   :verification/deployed? false
   :verification/production-verified? false
   :verification/satisfies-cutover-gate? false
   :verification/limits
   [(str "この repository を通っていない。"
         "担当者が PyIceberg を実行したのであって、"
         "payroll.projection.catalog は経路上に無い —— "
         "application adapter を通した書き込みも読み戻しも一度も無い")
    (str "この repository は live な catalog driver を構築しない。"
         "出荷される配備では :projection-health は nil である")
    (str "確かめたのは担当者のトークン 1 本についてであって、"
         "配備のトークンについてではない。"
         "そのトークンは " token-expires-on " の期限で発行されたが、"
         "期限より前の " token-revoked-on " に削除して失効させてある")
    (str "実在の MoneyForward 並行サイクルは 1 件も無いので、"
         "切り替えの 6 条件のうち 6 番目は読み戻す対象を持たない。"
         "これは切り替えの条件を満たしていない")]
   :verification/why
   (str "payroll.projection.schema の表の形を、"
        "この catalog が受け取り往復させられることが確かめられた。"
        "確かめられたのはそれだけである")})

(defn read-config
  "The environment → a configuration or a refusal. **Never reads the token.**

  `:r2/token-provider` is the NAME the deployment gave, and a deployment that
  put the token itself in `R2_CATALOG_TOKEN` gets the name it chose reported
  back as `:provided-by-environment` rather than the value — which is the
  only way this function can be safe to print."
  [env]
  (let [get* (fn [k] (some-> (get env k) str str/trim not-empty))
        missing (vec (for [c config-keys
                           :when (nil? (get* (:config/env c)))]
                       c))]
    (if (seq missing)
      {:r2/status :refused
       :r2/missing missing
       :r2/why (str "R2 Data Catalog の設定が " (count missing) " 件足りない: "
                    (str/join "、" (map :config/env missing)))
       :r2/required-permissions required-permissions}
      {:r2/status :ok
       :r2/catalog-uri (get* "R2_CATALOG_URI")
       :r2/warehouse (get* "R2_WAREHOUSE")
       ;; the presence of a token, and nothing about its value.
       :r2/token-provider :provided-by-environment
       :r2/token-read-here? false
       :r2/required-permissions required-permissions
       :r2/historical-blocker historical-blocker
       :r2/live-verification live-verification})))

(defn request-plan
  "What a driver would send, as data. No request is made.

  `:request/observed` is what the equivalent call did on `verified-on` when a
  person made it BY HAND through PyIceberg — which is why the two companion
  keys are on every row. A plan whose `:succeeded` could be read as *this
  repository has done this* would be claiming the one thing that has not
  happened."
  [{:r2/keys [catalog-uri warehouse]} tables]
  (let [observed {:request/observed :succeeded
                  :request/observed-on verified-on
                  :request/observed-how :manual-pyiceberg-rest
                  :request/observed-through-this-repository? false}]
    (into [(merge {:request/step :create-namespace
                   :request/method :post
                   :request/path (str catalog-uri "/v1/" warehouse "/namespaces")
                   :request/needs "R2 Data Catalog: admin read & write"}
                  observed)]
          (for [t tables]
            (merge {:request/step :create-table
                    :request/method :post
                    :request/path (str catalog-uri "/v1/" warehouse "/namespaces/"
                                       (str/join "%1F" (:table/namespace t))
                                       "/tables")
                    :request/table (:table/name t)
                    :request/needs (str "R2 Data Catalog: admin read & write、"
                                        "および R2 storage: object read & write")}
                   observed)))))

(defn preflight
  "Whether this environment NAMES what a driver would need. Nothing more.

  Two states, and both of them are about configuration: the variables are
  missing, or they are present. There is deliberately **no
  `:preflight/ready?`** — this function reads no token and makes no request,
  so `ready` is a question it cannot answer, and a boolean that answered it
  anyway would be the failure the 401 in `historical-blocker` already caused
  once. `:preflight/verifies-credentials?` is `false` in the returned map
  rather than only in this docstring, so a caller rendering the result
  carries the caveat whether or not it read the source.

  `payroll.operations/projection-section` puts this beside the catalog's own
  health, which is the thing that CAN answer `is it built` — and is nil in
  every deployment this repository ships."
  [env]
  (let [c (read-config env)
        base {:preflight/verifies-credentials? false
              :preflight/makes-request? false
              :preflight/required-permissions required-permissions}]
    (if (= :refused (:r2/status c))
      (merge base
             {:preflight/reason :configuration-missing
              :preflight/configuration-complete? false
              :preflight/why (:r2/why c)
              :preflight/missing (:r2/missing c)})
      (merge base
             {:preflight/reason :configuration-present
              :preflight/configuration-complete? true
              :preflight/missing []
              :preflight/config (dissoc c :r2/historical-blocker
                                        :r2/live-verification)
              :preflight/plan (request-plan c schema/tables)
              :preflight/history historical-blocker
              :preflight/live-verification live-verification
              :preflight/why
              (str "三つの設定は揃っている。"
                   "揃っていることは、作れることではない —— "
                   "この repository はトークンを読まず、要求も送らないので、"
                   "この配備のトークンに権限があるかどうかは"
                   "ここでは確かめていない。"
                   "手作業では " verified-on " に "
                   "namespace と三つの表が作られ、"
                   "各表に架空の 1 行を追記して読み戻し、"
                   "削除して 0 行を読み戻すところまで確かめられているが、"
                   "それはこの repository を通っていない")}))))
