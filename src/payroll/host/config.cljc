(ns payroll.host.config
  "What a deployment must say before this actor will listen on a socket.

  Portable and pure: it takes a plain environment map and returns either a
  configuration or a refusal, so every decision about whether a deployment is
  safe is testable without starting a process.

  ## Every default is the safe one, and the unsafe ones have no default

  | variable | absent | why |
  |---|---|---|
  | `PAYROLL_STORE` | REFUSE | `payroll.edge.endpoints/store-mode` already refuses to guess a backend; an empty in-process store would blame the operator for a deployment fault |
  | `PAYROLL_KOTOBASE_*` / `PAYROLL_ENCRYPTION` | REFUSE **in durable mode only** | see below |
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

  ## `PAYROLL_STORE=kotobase` fails closed on five things, not one

  The durable backend needs an endpoint, a tenant identity, a scoped auth
  provider, an encryption provider and a compare-and-set head. **None of them
  has a default and none of them is guessed**, because each absence produces
  a different silent wrong:

  | absent | what happens if it is defaulted |
  |---|---|
  | endpoint | the store talks to nothing and reports an empty ledger, which reads as `this employer has filed nothing` |
  | tenant | two deployments on one node share a key space and one employer's ledger appears in another's console |
  | scoped auth | the credential is either absent (nothing works) or ambient (everything does) |
  | encryption | **the payroll ledger is written in the clear** — the one failure nobody sees until somebody else reads the store |
  | CAS | the head is last-write-wins, so of two runs committed at once one leaves no record at all |

  This function reads only the NAMES of the auth and encryption providers
  (`PAYROLL_KOTOBASE_AUTH`, `PAYROLL_ENCRYPTION`). It never reads a token and
  never puts one in its own output — `payroll.host.config-test` asserts that
  by putting a recognisable secret in the environment and scanning the whole
  configuration for it.

  ## Durability is reported, not claimed

  `durability` answers `does what this process accepts survive the process`.
  For `:ephemeral` and `:datomic` the answer is **no** — `PAYROLL_STORE=datomic`
  selects `payroll.store/datomic-store`, whose own docstring says in-process
  is the default and not the guarantee.

  For `:kotobase` the answer is **the transport's**, and this function will
  not answer it on the transport's behalf. `payroll.store.kotobase` measures
  that a second store reconstructs the same records from the same transport;
  whether the bytes are still there tomorrow is a fact about the node, so
  `durability` takes the transport's own `:transport/durable?` and reports
  FALSE when it is absent. A transport that forgot to say is not durable.

  Saying so is the whole of this function. The console renders it, the health
  endpoint serves it, and `payroll.host.jvm-test` asserts it by actually
  restarting — in both directions: the two ephemeral modes lose the ledger,
  and the kotobase mode reconstructs it."
  (:require [clojure.string :as str]
            [payroll.edge.endpoints :as api]
            [payroll.projection.r2 :as r2]))

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

(defn- durability-legacy
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

(defn durability
  "What `mode` actually guarantees.

  Three-armed now, and the third arm is the one this repository spent its
  whole history not having. It is still not a `true` this function decides:
  `transport-durable?` comes from the injected transport's own `describe`,
  and absent it the answer is no.

  Called with one argument by everything that has only a mode; the host calls
  it again with the transport's answer once it has a store, which is the only
  moment either fact is known."
  ([mode] (durability mode {}))
  ([mode {:keys [transport-durable? transport reconstructs?]}]
   (case mode
     :kotobase
     {:store/mode :kotobase
      :store/survives-process-restart? (true? transport-durable?)
      :store/what "payroll.store.kotobase/KotobaseStore — kotobase の block/ref 面"
      :store/reconstructs? (boolean reconstructs?)
      :store/transport transport
      :store/why
      (if (true? transport-durable?)
        (str "注入された transport が耐久性を宣言している。"
             "store は head から chain を辿って記録を再構成する"
             "（payroll.store.kotobase/reconstruct）。"
             "ただし『この配備が実際の node に対して動いた』という主張ではない —— "
             "それは deploy の証拠であって、設定の証拠ではない")
        (str "注入された transport が :transport/durable? true を宣言していない。"
             "store の再構成は測定済みだが、"
             "bytes が明日も在るかどうかは transport の性質であり、"
             "store はそれを代わりに主張しない"
             "（宣言し忘れた transport は耐久ではない）"))}

     (durability-legacy mode))))


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

(def durable-requirements
  "What `PAYROLL_STORE=kotobase` additionally demands, in the order an
  operator fixes them.

  `:requirement/reads-a-secret? false` on every row and there is no row where
  it is true — this configuration reads the NAME of a provider, never a
  credential. `payroll.host.config-test` puts a recognisable token in the
  environment and asserts it appears nowhere in the returned configuration."
  [{:requirement/env "PAYROLL_KOTOBASE_ENDPOINT"
    :requirement/label "kotobase エンドポイント"
    :requirement/reads-a-secret? false
    :requirement/why (str "指していない store は空の台帳を返し、"
                          "それは「この事業主は何も届け出ていない」と"
                          "同じ顔をする")}
   {:requirement/env "PAYROLL_KOTOBASE_TENANT"
    :requirement/label "テナント識別子"
    :requirement/reads-a-secret? false
    :requirement/why (str "すべての ref と block に載る。"
                          "無ければ同じ node 上の別配備と鍵空間を共有する")}
   {:requirement/env "PAYROLL_KOTOBASE_AUTH"
    :requirement/label "scope を絞った認証プロバイダの名前"
    :requirement/reads-a-secret? false
    :requirement/why (str "資格そのものではなく、"
                          "どこから資格を取るかの名前を配備が述べる"
                          "（例: keychain:payroll-kotobase）。"
                          "この process は値を読まない")}
   {:requirement/env "PAYROLL_ENCRYPTION"
    :requirement/label "暗号化プロバイダの名前"
    :requirement/reads-a-secret? false
    :requirement/why (str "給与の台帳を平文で書かない。"
                          "既定が無いのは、既定があれば"
                          "「設定し忘れ」が「平文で書く」になるからである")}
   {:requirement/env "PAYROLL_BLIND_INDEX"
    :requirement/label "鍵付き blind index プロバイダの名前"
    :requirement/reads-a-secret? false
    :requirement/why (str "冪等性の tag は node 上に平文で載る。"
                          "鍵の無いハッシュでは、契約 ID を当てられる者が"
                          "封緘を一つも開けずに在籍を確認できる。"
                          "PAYROLL_ENCRYPTION と同じ名前を指してはならない —— "
                          "blind index の鍵は回転できず"
                          "（回すと過去の書き込みを見分けられなくなる）、"
                          "tag は平文で晒され続けるので、"
                          "封筒の鍵とは脅威も寿命も違う")}
   {:requirement/env "PAYROLL_KOTOBASE_CAS"
    :requirement/label "head が compare-and-set であることの明示"
    :requirement/reads-a-secret? false
    :requirement/expects "yes"
    :requirement/why (str "last-write-wins の head では、"
                          "同時に走った二つの run のうち一方が"
                          "何の記録も残さずに消える。"
                          "transport がそうでないなら、"
                          "そう宣言できないはずである")}])

(defn durable-gaps
  "Which durable requirements this environment does not meet.

  `PAYROLL_KOTOBASE_CAS` must be exactly `yes`: a variable set to `true`,
  `1` or the name of a transport is an operator answering a different
  question, and this one is an acknowledgement rather than a value."
  [env]
  (let [get* (fn [k] (some-> (get env k) str str/trim not-empty))]
    (vec (for [r durable-requirements
               :let [v (get* (:requirement/env r))
                     expect (:requirement/expects r)]
               :when (or (nil? v) (and expect (not= expect v)))]
           r))))

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
              (str "PAYROLL_STORE=kotobase（durable。transport の注入が要る）、"
                   "PAYROLL_STORE=datomic（langchain.db backend）または "
                   "PAYROLL_STORE=ephemeral（保存しない煙テスト）。"
                   "誤字は黙って保存先を選ばない"))

      ;; Checked BEFORE the allow-list, and that order is deliberate: an
      ;; operator who set the durable mode and none of what it needs has a
      ;; deployment that would otherwise start, accept payroll runs, and
      ;; write them into a store that either does not exist or is not
      ;; encrypted. Everything below this line is about who may call; this is
      ;; about whether what they file is kept at all.
      (and (= :kotobase mode)
           (let [e (get* "PAYROLL_ENCRYPTION")
                 b (get* "PAYROLL_BLIND_INDEX")]
             (and e b (= e b))))
      (refuse (str "PAYROLL_ENCRYPTION と PAYROLL_BLIND_INDEX が"
                   "同じプロバイダ " (pr-str (get* "PAYROLL_ENCRYPTION"))
                   " を指している")
              (str "封筒の鍵と blind index の鍵は別でなければならない。"
                   "blind index の鍵は回転できない —— "
                   "回した瞬間に過去の書き込みの tag と一致しなくなり、"
                   "再送された :commit が二度目の支払いになる。"
                   "一方 tag は node 上に平文で載り続けるので"
                   "オフラインで攻撃され続ける。"
                   "寿命も晒され方も違う二つの事実を、一つの秘密にしない"))

      (and (= :kotobase mode) (seq (durable-gaps env)))
      (let [gaps (durable-gaps env)]
        (refuse (str "PAYROLL_STORE=kotobase だが、durable 配備に要る設定が"
                     (count gaps) " 件足りない: "
                     (str/join "、" (map :requirement/env gaps)))
                (str/join "。"
                          (for [g gaps]
                            (str (:requirement/env g) "（"
                                 (:requirement/label g) "）: "
                                 (:requirement/why g))))))

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
       ;; The transport is INJECTED and this function has never seen it, so
       ;; the durability reported here is the conservative one. The host
       ;; recomputes it from the store it actually built — see
       ;; `payroll.host.jvm/start!`.
       :config/durability (durability mode)
       :config/durable-requirements (when (= :kotobase mode) durable-requirements)
       ;; Whether this deployment's ENVIRONMENT could build the analytical
       ;; projection. It makes no request and reads no token —
       ;; `payroll.projection.r2/read-config` reports the NAME a deployment
       ;; gave and never the value, which is what makes this safe to print on
       ;; a screen and into `GET /api/operations`.
       ;;
       ;; Computed here rather than in the host because this is the namespace
       ;; that reads the environment; `payroll.host.jvm` owns lifetimes and
       ;; sockets and has no business parsing variables. It is the third
       ;; distinguishable state the operations report needs: `not-configured`
       ;; says this process holds no catalog driver, and this says whether one
       ;; could be configured at all.
       :config/projection-preflight (r2/preflight env)
       :config/kotobase (when (= :kotobase mode)
                          {:kotobase/endpoint (get* "PAYROLL_KOTOBASE_ENDPOINT")
                           :kotobase/tenant (get* "PAYROLL_KOTOBASE_TENANT")
                           ;; the NAME of the provider, never its value.
                           :kotobase/auth-provider (get* "PAYROLL_KOTOBASE_AUTH")
                           :kotobase/encryption-provider (get* "PAYROLL_ENCRYPTION")
                           :kotobase/blind-index-provider (get* "PAYROLL_BLIND_INDEX")
                           ;; checked above: read-config refuses when the two
                           ;; provider names are equal, so reaching here means
                           ;; the deployment named two different things.
                           :kotobase/keys-are-separate? true
                           :kotobase/cas-acknowledged? true})
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
