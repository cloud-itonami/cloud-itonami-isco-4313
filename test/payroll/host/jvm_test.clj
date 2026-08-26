(ns payroll.host.jvm-test
  "The host, over a real socket.

  Every request here goes through `java.net.http.HttpClient` to a listening
  server. A test that called the handler directly would prove the routing and
  nothing about the thing the routing is for — and the two properties that
  matter most here, that a misconfigured deployment refuses to start and that
  what it accepts does not survive it, are only observable from outside the
  process."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.fixtures :as f]
            [payroll.host.config :as config]
            [payroll.host.jvm :as host]
            [payroll.juminzei :as juminzei]
            [payroll.store :as store])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(def ^:private good-env
  {"PAYROLL_STORE" "ephemeral"
   "PAYROLL_ALLOWLIST" f/allowlist-string
   "PAYROLL_AUTH" "trusted-header"
   "PAYROLL_DID_HEADER" "X-Verified-DID"
   ;; 0 = let the operating system choose, so several hosts can run at once
   "PAYROLL_PORT" "0"
   "PAYROLL_BIND" "127.0.0.1"})

(def ^:private client (HttpClient/newHttpClient))

(defn- send!
  [port method path {:keys [body headers]}]
  (let [b (-> (HttpRequest/newBuilder)
              (.uri (URI. (str "http://127.0.0.1:" port path))))]
    (doseq [[k v] headers] (.header b ^String k ^String v))
    (.method b method (if body
                        (HttpRequest$BodyPublishers/ofString body)
                        (HttpRequest$BodyPublishers/noBody)))
    (let [r (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r)
       :body (.body r)
       :headers (into {} (for [[k v] (.map (.headers r))] [k (first v)]))})))

(defn- with-host
  "Start a host, call `(f port store)`, stop it.

  A function and not a macro: `clj-kondo` cannot see bindings introduced by a
  custom macro, so every `port` and `st` inside one is an unresolved symbol
  and the lint alias fails at error level. A higher-order function needs no
  lint configuration to be understood, which is the cheaper of the two fixes."
  [opts f]
  (let [st (or (:store opts) (store/mem-store))
        srv (host/start! (merge good-env (:env opts)) {:store st})]
    (is (= :started (:host/status srv)) (pr-str srv))
    (try (f (:host/port srv) st)
         (finally ((:host/stop! srv))))))

;; ---------------------------------------------------------------------------
;; Fail-closed configuration
;; ---------------------------------------------------------------------------

(deftest a-deployment-that-says-nothing-refuses-to-start
  (let [r (host/start! {})]
    (is (= :refused (:host/status r)))
    (is (str/includes? (:host/why r) "PAYROLL_STORE"))))

(deftest each-missing-variable-refuses-and-names-itself
  (doseq [[k expected] [["PAYROLL_STORE" "PAYROLL_STORE"]
                        ["PAYROLL_ALLOWLIST" "PAYROLL_ALLOWLIST"]
                        ["PAYROLL_AUTH" "PAYROLL_AUTH"]
                        ["PAYROLL_DID_HEADER" "PAYROLL_DID_HEADER"]
                        ["PAYROLL_PORT" "PAYROLL_PORT"]]]
    (let [r (host/start! (dissoc good-env k))]
      (is (= :refused (:host/status r)) k)
      (is (str/includes? (:host/why r) expected) k))))

(deftest a-typo-in-the-store-mode-does-not-silently-select-one
  (doseq [bad ["Datomic" "datomicc" "memory" "none" ""]]
    (is (= :refused (:host/status (host/start! (assoc good-env
                                                      "PAYROLL_STORE" bad))))
        bad)))

(deftest there-is-no-auth-mode-that-means-none
  (testing "an operator in a hurry would set it"
    (doseq [bad ["none" "off" "no" "anonymous" "" "TRUSTED-HEADER"]]
      (is (= :refused (:host/status (host/start! (assoc good-env
                                                        "PAYROLL_AUTH" bad))))
          bad))
    (is (= ["trusted-header"] (vec (keys config/auth-modes))))))

(deftest binding-beyond-loopback-needs-a-separate-acknowledgement
  (testing "the failure being prevented is somebody widening the bind address
            for a legitimate reason without noticing that they also removed
            the only thing making the header trustworthy"
    (let [r (host/start! (assoc good-env "PAYROLL_BIND" "0.0.0.0"))]
      (is (= :refused (:host/status r)))
      (is (str/includes? (:host/why r) "PAYROLL_TRUST_FORWARDED")))
    (testing "and with the acknowledgement the config is accepted"
      (let [c (config/read-config (assoc good-env
                                         "PAYROLL_BIND" "0.0.0.0"
                                         "PAYROLL_TRUST_FORWARDED" "yes"))]
        (is (= :ok (:config/status c)))
        (is (false? (:config/loopback? c)))))))

(deftest loopback-by-default
  (is (= "127.0.0.1" (:config/bind (config/read-config
                                    (dissoc good-env "PAYROLL_BIND")))))
  (doseq [addr config/loopback-addresses]
    (is (= :ok (:config/status (config/read-config
                                (assoc good-env "PAYROLL_BIND" addr))))
        addr)))

;; ---------------------------------------------------------------------------
;; Durability, measured rather than claimed
;; ---------------------------------------------------------------------------

(deftest neither-store-mode-survives-a-restart-and-both-say-so
  (doseq [mode [:ephemeral :datomic]]
    (let [d (config/durability mode)]
      (is (false? (:store/survives-process-restart? d)) mode)
      (is (seq (:store/why d)) mode))))

(deftest the-durability-claim-matches-what-actually-happens
  (testing "the claim is checked against a measurement rather than being a
            sentence somebody keeps up to date. A store built by the host —
            not one handed in by this test — is committed to and then a
            second host is started the way a restart would"
    (doseq [mode ["ephemeral" "datomic"]]
      (let [env (assoc good-env "PAYROLL_STORE" mode)
            srv1 (host/start! env)
            port1 (:host/port srv1)
            st1 (:host/store srv1)]
        (is (= :started (:host/status srv1)))
        (try
          ;; register through the store the host built, then commit a run
          ;; over the socket, so the ledger entry is genuinely the host's.
          (store/register-client! st1 (f/employer))
          (store/register-contract! st1 (f/contract))
          (let [r (send! port1 "POST" "/api/payroll-run"
                         {:body (f/run-body)
                          :headers {"X-Verified-DID" f/caller-did}})]
            (is (= 200 (:status r)) mode)
            (is (= :commit (:disposition (edn/read-string (:body r))))))
          (is (= 1 (count (store/ledger st1))) mode)
          (finally ((:host/stop! srv1))))

        ;; a second host, started the way a restart would start it
        (let [srv2 (host/start! env)]
          (try
            (let [r (send! (:host/port srv2) "GET" "/api/ledger"
                           {:headers {"X-Verified-DID" f/caller-did}})
                  body (edn/read-string (:body r))]
              (is (= 200 (:status r)) mode)
              (is (zero? (:count body))
                  (str mode ": the ledger survived a restart, so "
                       "`payroll.host.config/durability` is now lying"))
              (is (empty? (:entries body)) mode))
            (finally ((:host/stop! srv2)))))))))

(deftest the-health-endpoint-tells-the-truth-about-durability
  (with-host {}
    (fn [port _st]
    (let [body (edn/read-string (:body (send! port "GET" "/api/health" {})))]
      (is (false? (get-in body [:durability :store/survives-process-restart?])))
      (is (= :trusted-header (:auth body)))
      (is (true? (:loopback body)))
      (is (str/includes? (:note body) "何も主張しない"))))))

(deftest health-is-served-without-the-allowlist
  (testing "it carries no payroll data, and a deployment whose allow-list is
            wrong is exactly when somebody needs to ask what this process
            thinks it is"
    (with-host {}
    (fn [port _st]
      (is (= 200 (:status (send! port "GET" "/api/health" {}))))))))

;; ---------------------------------------------------------------------------
;; Identity, over the socket
;; ---------------------------------------------------------------------------

(deftest a-request-with-no-did-header-is-403
  (with-host {}
    (fn [port _st]
    (is (= 403 (:status (send! port "GET" "/console" {}))))
    (let [r (send! port "GET" "/api/ledger" {})]
      (is (= 403 (:status r)))
      (is (false? (:ok (edn/read-string (:body r)))))))))

(deftest an-unknown-did-is-403
  (with-host {}
    (fn [port _st]
    (is (= 403 (:status (send! port "GET" "/console"
                               {:headers {"X-Verified-DID" "did:key:zNope"}})))))))

(deftest the-did-header-name-is-the-configured-one
  (with-host {:env {"PAYROLL_DID_HEADER" "X-Caller"}}
    (fn [port _st]
    (is (= 403 (:status (send! port "GET" "/console"
                               {:headers {"X-Verified-DID" f/caller-did}}))))
    (is (= 200 (:status (send! port "GET" "/console"
                               {:headers {"X-Caller" f/caller-did}})))))))

;; ---------------------------------------------------------------------------
;; The full vertical, over the socket
;; ---------------------------------------------------------------------------

(deftest the-whole-operator-flow-works-over-http
  (with-host {}
    (fn [port st]
    (let [did {"X-Verified-DID" f/caller-did}
          form (merge did {"Content-Type" "application/x-www-form-urlencoded"})]
      (store/register-client! st (f/employer))

      (testing "register a contract through the console"
        (let [r (send! port "POST" "/console/contract"
                       {:headers form
                        :body (str "contract-id=" f/contract-id
                                   "&worker=W&wage-type=monthly&rate=" f/gross
                                   "&standard-remuneration=" f/standard-remuneration
                                   "&standard-remuneration-month=2026-07"
                                   "&health-insurance-insured=true"
                                   "&care-insurance-second-category=true"
                                   "&employees-pension-insured=true"
                                   "&employment-insurance-insured=true")})]
          (is (= 200 (:status r)))
          (is (some? (store/contract-of st f/contract-id)))))

      (testing "a run missing a contribution is held, and the screen says which"
        (let [r (send! port "POST" "/console/run"
                       {:headers form
                        :body (str "contract-id=" f/contract-id
                                   "&period=" f/period
                                   "&deductions=" f/income-tax
                                   "&income-tax-withheld=" f/income-tax)})]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "保留した"))
          (is (str/includes? (:body r) "social-insurance-not-accounted-for"))))

      (testing "the complete run commits"
        (let [r (send! port "POST" "/console/run"
                       {:headers form
                        :body (str "contract-id=" f/contract-id
                                   "&period=" f/period
                                   "&deductions=" f/deduction-total
                                   "&income-tax-withheld=" f/income-tax
                                   "&health-insurance-withheld=" f/health-insurance
                                   "&care-insurance-withheld=" f/care-insurance
                                   "&employees-pension-withheld=" f/employees-pension
                                   "&employment-insurance-withheld="
                                   f/employment-insurance)})]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "台帳に記録した"))))

      (testing "the wage ledger carries both runs, held and committed"
        (let [r (send! port "GET" "/console/export?kind=wage-ledger&format=csv"
                       {:headers did})]
          (is (= 200 (:status r)))
          (is (str/includes? (get (:headers r) "content-disposition" "")
                             "chingin-daicho.csv")
              "Content-Disposition names the file")
          (is (= 3 (count (str/split-lines (:body r)))))
          (is (str/includes? (:body r) "保留"))
          (is (str/includes? (:body r) "承認"))))

      (testing "a payslip renders as a printable document"
        (let [r (send! port "GET"
                       (str "/console/export?kind=payslip&format=html"
                            "&contract-id=" f/contract-id "&period=" f/period)
                       {:headers did})]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "給与支払明細書"))
          (is (str/includes? (:body r) "法定様式ではない"))))

      (testing "and the reconciliation runs against the committed figures"
        (let [r (send! port "POST" "/console/mf"
                       {:headers form
                        :body (str "period=" f/period "&csv="
                                   (java.net.URLEncoder/encode
                                    (str "従業員番号,氏名,支給年月,総支給額,差引支給額\n"
                                         "9001,W," f/period "," f/gross "," f/net)
                                    "UTF-8"))})]
          (is (= 200 (:status r)))
          ;; the fixture contract registered here carries no :mf/employee-number,
          ;; so the row is unmapped — which is the honest outcome and is what
          ;; the screen has to say.
          (is (str/includes? (:body r) "契約に紐づいていない"))))

      (testing "register a 住民税 決定通知書, and land on the redirect"
        (let [enc #(java.net.URLEncoder/encode (str %) "UTF-8")
              n f/resident-tax-notice-as-transcribed
              r (send! port "POST" "/console/juminzei-notice"
                       {:headers form
                        :body (str/join
                               "&"
                               (concat
                                ["kind=decision"
                                 (str "municipality=" (enc (:notice/municipality n)))
                                 (str "tax-year=" (:notice/tax-year n))
                                 (str "reference=" (enc (:notice/reference n)))
                                 (str "revision=" (:notice/revision n))
                                 (str "designated-number="
                                      (enc (:notice/designated-number n)))
                                 (str "annual-total=" (:notice/annual-total n))
                                 (str "registered-at=" (:notice/registered-at n))]
                                (for [k juminzei/month-keys]
                                  (str (name k) "=" (get (:notice/months n) k)))))})]
          (is (= 303 (:status r)))
          (testing "the host carries the console's Location through. Without
                    the header a 303 is a blank page, and a blank page after a
                    registration reads as a registration that failed"
            (is (= "/console/operations?notice=registered"
                   (get (:headers r) "location"))))
          (is (= 1 (count (store/juminzei-notices st f/employer-id))))
          (testing "and following it by hand lands on a screen that confirms
                    by reading the store back"
            (let [g (send! port "GET" "/console/operations?notice=registered"
                           {:headers did})]
              (is (= 200 (:status g)))
              (is (str/includes? (:body g) "通知を登録した"))
              (is (str/includes? (:body g)
                                 "いまこの事業主に登録されている通知は 1 件"))
              (testing "with no 月割額 and no 年税額 on it"
                (is (not (str/includes? (:body g) (str f/resident-tax))))
                (is (not (str/includes? (:body g)
                                        (str (* 12 f/resident-tax))))))))))))))

;; ---------------------------------------------------------------------------
;; Headers
;; ---------------------------------------------------------------------------

(deftest the-console-ships-no-script-and-the-policy-says-so
  (with-host {}
    (fn [port _st]
    (let [r (send! port "GET" "/console"
                   {:headers {"X-Verified-DID" f/caller-did}})]
      (is (str/includes? (get (:headers r) "content-security-policy")
                         "default-src 'none'"))
      (is (not (str/includes? (:body r) "<script")))))))

(deftest the-referrer-policy-is-not-no-referrer
  (testing "a page carrying `no-referrer` sends `Origin: null` on its OWN
            same-origin form POST, so every action on this console would be
            refused — and an HTTP client in a test would not reproduce it,
            because a test sends whatever Origin it was told to"
    (is (= "same-origin" (get config/security-headers "Referrer-Policy")))
    (with-host {}
    (fn [port _st]
      (is (= "same-origin"
             (get (:headers (send! port "GET" "/api/health" {}))
                  "referrer-policy")))))))

;; ---------------------------------------------------------------------------
;; Limits
;; ---------------------------------------------------------------------------

(deftest an-oversized-body-is-refused-rather-than-held-in-memory
  (with-host {}
    (fn [port _st]
    (let [r (send! port "POST" "/api/payroll-run"
                   {:body (apply str (repeat (* 2 1024 1024) \x))
                    :headers {"X-Verified-DID" f/caller-did}})]
      (is (= 413 (:status r)))))))

(deftest a-route-nobody-declared-is-404
  (with-host {}
    (fn [port _st]
    (is (= 404 (:status (send! port "GET" "/" {}))))
    (is (= 404 (:status (send! port "GET" "/api/disburse"
                               {:headers {"X-Verified-DID" f/caller-did}})))))))

(deftest an-exception-inside-the-graph-does-not-leak-its-message
  (testing "a stack trace can carry a contract id, a worker name or an
            amount, and this surface serves whoever holds the header"
    (with-host {}
    (fn [port st]
      (store/register-client! st (f/employer))
      (store/register-contract! st (f/contract))
      ;; `payroll.advisor/infer` has a `case` with no default on `:stake`, so
      ;; an unrecognised one throws inside the graph. The edge's structural
      ;; check catches this particular value, so it is sent as a body the
      ;; parser accepts and the graph does not.
      (let [r (send! port "POST" "/api/payroll-run"
                     {:body (pr-str {:period f/period :contract-id f/contract-id
                                     :deductions "not a number"})
                      :headers {"X-Verified-DID" f/caller-did}})]
        (is (contains? #{400 500} (:status r)))
        (is (not (str/includes? (:body r) f/worker))))))))
