(ns payroll.kotobase.http
  "The HTTP shape of a kotobase.net deployment — built here, sent elsewhere.

  ## What this namespace is, and what it deliberately is not

  `payroll.kotobase.transport/Transport` is four operations, and until now a
  deployment had to write all four AND the HTTP client AND the URL scheme
  AND the header discipline. That is the seam being open too wide: the parts
  a deployment genuinely owns are the credential and the socket, and every
  other part is the same for every deployment and should be written once,
  here, where it is testable.

  So this namespace does everything except send:

  - `read-config` validates the endpoint the same way
    `payroll.host.config` validates everything else — fail-closed, naming
    each gap
  - `request-for` turns one `Transport` operation into a request VALUE
  - `transport` is a real `Transport` over an injected `send!`

  **It performs no I/O and this repository ships no `send!`.** A deployment
  supplies one function of one argument. That keeps the network out of the
  test suite (the reason `payroll.kotobase.transport` gives), and it keeps
  the choice of HTTP client — and its timeout, retry and TLS configuration —
  where it belongs, which is with whoever operates the node.

  ## No credential appears in this file, and none can be logged from it

  There is no token, no key, no default endpoint and no fallback host. The
  credential arrives as `auth`, a zero-argument function the deployment
  supplies, and it is called **once per request**, at the moment the header
  is built. It is never stored on the transport, never put in the URL, and
  never returned from `describe`.

  `redact` is the only way a request should ever be printed. It replaces the
  authorisation header's value with the number of characters it had, which
  is enough to tell `the provider returned nothing` from `the provider
  returned something` — the distinction an operator debugging a 401 needs —
  and is not enough to replay.

  A credential in a URL was the alternative and is refused explicitly:
  `read-config` rejects an endpoint carrying userinfo, because URLs reach
  access logs, proxy logs and error messages, and none of those was chosen
  by the person who chose the secret.

  ## The paths are transcribed, and are NOT verified

  kotobase's canonical surfaces are CID-native: an immutable block plane and
  a ref plane whose head moves under compare-and-set (CLAUDE.md,
  ADR-2608039000). `paths` below is this repository's reading of that shape.
  **No request built here has ever been sent**, so every path, every header
  name and every status-code mapping is a transcription that a first
  deployment will correct. They are named constants rather than string
  literals in five places for exactly that reason."
  (:require [clojure.string :as str]
            [payroll.digest :as digest]
            [payroll.kotobase.transport :as transport]))

(def config-keys
  "What a deployment supplies, and which of them is a secret.

  `:secret? true` on exactly one row, and that row is NOT read from the
  environment — it names the PROVIDER, and the value comes from calling it."
  [{:config/key :endpoint :config/env "PAYROLL_KOTOBASE_ENDPOINT"
    :config/label "kotobase エンドポイント" :config/secret? false
    :config/why "https の絶対 URL。末尾のスラッシュは正規化する"}
   {:config/key :tenant :config/env "PAYROLL_KOTOBASE_TENANT"
    :config/label "テナント識別子" :config/secret? false
    :config/why "すべての path とヘッダに載る。無ければ鍵空間を共有する"}
   {:config/key :auth :config/env "PAYROLL_KOTOBASE_AUTH"
    :config/label "資格情報プロバイダの名前" :config/secret? true
    :config/why (str "資格そのものではなく、どこから取るかの名前。"
                     "値はリクエストを組む瞬間にプロバイダを呼んで得る")}])

(def paths
  "The four operations as path templates. TRANSCRIBED, NOT VERIFIED — see the
  namespace docstring."
  {:put-block {:method :put :template "/ipfs/:cid"
               :path/why "raw CIDv1 の block を、その内容アドレスの下に置く"}
   :get-block {:method :get :template "/ipfs/:cid"}
   :read-head {:method :get :template "/refs/:ref"}
   :cas-head {:method :put :template "/refs/:ref"
              :path/why (str "If-Match が compare-and-set である。"
                             "無条件 PUT は last-write-wins であり、"
                             "同時に走った二つの run のうち一方が消える")}})

(def headers
  "Header names, once. `authorization` is the only one carrying a secret and
  is the only one `redact` touches."
  {:auth "Authorization"
   :tenant "X-Kotobase-Tenant"
   :content-type "Content-Type"
   :if-match "If-Match"
   :if-none-match "If-None-Match"})

(def block-content-type "application/vnd.ipld.raw")

(defn- trim-slash [s] (str/replace (str s) #"/+$" ""))

(defn read-config
  "An environment map plus an auth provider → a validated endpoint config, or
  a refusal naming every gap.

    (read-config env {:auth (fn [] …) :send! (fn [req] …)})
    => {:http/status :ok :http/endpoint … :http/tenant … :http/auth … :http/send! …}
    => {:http/status :refused :http/gaps [{:gap/key :gap/why}] :http/why …}

  Every gap is reported, not the first: a deployment told `something is
  wrong` fixes one thing and runs again.

  The endpoint checks are the ones whose absence is silent:

  - **`https` only.** `http` would put the ledger and the credential on the
    wire in the clear, and it would work, which is the problem.
  - **No userinfo.** `https://tok@node/` is a credential in every access log
    between here and there.
  - **No query and no fragment.** They would be silently dropped when the
    path template is appended, and the deployment would think it had
    configured something.

  ⚠ This validates the SHAPE of a deployment. It does not establish that the
  endpoint exists, answers, or is kotobase — nothing here has ever sent a
  request. `payroll.host.config`'s durability rule applies unchanged: what
  the node does is the node's claim to make."
  [env {:keys [auth send!]}]
  (let [get* (fn [k] (some-> (get env k) str str/trim not-empty))
        raw (get* "PAYROLL_KOTOBASE_ENDPOINT")
        tenant (get* "PAYROLL_KOTOBASE_TENANT")
        gaps
        (cond-> []
          (nil? raw)
          (conj {:gap/key :endpoint
                 :gap/why "PAYROLL_KOTOBASE_ENDPOINT が設定されていない"})

          (and raw (not (re-matches #"(?i)https://[^/@?#\s]+(/[^?#\s]*)?" raw)))
          (conj {:gap/key :endpoint
                 :gap/why
                 (cond
                   (re-find #"(?i)^http://" raw)
                   (str "エンドポイントが http である。"
                        "給与の台帳と資格情報が平文で流れる —— "
                        "しかも動いてしまうので、誰も気づかない")
                   (re-find #"@" raw)
                   (str "エンドポイントに userinfo（URL 内の資格情報）が入って"
                        "いる。URL は access log にも proxy log にも"
                        "エラー本文にも出る。"
                        "そのどれも、秘密を選んだ人が選んだ場所ではない")
                   (re-find #"[?#]" raw)
                   (str "エンドポイントに query か fragment が入っている。"
                        "path を継ぎ足すときに黙って落ちるので、"
                        "設定したつもりの配備ができる")
                   :else
                   (str "エンドポイント " (pr-str raw)
                        " が https の絶対 URL ではない"))})

          (nil? tenant)
          (conj {:gap/key :tenant
                 :gap/why "PAYROLL_KOTOBASE_TENANT が設定されていない"})

          (not (ifn? auth))
          (conj {:gap/key :auth
                 :gap/why (str "資格情報プロバイダ（引数ゼロの関数）が"
                               "注入されていない。"
                               "この repository は資格情報を同梱しない")})

          (not (ifn? send!))
          (conj {:gap/key :send!
                 :gap/why (str "HTTP を実際に送る関数が注入されていない。"
                               "この repository は network に到達する実装を"
                               "同梱していない —— timeout も再試行も TLS 設定も"
                               "node を運用する側のものである")}))]
    (if (seq gaps)
      {:http/status :refused
       :http/gaps gaps
       :http/why (str/join "。" (map :gap/why gaps))}
      {:http/status :ok
       :http/endpoint (trim-slash raw)
       :http/tenant tenant
       :http/auth auth
       :http/send! send!})))

(defn- fill [template subs*]
  (reduce (fn [t [k v]] (str/replace t (str k) (str v))) template subs*))

(defn request-for
  "One `Transport` operation as a request VALUE.

    (request-for cfg :put-block {:cid … :bytes […]})
    => {:request/method :put
        :request/url \"https://node/ipfs/bafk…\"
        :request/headers {\"Authorization\" \"…\" \"X-Kotobase-Tenant\" \"…\"}
        :request/body-bytes […]}

  The credential is fetched HERE, by calling `:http/auth`, and goes into the
  header and nowhere else. It is not cached on the config and not stored on
  the transport, so a provider that rotates is followed without a restart and
  a heap dump of an idle process holds no token.

  A provider returning nil produces a request with NO authorization header
  rather than one carrying `\"\"` or `\"Bearer null\"`. The node will answer
  401 either way; only the first is legible in a log."
  [{:http/keys [endpoint tenant auth]} op {:keys [cid ref bytes expected]}]
  (let [{:keys [method template]} (get paths op)
        credential (when auth (auth))]
    (cond-> {:request/op op
             :request/method method
             :request/url (str endpoint (fill template {":cid" cid ":ref" ref}))
             :request/headers
             (cond-> {(:tenant headers) tenant}
               (and credential (seq (str credential)))
               (assoc (:auth headers) (str credential))
               (= :put-block op) (assoc (:content-type headers)
                                        block-content-type)
               ;; nil `expected` means "this ref must not exist yet", which is
               ;; `If-None-Match: *` and NOT an absent precondition. An absent
               ;; precondition is an unconditional PUT, and an unconditional
               ;; PUT on a head is the last-write-wins this whole design
               ;; exists to avoid.
               (and (= :cas-head op) (nil? expected))
               (assoc (:if-none-match headers) "*")
               (and (= :cas-head op) (some? expected))
               (assoc (:if-match headers) (str expected)))}
      ;; The CAS body is the PROPOSED head and is supplied by `cas-head!`,
      ;; which is the only caller that has it. Building an empty one here
      ;; would produce a request that looks complete and moves a ref to
      ;; nothing.
      (= :put-block op) (assoc :request/body-bytes (vec bytes)))))

(defn redact
  "A request as something safe to print. The only correct way to log one.

  The authorisation header becomes `{:redacted/chars n}` — enough to tell
  `the provider returned nothing` from `the provider returned something`,
  which is what an operator debugging a 401 needs, and not enough to replay.
  The body becomes its length and its content address rather than its bytes,
  because a block body is a sealed payroll payload."
  [req]
  (-> req
      (update :request/headers
              (fn [h]
                (cond-> h
                  (contains? h (:auth headers))
                  (assoc (:auth headers)
                         {:redacted/chars (count (str (get h (:auth headers))))}))))
      (cond-> (:request/body-bytes req)
        (-> (assoc :request/body-length (count (:request/body-bytes req))
                   :request/body-cid (digest/cid (:request/body-bytes req)))
            (dissoc :request/body-bytes)))))

(def retryable-statuses
  "HTTP statuses a caller may retry. 429 and the 5xx family, and nothing
  else — a 4xx is a request this deployment should not repeat."
  #{429 500 502 503 504})

(defn transport
  "A `payroll.kotobase.transport/Transport` over an injected `send!`.

  `send!` takes a request value (`request-for`'s output) and returns
  `{:response/status n :response/body-bytes […] :response/headers {…}}`, or
  `{:response/error \"…\"}` when it could not send at all.

  **A response this function cannot interpret is a REFUSAL, never a
  success.** That is the whole reason the mapping is written here rather
  than being left to each deployment: an unrecognised status is the shape
  `payroll.store.kotobase` most needs to fail closed on, and a deployment
  writing its own adapter under time pressure writes `(= 200 status)` and
  treats everything else as missing — which turns a 500 on the ledger into
  an empty ledger.

  `:transport/durable? true` is asserted here and is the ONE claim in this
  namespace that is not a transcription: a deployment that has wired a real
  kotobase node is asserting durability by doing so. It is still the
  deployment's claim and not this repository's — nothing here has measured
  it, and `payroll.host.config/durability` reports it as the transport's
  answer rather than as a fact."
  [{:http/keys [endpoint tenant] :as cfg}]
  (reify transport/Transport
    (put-block! [_ _tenant cid block-bytes]
      (let [r ((:http/send! cfg) (request-for cfg :put-block
                                              {:cid cid :bytes block-bytes}))
            st (:response/status r)]
        (cond
          (:response/error r)
          {:block/status :refused :block/why (:response/error r)}
          ;; 200/201 written, 409 already there under this address — and the
          ;; bytes ARE the address, so that is a success and not a conflict.
          (contains? #{200 201 204 409} st) {:block/status :ok :block/cid cid}
          :else {:block/status :refused
                 :block/why (str "block の書き込みに HTTP " st " が返った")})))

    (get-block [_ _tenant cid]
      (let [r ((:http/send! cfg) (request-for cfg :get-block {:cid cid}))
            st (:response/status r)]
        (cond
          (:response/error r) {:block/status :missing}
          (= 200 st) {:block/status :ok
                      :block/bytes (vec (:response/body-bytes r))}
          (= 404 st) {:block/status :missing}
          :else {:block/status :missing})))

    (read-head [_ _tenant ref]
      (let [r ((:http/send! cfg) (request-for cfg :read-head {:ref ref}))
            st (:response/status r)]
        (cond
          (:response/error r)
          {:head/status :refused :head/why (:response/error r)}
          ;; 404 is `no ref yet`, which is a real and different answer from
          ;; `could not read`. Collapsing them is how an unreachable node
          ;; reports an employer who has filed nothing.
          (= 404 st) {:head/status :ok :head/cid nil}
          (= 200 st) {:head/status :ok
                      :head/cid (some-> (:response/body-bytes r)
                                        digest/utf8-string
                                        str/trim
                                        not-empty)}
          :else {:head/status :refused
                 :head/why (str "head の読み出しに HTTP " st " が返った")})))

    (cas-head! [_ _tenant ref expected proposed]
      (let [req (assoc (request-for cfg :cas-head
                                    {:ref ref :expected expected})
                       :request/body-bytes (digest/utf8-bytes (str proposed)))
            r ((:http/send! cfg) req)
            st (:response/status r)]
        (cond
          (:response/error r)
          {:cas/status :refused :cas/why (:response/error r)}
          (contains? #{200 201 204} st) {:cas/status :ok}
          (contains? #{409 412} st)
          {:cas/status :conflict
           :cas/actual (some-> (:response/body-bytes r)
                               digest/utf8-string str/trim not-empty)}
          :else {:cas/status :refused
                 :cas/why (str "head の compare-and-set に HTTP " st
                               " が返った。この書き込みは行われていない")})))

    (describe [_]
      {:transport/kind :kotobase-http
       :transport/endpoint endpoint
       :transport/tenant tenant
       :transport/durable? true
       :transport/paths-verified? false
       :transport/why
       (str "注入された send! で kotobase.net に HTTP で到達する。"
            "path とヘッダはこの repository の読み取りであって、"
            "実際に送って確かめたものではない"
            "（payroll.kotobase.http/paths）")})))
