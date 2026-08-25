(ns payroll.host.jvm
  "A real host for the pure routes: `com.sun.net.httpserver.HttpServer`.

  ## Why the JDK's server and not a framework

  Because it is already there. `jdk.httpserver` is a JDK module, so this host
  adds ZERO dependencies to a repository whose `deps.edn` treats its
  dependency set as a hard property. A Ring adapter would have been more
  idiomatic and would have brought a server, a middleware stack and a spec
  this repository does not otherwise need, in exchange for handling of
  multipart bodies and websockets that this console does not have.

  What it costs: no connection pooling worth the name, a fixed thread pool,
  and no HTTPS. **This host is meant to run on loopback behind a reverse
  proxy** — which is exactly the deployment `payroll.host.config` already
  refuses to start without, so the limitation and the safety rule are the
  same rule.

  ## It verifies nothing, and that is stated rather than implied

  `payroll.edge.endpoints` takes a caller DID that is ALREADY VERIFIED. This
  host reads it from a header a reverse proxy set. It does not check a CACAO,
  a signature or a temporal window — `kotoba-lang/org-chainagnostic-cacao`
  does that and this repository does not reimplement it (ADR-2607268000).

  `payroll.host.config` is what makes that safe to say: it refuses to start
  unless the deployment names the header AND either binds to loopback or
  explicitly acknowledges that something in front is overwriting it.

  ## The store's lifetime belongs here

  `payroll.edge.endpoints/route` takes a store rather than building one,
  because a dispatcher that built one per request would give every read an
  empty store — the write route would work, both reads would answer 404
  forever, and the deployment would look healthy while remembering nothing.
  So `start!` builds exactly one and holds it for the server's life.

  ## What `start!` does NOT do

  It does not create an employer, register a contract or seed anything. A
  host that seeded a demo employer would make the difference between a
  deployment with data and one without invisible — and the first request
  against an empty store gets `:no-client`, which is the governor's own
  answer and points at the registration that has not happened."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [payroll.edge.console :as console]
            [payroll.edge.endpoints :as api]
            [payroll.host.config :as config])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress URI)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors TimeUnit)))

(def ^:private max-body-bytes
  "How much of a request body is read before it is refused.

  1 MiB. Generous for an EDN payroll run and for a pasted MoneyForward export
  of a company with a few dozen employees, and small enough that a request
  cannot make this process hold an arbitrary amount of memory. The limit is
  enforced while reading rather than after: checking `Content-Length` alone
  trusts a number the client chose."
  (* 1024 1024))

(def ^:private max-drain-bytes
  "How much of a body this host will read and throw away after deciding to
  refuse it.

  Refusing without draining does not produce a 413. `com.sun.net.httpserver`
  closes the connection when a response is sent before the request body has
  been consumed, and a client still uploading sees a reset — measured
  2026-08-25 as `IOException: HTTP/1.1 header parser received no bytes`,
  which is a client-side symptom that says nothing about what the server
  decided.

  So an over-large body is drained up to this much and then answered. It is
  8x `max-body-bytes` rather than unbounded, because draining without a limit
  is the same resource exhaustion the cap exists to prevent, one step later.
  A body larger than THIS gets the connection closed under it, which is the
  correct outcome for a client that will not stop."
  (* 8 max-body-bytes))

(defn- drain!
  "Read and discard, up to `max-drain-bytes`, so a refusal can be delivered."
  [^java.io.InputStream in]
  (let [buf (byte-array 8192)]
    (loop [total 0]
      (when (< total max-drain-bytes)
        (let [n (.read in buf)]
          (when-not (neg? n) (recur (+ total n))))))))

(defn- read-body
  "The request body as a UTF-8 string, or `::too-large`.

  Reads with a cap rather than slurping. A chunked request has no
  `Content-Length` to check, so the only honest limit is the one applied
  while reading — `Content-Length` is a number the client chose and a body
  can be longer than it claims."
  [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toString out "UTF-8")
            (> (+ total n) max-body-bytes) (do (drain! in) ::too-large)
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- query-of [^URI uri]
  (console/parse-form (.getRawQuery uri)))

(defn- header [^HttpExchange exchange n]
  (some-> (.getRequestHeaders exchange) (.getFirst ^String n)))

(defn- self-origin
  "This deployment's own origin, from the request's own `Host` header.

  Derived rather than configured, deliberately. A configured origin is one
  more thing that can be wrong in a way that only shows up as every form
  being refused, and behind a reverse proxy the operator would have to know
  the EXTERNAL origin — which is exactly what the proxy is hiding. Taking it
  from `Host` means the check compares the page's origin against the origin
  the browser used to reach the page, which is the comparison that matters.

  The scheme is read from `X-Forwarded-Proto` when present, because the proxy
  terminates TLS and this process only ever sees http."
  [^HttpExchange exchange]
  (when-let [host (header exchange "Host")]
    (str (or (header exchange "X-Forwarded-Proto") "http") "://" host)))

(defn- respond!
  [^HttpExchange exchange status content-type ^String body headers]
  (let [bytes (.getBytes (or body "") StandardCharsets/UTF_8)
        h (.getResponseHeaders exchange)]
    (doseq [[k v] headers] (.set h ^String k ^String v))
    (.set h "Content-Type" (or content-type "text/plain; charset=utf-8"))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- edn-body
  "A response body from the pure routes, printed as EDN.

  EDN and not JSON because the routes return EDN and `clojure.edn/read-string`
  is what the request side already uses — a JSON encoder here would be a
  second serialisation format in a repository that has one, and would have to
  make a decision about how to render a keyword that the caller then has to
  reverse."
  [body]
  (pr-str body))

(defn- handle
  [{:keys [store config advisor css]} ^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)
        path (.getPath uri)
        method (keyword (str/lower-case (.getRequestMethod exchange)))
        did (header exchange (:config/did-header config))
        headers (:config/headers config)
        mode (:config/store-mode config)
        allowlist (:config/allowlist config)]
    (try
      (cond
        ;; ---- health -------------------------------------------------
        ;; Outside the allow-list on purpose: it carries no payroll data and
        ;; a deployment whose allow-list is wrong is exactly when somebody
        ;; needs to be able to ask what this process thinks it is.
        (= path "/api/health")
        (respond! exchange 200 "application/edn; charset=utf-8"
                  (edn-body (config/health config)) headers)

        ;; ---- the console --------------------------------------------
        (str/starts-with? path "/console")
        (let [raw (if (= :post method) (read-body exchange) "")]
          (if (= ::too-large raw)
            (respond! exchange 413 "text/plain; charset=utf-8"
                      "request body too large" headers)
            (let [r (console/route
                     {:store store :store-mode mode :allowlist allowlist
                      :caller-did did :css css :advisor advisor
                      :durability (:config/durability config)
                      :self-origin (self-origin exchange)}
                     {:method method :path path
                      :query (query-of uri)
                      :form (console/parse-form raw)
                      :origin (header exchange "Origin")})]
              (respond! exchange (:status r) (:content-type r) (:body r)
                        (cond-> headers
                          (:filename r)
                          (assoc "Content-Disposition"
                                 (str "attachment; filename=\"" (:filename r) "\"")))))))

        ;; ---- the API ------------------------------------------------
        (str/starts-with? path "/api/")
        (let [raw (if (= :post method) (read-body exchange) "")]
          (if (= ::too-large raw)
            (respond! exchange 413 "text/plain; charset=utf-8"
                      "request body too large" headers)
            (let [r (api/route store mode allowlist did
                               {:method method :path path :body raw})]
              (respond! exchange (:status r) "application/edn; charset=utf-8"
                        (edn-body (:body r)) headers))))

        :else
        (respond! exchange 404 "text/plain; charset=utf-8" "no such route" headers))

      (catch Throwable t
        ;; The message is NOT served. A stack trace or an exception message
        ;; from inside the graph can carry a contract id, a worker name or an
        ;; amount, and this surface serves whoever holds the header. It goes
        ;; to stderr, where the operator running the process can read it.
        (binding [*out* *err*]
          (println "payroll.host.jvm: unhandled" (.getMessage t))
          (.printStackTrace t))
        (respond! exchange 500 "text/plain; charset=utf-8"
                  "internal error" headers))
      (finally
        (.close exchange)))))

(defn dds-css
  "The vendored デジタル庁デザインシステム stylesheet, read once.

  A classpath read, and the reason `payroll.ui.render` takes the string as a
  parameter: the pure namespace stays pure and this one owns the I/O.

  Returns \"\" when the resource is absent rather than throwing. An unstyled
  console is legible — every state in it is a word — while a host that
  refused to start over a stylesheet would be refusing for a reason that has
  nothing to do with payroll."
  []
  (if-let [r (io/resource "jp_go_dds/dds.css")]
    (slurp r)
    (do (binding [*out* *err*]
          (println "payroll.host.jvm: jp_go_dds/dds.css not on the classpath;"
                   "serving the console unstyled"))
        "")))

(defn start!
  "Start a server from `env`, or return the refusal.

    (start! (System/getenv))
    => {:host/status :started :host/port n :host/stop! (fn [])}
    => {:host/status :refused :host/why … :host/hint …}

  `opts` may carry `:store` and `:advisor`, which is how the test suite runs
  a host against a store it can also inspect directly, and how a deployment
  swaps in `payroll.advisor/llm-advisor`. Neither is read from the
  environment: a host that could be pointed at an advisor by an environment
  variable would be a host whose safety depends on a string.

  Port 0 binds an ephemeral port and `:host/port` reports the one the OS
  chose — which is what lets the suite run several hosts at once without
  a fixed port that two test runs would fight over."
  ([env] (start! env {}))
  ([env {:keys [store advisor]}]
   (let [config (config/read-config env)]
     (if (= :refused (:config/status config))
       {:host/status :refused
        :host/why (:config/why config)
        :host/hint (:config/hint config)}
       (let [st (or store (api/store-for (:config/store-mode config)))
             css (dds-css)
             ctx {:store st :config config :advisor advisor :css css}
             server (HttpServer/create
                     (InetSocketAddress. ^String (:config/bind config)
                                         ^int (:config/port config))
                     0)
             pool (Executors/newFixedThreadPool 8)]
         (.createContext server "/"
                         (reify HttpHandler
                           (handle [_ exchange] (handle ctx exchange))))
         (.setExecutor server pool)
         (.start server)
         {:host/status :started
          :host/port (.getPort (.getAddress server))
          :host/bind (:config/bind config)
          :host/store st
          :host/durability (:config/durability config)
          :host/stop! (fn []
                        (.stop server 0)
                        (.shutdownNow pool)
                        (.awaitTermination pool 2 TimeUnit/SECONDS))})))))

(defn -main
  "Start from the process environment, or print the refusal and exit 78.

  78 is `EX_CONFIG` from `sysexits.h`: a supervisor that restarts on a
  non-zero exit can tell `this deployment is misconfigured` from `this
  process crashed`, and restarting the first forever is a loop that fills a
  log with an answer nobody reads."
  [& _]
  (let [r (start! (into {} (System/getenv)))]
    (if (= :refused (:host/status r))
      (do (binding [*out* *err*]
            (println "payroll: refusing to start —" (:host/why r))
            (when (:host/hint r) (println "  " (:host/hint r))))
          (System/exit 78))
      (println (str "payroll: listening on " (:host/bind r) ":" (:host/port r)
                    " — 保存先が再起動を越えるか: "
                    (if (get-in r [:host/durability :store/survives-process-restart?])
                      "越える" "越えない"))))))
