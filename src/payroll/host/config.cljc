(ns payroll.host.config
  "What a deployment must say before this actor will listen on a socket.

  Portable and pure: it takes a plain environment map and returns either a
  configuration or a refusal, so every decision about whether a deployment is
  safe is testable without starting a process.

  ## Every default is the safe one, and the unsafe ones have no default

  | variable | absent | why |
  |---|---|---|
  | `PAYROLL_STORE` | REFUSE | `payroll.edge.endpoints/store-mode` already refuses to guess a backend; an empty in-process store would blame the operator for a deployment fault |
  | `PAYROLL_ALLOWLIST` | REFUSE | an absent allow-list must never be an open payroll endpoint |
  | `PAYROLL_AUTH` | REFUSE | see below — there is no safe default for how a caller is identified |
  | `PAYROLL_PORT` | REFUSE | a payroll surface should not appear on a port nobody chose |
  | `PAYROLL_BIND` | `127.0.0.1` | loopback, and widening it requires an additional acknowledgement |

  ## Why `PAYROLL_AUTH` has no default, and what `trusted-header` costs

  `payroll.edge.endpoints` takes a caller DID that is ALREADY VERIFIED, and
  says CACAO verification is `kotoba-lang/org-chainagnostic-cacao`'s job and
  is not reimplemented there. This host does not reimplement it either. What
  it does instead is make the deployment say, out loud, how identity reaches
  it:

      PAYROLL_AUTH=trusted-header   a reverse proxy in front of this process
                                    verifies the caller and sets a header

  and there is no second value today. A `PAYROLL_AUTH` that is absent or
  anything else refuses to start. `none` is deliberately not a value: an
  operator in a hurry would set it.

  `trusted-header` means the header IS the authentication, so anything that
  can reach the socket can choose who it is. That is safe behind a proxy on
  loopback and catastrophic on an interface anything else can reach. So:

  **binding to a non-loopback address requires `PAYROLL_TRUST_FORWARDED=yes`
  as a separate, explicit acknowledgement.** Two variables rather than one,
  because the failure being prevented is somebody widening the bind address
  for a legitimate reason (a container's network) without noticing that they
  also removed the only thing making the header trustworthy.

  ## Durability is reported, not claimed

  `durability` answers `does what this process accepts survive the process`,
  and for BOTH store modes today the answer is **no**. `PAYROLL_STORE=datomic`
  selects `payroll.store/datomic-store`, whose own docstring says in-process
  is the default and not the guarantee: with langchain.db's default
  in-process DataScript it survives no longer than `MemStore` does.

  Saying so is the whole of this function. The console renders it, the health
  endpoint serves it, and `payroll.host.jvm-test` asserts it by actually
  restarting: the claim is checked against a measurement rather than being a
  sentence somebody keeps up to date."
  (:require [clojure.string :as str]
            [payroll.edge.endpoints :as api]))

(def loopback-addresses
  "Addresses that reach only this machine. `localhost` is included because a
  deployment writes it and it resolves to one of the other two; a check that
  refused it would push operators towards `0.0.0.0`."
  #{"127.0.0.1" "::1" "localhost"})

(def auth-modes
  "How a caller's identity reaches this process. One value, deliberately —
  see the namespace docstring."
  {"trusted-header"
   {:auth/mode :trusted-header
    :auth/means (str "この process の前段にあるリバースプロキシが呼び出し元を"
                     "検証し、ヘッダに DID を入れる。"
                     "ヘッダそのものが認証なので、"
                     "socket に到達できるものは誰にでもなれる")
    :auth/verifier "org-chainagnostic-cacao（この repository は再実装しない）"}})

(defn- refuse [why & [hint]]
  (cond-> {:config/status :refused :config/why why}
    hint (assoc :config/hint hint)))

(defn durability
  "What `mode` actually guarantees. Truthful for both values it accepts.

  There is no `:store/durable? true` branch and there is no code that
  produces one. When a backend that survives a restart is actually wired,
  this function changes and the test that restarts a host changes with it —
  which is the point of the test existing before the backend does."
  [mode]
  (case mode
    :ephemeral
    {:store/mode :ephemeral
     :store/survives-process-restart? false
     :store/what "payroll.store/MemStore — プロセス内の atom"
     :store/why (str "この process が終われば、承認した run も保留の理由も"
                     "すべて消える。煙テスト用であって、"
                     "給与の記録を預けてよい保存先ではない")}

    :datomic
    {:store/mode :datomic
     :store/survives-process-restart? false
     :store/what "payroll.store/DatomicStore — langchain.db（既定は in-process DataScript）"
     :store/why (str "protocol の差し替えは済んでいて、"
                     "契約テストが両 backend の同一性を証明している。"
                     "しかし既定の :db-api は in-process の DataScript なので、"
                     "耐久性は MemStore と変わらない。"
                     "実 Datomic や kotobase pod を指すのは "
                     "langchain.db の :db-api を差し替える別の作業であり、"
                     "この repository はまだそれをしていない")}

    {:store/mode nil
     :store/survives-process-restart? false
     :store/what "未設定"
     :store/why "保存先が設定されていない"}))

(def security-headers
  "Response headers this host always sends.

  `Content-Security-Policy` is `default-src 'none'` with `style-src
  'unsafe-inline'` — the console ships NO script, so `script-src` need not be
  allowed at all, and the one inline `<style>` `jp-go-dds.page` emits is the
  only thing that has to be. A console that later wanted a script would have
  to change this line, which is the review this policy exists to force.

  ⚠ `Referrer-Policy` is `same-origin` and must NOT be `no-referrer`. A page
  carrying `no-referrer` sends `Origin: null` on its own same-origin form
  POST, so `payroll.edge.console/same-origin?` would refuse every action —
  and an HTTP client in a test would not reproduce it, because a test sends
  whatever Origin it was told to. CLAUDE.md records this exact measurement
  from another repository on this fleet."
  {"Content-Security-Policy"
   (str "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
        "base-uri 'none'; frame-ancestors 'none'")
   "Referrer-Policy" "same-origin"
   "X-Content-Type-Options" "nosniff"
   "Cache-Control" "no-store"})

(defn- port-of
  "A port number, or nil.

  `0` IS accepted and means `let the operating system choose`. It is not a
  port a deployment would set, and it is exactly what the test suite sets so
  that several hosts can run at once — which matters because the alternative
  is a test that picks a free port itself and then exercises a code path
  production does not. A host started on port 0 reports the port it actually
  got, so nothing downstream has to guess."
  [s]
  (let [t (str/trim (str s))]
    (when (re-matches #"\d{1,5}" t)
      (let [n #?(:clj (parse-long t) :cljs (js/parseInt t 10))]
        (when (and (not (neg? n)) (< n 65536)) n)))))

(defn read-config
  "An environment map → `{:config/status :ok …}` or `{:config/status :refused
  :config/why …}`.

  The order of the checks is the order an operator fixes them in: what to
  store, who may call, how they are identified, and only then where to
  listen. A deployment that got the last one right and the first one wrong
  should not be told about the port."
  [env]
  (let [get* (fn [k] (some-> (get env k) str str/trim not-empty))
        mode (api/store-mode env)
        allowlist (api/parse-allowlist (get env "PAYROLL_ALLOWLIST"))
        auth (get auth-modes (get* "PAYROLL_AUTH"))
        header (get* "PAYROLL_DID_HEADER")
        bind (or (get* "PAYROLL_BIND") "127.0.0.1")
        port (port-of (get* "PAYROLL_PORT"))
        loopback? (contains? loopback-addresses bind)
        forwarded-ack? (= "yes" (get* "PAYROLL_TRUST_FORWARDED"))]
    (cond
      (nil? mode)
      (refuse "PAYROLL_STORE が設定されていないか、認識できない値である"
              (str "PAYROLL_STORE=datomic（langchain.db backend）または "
                   "PAYROLL_STORE=ephemeral（保存しない煙テスト）。"
                   "誤字は黙って保存先を選ばない"))

      (nil? allowlist)
      (refuse "PAYROLL_ALLOWLIST が設定されていない"
              (str "did:key:z6Mk…=emp-1,did:key:z6Ml…=emp-2 の形式。"
                   "許可リストが無い状態は「誰も許可されていない」ではなく"
                   "「何も設定されていない」であり、"
                   "開いた給与エンドポイントにしてはならない"))

      (nil? auth)
      (refuse (str "PAYROLL_AUTH が設定されていないか、"
                   "この host が知らない値である")
              (str "使えるのは " (pr-str (vec (sort (keys auth-modes))))
                   " のみ。呼び出し元の身元がどう届くかを"
                   "配備が明示するまで起動しない"))

      (and (= :trusted-header (:auth/mode auth)) (nil? header))
      (refuse "PAYROLL_AUTH=trusted-header だが PAYROLL_DID_HEADER が無い"
              "検証済みの DID を運ぶヘッダ名を指定する（例: X-Verified-DID）")

      (nil? port)
      (refuse "PAYROLL_PORT が設定されていないか、0-65535 の整数ではない"
              (str "給与の窓口を、誰も選んでいない番号に出さない"
                   "（0 は「OS に選ばせる」の意で、試験用）"))

      (and (not loopback?) (not forwarded-ack?))
      (refuse (str "PAYROLL_BIND=" bind " は loopback ではないのに、"
                   "PAYROLL_TRUST_FORWARDED=yes が無い")
              (str "PAYROLL_AUTH=trusted-header ではヘッダそのものが認証なので、"
                   "socket に到達できるものは誰にでもなれる。"
                   "loopback 以外に出すなら、前段のプロキシがヘッダを"
                   "上書きすることを配備が明示する必要がある"))

      :else
      {:config/status :ok
       :config/store-mode mode
       :config/allowlist allowlist
       :config/auth auth
       :config/did-header header
       :config/bind bind
       :config/port port
       :config/loopback? loopback?
       :config/trust-forwarded? forwarded-ack?
       :config/durability (durability mode)
       :config/headers security-headers})))

(defn health
  "What `GET /api/health` serves. Never says `ok` on its own.

  `:durable` is the field an operator is most likely to read wrong, so it is
  a three-part answer — the boolean, what the backend is, and why — rather
  than a boolean somebody can screenshot."
  [config]
  {:actor "cloud-itonami-isco-4313"
   :store (name (:config/store-mode config))
   :durability (:config/durability config)
   :auth (get-in config [:config/auth :auth/mode])
   :auth-means (get-in config [:config/auth :auth/means])
   :bind (:config/bind config)
   :loopback (:config/loopback? config)
   :allowlist-entries (count (:config/allowlist config))
   :note (str "この応答は配備の設定を述べるだけで、"
              "給与の計算が正しいことは何も主張しない")})
