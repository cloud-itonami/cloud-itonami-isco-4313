(ns payroll.projection.r2
  "The adapter contract for Cloudflare R2 Data Catalog, and the exact
  permission this deployment does not have.

  ## No client, and no token, ever

  There is no HTTP here. What is here is the CONFIGURATION a driver needs,
  read from the environment by NAME, plus the request plan a driver would
  execute — so that an operator can see what would be sent before anything
  is, and so that `tools/r2-catalog-preflight.cljs` can check a deployment
  without a credential.

  `R2_CATALOG_TOKEN` is **not read by this namespace**. `token-provider` is
  the name of where a token comes from, and `payroll.projection.r2-test`
  puts a recognisable secret in the environment and asserts it appears
  nowhere in the returned configuration.

  ## The live blocker, stated exactly

  Measured against the live catalog:

      create_namespace   → succeeded
      create_table       → **401**

  The namespace call is served by the catalog's own control plane; the table
  call additionally writes table metadata into the R2 bucket that backs the
  warehouse. A token with catalog permission and **no R2 storage permission**
  therefore passes the first and fails the second — which reads as
  `the token is wrong` when it is `the token is short of one permission`.

  **What the token needs is both:**

    · an **R2 Data Catalog** permission that is read-WRITE (admin-read-write),
      not read-only — `create_namespace` and `create_table` are writes
    · an **R2 storage** permission over the bucket named by `R2_WAREHOUSE`,
      at object read-and-write — Iceberg table metadata and manifests are
      objects in that bucket

  A token scoped to the catalog alone reproduces exactly the observed
  behaviour. Until a token carrying both is issued, `production-verified`
  stays false and `docs/maturity.md` says so.

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
  "What the token must carry. Both rows, and the second is the one that is
  missing today."
  [{:permission/scope "R2 Data Catalog"
    :permission/level "admin read & write"
    :permission/why "create_namespace と create_table はどちらも書き込みである"
    :permission/observed :granted}
   {:permission/scope "R2 storage（R2_WAREHOUSE が指すバケット）"
    :permission/level "object read & write"
    :permission/why (str "Iceberg のテーブルメタデータとマニフェストは"
                         "そのバケット上のオブジェクトである。"
                         "catalog 権限だけのトークンは "
                         "create_namespace を通し create_table で 401 になる")
    :permission/observed :missing}])

(def observed-blocker
  {:blocker/create-namespace :succeeded
   :blocker/create-table :http-401
   :blocker/diagnosis
   (str "トークンに R2 storage のデータプレーン権限が無い。"
        "catalog の制御面だけで足りる create_namespace は通り、"
        "warehouse バケットへ書く create_table が 401 になる")
   :blocker/resolution
   (str "R2 Data Catalog の admin read & write と、"
        "R2_WAREHOUSE が指すバケットに対する object read & write の"
        "両方を持つトークンを発行する")
   :blocker/until-then
   (str "docs/maturity.md の R2 投影は deployed / production-verified とも"
        "false のままである。この repository は 401 を再試行しない —— "
        "権限の不足は再試行で解けず、"
        "四回の失敗は一つの理由より読みにくい")})

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
       :r2/observed-blocker observed-blocker})))

(defn request-plan
  "What a driver would send, as data. No request is made.

  Useful in two places: the operator script prints it so a deployment can be
  checked against a token's actual permissions before anything is created,
  and `payroll.projection.r2-test` asserts that every entry is a REST path
  and that none of them carries a credential."
  [{:r2/keys [catalog-uri warehouse]} tables]
  (into [{:request/step :create-namespace
          :request/method :post
          :request/path (str catalog-uri "/v1/" warehouse "/namespaces")
          :request/needs "R2 Data Catalog: admin read & write"
          :request/observed :succeeded}]
        (for [t tables]
          {:request/step :create-table
           :request/method :post
           :request/path (str catalog-uri "/v1/" warehouse "/namespaces/"
                              (str/join "%1F" (:table/namespace t)) "/tables")
           :request/table (:table/name t)
           :request/needs (str "R2 Data Catalog: admin read & write、"
                               "および R2 storage: object read & write")
           :request/observed :http-401})))

(defn preflight
  "Everything an operator needs to decide whether this deployment can build
  the projection, without making a request.

  Returns `:preflight/ready? false` today and says why. The three states are
  distinct: configuration missing, configuration present but the known
  permission gap unresolved, and ready."
  [env]
  (let [c (read-config env)]
    (if (= :refused (:r2/status c))
      {:preflight/ready? false
       :preflight/reason :configuration-missing
       :preflight/why (:r2/why c)
       :preflight/missing (:r2/missing c)
       :preflight/required-permissions required-permissions}
      {:preflight/ready? false
       :preflight/reason :permission-gap-unresolved
       :preflight/config (dissoc c :r2/observed-blocker)
       :preflight/plan (request-plan c schema/tables)
       :preflight/blocker observed-blocker
       :preflight/why
       (str "設定は揃っている。ただし create_table は 401 で失敗すると"
            "実測されており、原因はトークンに R2 storage の権限が"
            "無いことである。"
            "この repository はトークンを読まないので、"
            "権限が付いたかどうかをここから確かめることはできない —— "
            "確かめる方法は driver に実際に作らせて健全性を読み戻すことだけである")})))
