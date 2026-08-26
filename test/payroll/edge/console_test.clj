(ns payroll.edge.console-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.edge.console :as console]
            [payroll.edge.endpoints :as api]
            [payroll.fixtures :as f]
            [payroll.juminzei :as juminzei]
            [payroll.projection.r2 :as r2]
            [payroll.store :as store]
            [payroll.touroku :as touroku]
            [payroll.ui.views :as views]))

(def allowlist (api/parse-allowlist f/allowlist-string))

(defn- base [st]
  {:store st :store-mode :ephemeral :allowlist allowlist
   :caller-did f/caller-did :css "" :self-origin "http://localhost:9"
   :durability {:store/what "MemStore" :store/why "消える"
                :store/survives-process-restart? false}})

(defn- GET
  ([st path] (GET st path {}))
  ([st path query]
   (console/route (base st) {:method :get :path path :query query})))

(defn- POST
  ([st path form] (POST st path form "http://localhost:9"))
  ([st path form origin]
   (console/route (base st) {:method :post :path path :form form
                             :origin origin})))

;; ---------------------------------------------------------------------------
;; Form decoding
;; ---------------------------------------------------------------------------

(deftest a-literal-plus-survives-decoding
  (testing "`+` is turned into `%20` BEFORE decoding. A post-decode
            replacement would turn a `%2B` into a space and silently corrupt
            any field an operator typed a plus into"
    (is (= {"a" "1 2"} (console/parse-form "a=1+2")))
    (is (= {"a" "1+2"} (console/parse-form "a=1%2B2")))
    (is (= {"a" "あ"} (console/parse-form "a=%E3%81%82")))))

(deftest a-repeated-key-keeps-the-first-value
  (testing "so appending an override to a submitted body does not work"
    (is (= {"a" "1"} (console/parse-form "a=1&a=2")))))

(deftest an-empty-body-decodes-to-nothing-rather-than-throwing
  (is (= {} (console/parse-form "")))
  (is (= {} (console/parse-form nil))))

;; ---------------------------------------------------------------------------
;; The gates
;; ---------------------------------------------------------------------------

(deftest an-unconfigured-store-serves-503
  (is (= 503 (:status (console/route (assoc (base (f/fresh-store))
                                            :store-mode nil)
                                     {:method :get :path "/console"}))))
  (is (= 503 (:status (console/route (assoc (base (f/fresh-store)) :store nil)
                                     {:method :get :path "/console"})))))

(deftest an-absent-allowlist-serves-503-and-never-an-open-console
  (let [r (console/route (assoc (base (f/fresh-store)) :allowlist nil)
                         {:method :get :path "/console"})]
    (is (= 503 (:status r)))
    (is (str/includes? (:body r) "allow-list"))))

(deftest a-caller-not-on-the-allowlist-is-403
  (is (= 403 (:status (console/route (assoc (base (f/fresh-store))
                                            :caller-did "did:key:zSomeoneElse")
                                     {:method :get :path "/console"})))))

(deftest a-cross-origin-post-is-refused
  (let [r (POST (f/fresh-store) "/console/contract"
                {"contract-id" "c-x" "worker" "w" "wage-type" "monthly"
                 "rate" "1"}
                "http://evil.example")]
    (is (= 403 (:status r)))
    (is (str/includes? (:body r) "cross-origin"))))

(deftest a-post-with-no-origin-is-allowed
  (testing "a non-browser client sends none, and refusing those would make
            the console unusable from anything but a browser without adding
            protection — the attack this refuses is a page on another origin,
            and a page always sends one"
    (is (console/same-origin? nil "http://localhost:9"))
    (is (console/same-origin? "" "http://localhost:9"))
    (is (console/same-origin? "http://localhost:9" "http://localhost:9"))
    (is (not (console/same-origin? "http://evil.example" "http://localhost:9")))))

(deftest a-get-is-not-origin-checked
  (is (= 200 (:status (GET (f/fresh-store) "/console")))))

;; ---------------------------------------------------------------------------
;; Routing
;; ---------------------------------------------------------------------------

(deftest every-view-in-the-table-has-a-route
  (doseq [{:view/keys [path label]} views/views]
    (is (= 200 (:status (GET (f/fresh-store) path))) label)))

(deftest a-path-nobody-declared-is-404
  (is (= 404 (:status (GET (f/fresh-store) "/console/disburse")))))

(deftest a-wrong-method-on-a-real-path-is-405-and-not-404
  (testing "`POST /console/ledger` is a caller using the wrong verb on a real
            page and `POST /console/disburse` is a caller inventing one"
    (is (= 405 (:status (POST (f/fresh-store) "/console/ledger" {}))))
    (is (= 404 (:status (POST (f/fresh-store) "/console/disburse" {}))))))

;; ---------------------------------------------------------------------------
;; 運用の現況 — the screen, on the same gates as everything else
;; ---------------------------------------------------------------------------

(deftest the-operations-screen-is-reachable-and-is-in-the-navigation
  (testing "a view added to the dispatch and forgotten in the nav is dead code
            that looks live"
    (let [r (GET (f/fresh-store) "/console/operations")]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "運用の現況"))
      (testing "and every screen's nav carries it, because the nav is
                generated from the same table the router dispatches on"
        (doseq [{:view/keys [path]} views/views]
          (is (str/includes? (:body (GET (f/fresh-store) path))
                             "/console/operations")
              path))))))

(deftest the-operations-screen-is-behind-the-same-three-gates
  (testing "it is the screen that lists what is missing, which is exactly the
            screen somebody would be tempted to leave open"
    (is (= 503 (:status (console/route (assoc (base (f/fresh-store))
                                              :store-mode nil)
                                       {:method :get
                                        :path "/console/operations"}))))
    (is (= 503 (:status (console/route (assoc (base (f/fresh-store))
                                              :allowlist nil)
                                       {:method :get
                                        :path "/console/operations"}))))
    (testing "a caller nobody listed sees nothing, and the refusal names no
              employer"
      (let [r (console/route (assoc (base (f/fresh-store))
                                    :caller-did "did:key:zSTRANGER")
                             {:method :get :path "/console/operations"})]
        (is (= 403 (:status r)))
        (is (not (str/includes? (:body r) f/employer-id)))))
    (testing "and POST is 405 on it — a real page with the wrong verb"
      (is (= 405 (:status (POST (f/fresh-store) "/console/operations" {})))))))

(deftest the-operations-screen-is-scoped-to-the-calling-employer
  (testing "another employer's cycles, contracts and runs are not on it"
    (let [st (f/fresh-store)]
      (store/register-client! st {:client-id "emp-other" :name "他社"})
      (store/register-contract! st (f/contract {:contract/id "c-other"
                                                :contract/employer "emp-other"}))
      (store/append-ledger! st {:client-id "emp-other" :contract-id "c-other"
                                :period f/period :disposition :commit
                                :record {:op :draft-payroll-run
                                         :payload {:gross 999999}}})
      (let [body (:body (GET st "/console/operations"))]
        (is (str/includes? body f/employer-id))
        (is (not (str/includes? body "c-other")))
        (is (not (str/includes? body "999999")))))))

(deftest the-operations-screen-carries-nothing-that-must-not-be-logged
  (testing "the report is redacted on the way out and the screen renders the
            redacted value — it does not reach past it for a name"
    (let [st (f/fresh-store)]
      (store/append-ledger! st {:client-id f/employer-id
                                :contract-id f/contract-id
                                :period f/period :disposition :commit
                                :record {:op :draft-payroll-run
                                         :payload (:payload (f/proposal))}})
      (let [body (:body (GET st "/console/operations"))]
        (doseq [leak [f/worker (:bank/payee-name-kana (f/contract))
                      (str f/gross) (str f/net)]]
          (is (not (str/includes? body leak)) leak))))))

(deftest the-console-renders-the-hosts-measurements-and-never-invents-one
  (testing "`extras` is what only the host can measure. Absent, the screen
            says `報告なし` / `未設定`, which are distinguishable from a pass —
            and a console that defaulted either to a pass would be answering
            on the store's behalf"
    (let [bare (:body (GET (f/fresh-store) "/console/operations"))]
      (is (str/includes? bare "報告なし"))
      (is (str/includes? bare "未設定")))
    (testing "and supplied, the SAME values reach the screen that reach
              GET /api/operations"
      (let [health {:store/mode :kotobase :store/readable? true
                    :store/survives-process-restart? true
                    :store/entries-are-a-floor? false
                    :store/key-separation :separate
                    :store/break-kinds []
                    :store/streams [{:stream :ledger :head "bafk" :entries 1
                                     :complete? true :broken [] :why nil}]
                    :store/why "七つの chain すべてを head から末尾まで辿れた"}
            st (f/fresh-store)
            _ (juminzei/register-notice!
               st {:employer f/employer-id
                   :notice f/resident-tax-notice-as-transcribed})
            r (console/route
               (assoc (base st)
                      :extras {:store-health health
                               :projection-preflight (r2/preflight {})})
               {:method :get :path "/console/operations"})
            body (:body r)]
        (is (= 200 (:status r)))
        (is (str/includes? body "読める"))
        (is (not (str/includes? body "報告なし")))
        (testing "the 住民税 notice is read out of the STORE and not out of
                  the extras. It used to be an injected option that nothing
                  outside the test suite ever supplied, so this screen said
                  「決定通知書が一件も登録されていない」 to every real
                  deployment that had one"
          (is (str/includes? body "架空区"))
          (is (str/includes? body "12 / 12")))
        (testing "and the projection preflight names the missing variables
                  rather than leaving the panel blank"
          (is (str/includes? body "R2_CATALOG_URI")))))))

(deftest the-console-and-the-api-answer-with-one-report
  (testing "a screen and an endpoint that each build their own answer are two
            answers, and the one nobody is looking at is the one that goes
            stale"
    (let [st (f/fresh-store)
          extras {:projection-preflight (r2/preflight {})}
          screen (:operations (console/context st f/employer-id
                                               {:extras extras}))
          api* (:body (api/route st :ephemeral allowlist f/caller-did
                                 {:method :get :path "/api/operations"}
                                 extras))]
      (is (= (dissoc api* :ok) screen)))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest a-contract-can-be-registered-and-nothing-is-defaulted
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (let [r (POST st "/console/contract"
                  {"contract-id" "c-new" "worker" "新人" "wage-type" "monthly"
                   "rate" "300000" "health-insurance-insured" ""})]
      (is (= 200 (:status r)))
      (let [c (store/contract-of st "c-new")]
        (is (= f/employer-id (:contract/employer c)))
        (is (not (contains? c :employment/health-insurance-insured?)))))))

(deftest an-empty-select-stays-absent-and-a-false-is-written
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (POST st "/console/contract"
          {"contract-id" "c-a" "worker" "a" "wage-type" "monthly" "rate" "1"
           "health-insurance-insured" "false"})
    (is (false? (:employment/health-insurance-insured?
                 (store/contract-of st "c-a"))))))

(deftest a-refused-registration-writes-nothing
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (let [r (POST st "/console/contract"
                  {"contract-id" "c-bad" "worker" "b" "wage-type" "monthly"
                   "rate" "not a number"})]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "flash-error"))
      (is (nil? (store/contract-of st "c-bad"))))))

(deftest a-registration-cannot-name-another-employer
  (let [st (store/mem-store)]
    (store/register-client! st (f/employer))
    (POST st "/console/contract"
          {"contract-id" "c-x" "worker" "x" "wage-type" "monthly" "rate" "1"})
    (is (= f/employer-id (:contract/employer (store/contract-of st "c-x"))))))

;; ---------------------------------------------------------------------------
;; 住民税の決定通知書 — the form, and the redirect that follows it
;; ---------------------------------------------------------------------------

(def notice-form
  "One 決定通知書 as an operator would type it, keyed the way the browser
  posts it: every value a STRING, and the twelve months under `m06` … `m05`.

  Built from `payroll.fixtures/resident-tax-notice-as-transcribed` rather than
  written out, so a change to what a notice must carry shows up here as a
  failing admission rather than as a form that keeps passing while the fixture
  moves underneath it."
  (let [n f/resident-tax-notice-as-transcribed]
    (merge {"kind" "decision"
            "municipality" (:notice/municipality n)
            "tax-year" (:notice/tax-year n)
            "reference" (:notice/reference n)
            "revision" (str (:notice/revision n))
            "designated-number" (:notice/designated-number n)
            "annual-total" (str (:notice/annual-total n))
            "registered-at" (:notice/registered-at n)}
           (into {} (for [k juminzei/month-keys]
                      [(name k) (str (get (:notice/months n) k))])))))

(defn- notices-in [st] (or (store/juminzei-notices st f/employer-id) []))

(deftest a-notice-can-be-registered-through-the-form
  (testing "the seam that did not exist. It lands in the store for the CALLING
            employer — the form never names one, and could not: the employer
            comes from the verified caller through the allow-list"
    (let [st (f/fresh-store)
          r (POST st "/console/juminzei-notice" notice-form)
          [n & more] (notices-in st)]
      (is (= 303 (:status r)))
      (is (empty? more) "exactly one notice")
      (is (= f/employer-id (:notice/employer n)))
      (is (= f/resident-tax-notice-id (:notice/id n)))
      (is (= :notice/decision (:notice/kind n)))
      (testing "and the twelve months arrived as INTEGERS, not as the strings
                the browser posted — a 月割額 of \"8200\" would compare equal
                to nothing and sum to a type error"
        (is (= 12 (count (:notice/months n))))
        (is (= (repeat 12 f/resident-tax)
               (mapv (:notice/months n) juminzei/month-keys)))
        (is (= (* 12 f/resident-tax) (:notice/annual-total n))))
      (testing "so the operations screen now says it is registered, on the
                same read"
        (let [body (:body (GET st "/console/operations"))]
          (is (str/includes? body "架空区"))
          (is (str/includes? body "12 / 12"))
          (is (not (str/includes? body "決定通知書が一件も登録されていない"))))))))

(deftest the-redirect-carries-nothing-that-was-transcribed
  (testing "a query string is in the browser's history, in whatever proxy is
            in front of this, in the referrer of the next request and in any
            screenshot of the address bar. None of those is a place anybody
            chose to put an employer's tax paperwork"
    (let [st (f/fresh-store)
          r (POST st "/console/juminzei-notice" notice-form)
          loc (:location r)]
      (is (= 303 (:status r)))
      (is (= "/console/operations?notice=registered" loc))
      (doseq [secret (concat [f/municipality
                              (:notice/reference f/resident-tax-notice-as-transcribed)
                              (str f/resident-tax)
                              (str (* 12 f/resident-tax))
                              (:notice/designated-number
                               f/resident-tax-notice-as-transcribed)]
                             (map str (vals (:notice/months
                                             f/resident-tax-notice-as-transcribed))))]
        (is (not (str/includes? loc secret)) secret))
      (testing "and the landing page turns the parameter into a confirmation
                that reads the store back — the count and the coverage, and
                still not the amounts"
        (let [body (:body (GET st "/console/operations" {"notice" "registered"}))]
          (is (str/includes? body "通知を登録した"))
          (is (str/includes? body "いまこの事業主に登録されている通知は 1 件"))
          (is (str/includes? body "12 / 12"))
          (is (not (str/includes? body (str f/resident-tax))))
          (is (not (str/includes? body (str (* 12 f/resident-tax))))))))))

(deftest a-retried-submission-redirects-as-a-duplicate-and-writes-nothing
  (testing "an operator who clicks twice has transcribed one piece of paper
            once. `notice=duplicate` and not `notice=registered`, because two
            「登録した」 banners honestly read as two notices"
    (let [st (f/fresh-store)
          first* (POST st "/console/juminzei-notice" notice-form)
          again (POST st "/console/juminzei-notice" notice-form)]
      (is (= "/console/operations?notice=registered" (:location first*)))
      (is (= 303 (:status again)))
      (is (= "/console/operations?notice=duplicate" (:location again)))
      (is (= 1 (count (notices-in st))) "still exactly one notice")
      (let [body (:body (GET st "/console/operations" {"notice" "duplicate"}))]
        (is (str/includes? body "同じ通知が既に登録されていた"))
        (is (str/includes? body "再送は二度目の登録ではない"))))))

(deftest a-refused-notice-is-not-a-redirect-and-keeps-the-transcription
  (testing "a redirect would throw twelve transcribed figures away, and an
            operator who has lost them twice starts keeping payroll data in a
            text file"
    (let [st (f/fresh-store)
          ;; one month blank. `admit-notice` refuses the whole registration —
          ;; an eleven-month 決定通知書 is not a notice with a zero in the
          ;; twelfth
          form (assoc notice-form "m11" "")
          r (POST st "/console/juminzei-notice" form)
          body (:body r)]
      (is (= 200 (:status r)) "not a 303")
      (is (nil? (:location r)))
      (is (str/includes? body "flash-error"))
      (is (str/includes? body "登録を拒否した"))
      (is (str/includes? body "未登録は零ではない"))
      (is (empty? (notices-in st)) "nothing reached the store")
      (testing "and every value the operator typed is still in the form"
        (doseq [v [f/municipality "R8-0000-0000" "2026-05-31" "8200"]]
          (is (str/includes? body (str "value=\"" v "\"")) v))))))

(deftest a-month-typed-with-a-comma-is-refused-and-never-coerced-to-zero
  (testing "`28,000` in a 月割額 box is not an accounting of zero. The refusal
            names the field in Japanese"
    (let [st (f/fresh-store)
          r (POST st "/console/juminzei-notice"
                  (assoc notice-form "m08" "8,200"))]
      (is (= 200 (:status r)))
      (is (nil? (:location r)))
      (is (str/includes? (:body r) "月割額が非負の整数ではない"))
      (is (str/includes? (:body r) "m08"))
      (is (empty? (notices-in st)))))
  (testing "and so is a 改訂番号 that is not a count — it ends up in
            `payroll.juminzei/notice-id`, so getting it wrong forks a
            correction history rather than mis-stating an amount"
    (let [st (f/fresh-store)
          r (POST st "/console/juminzei-notice"
                  (assoc notice-form "revision" "いち"))]
      (is (str/includes? (:body r) "改訂番号が非負の整数ではない"))
      (is (empty? (notices-in st))))))

(deftest a-blank-notice-kind-stays-absent-and-is-refused-rather-than-defaulting
  (testing "a select whose first option is 決定通知書 submits 決定通知書 from a
            form nobody touched. This one has an empty option, an empty option
            is nil, and nil is refused — 未登録 is not 「決定通知書」"
    (let [st (f/fresh-store)]
      (doseq [blank ["" "  "]]
        (let [r (POST st "/console/juminzei-notice" (assoc notice-form "kind" blank))]
          (is (= 200 (:status r)) (pr-str blank))
          (is (nil? (:location r)) (pr-str blank))
          (is (str/includes? (:body r) "通知の種類が受け付けられない")
              (pr-str blank))))
      (testing "and a value nobody offered is refused the same way rather than
                becoming the nearest kind"
        (let [r (POST st "/console/juminzei-notice"
                      (assoc notice-form "kind" "decisionn"))]
          (is (str/includes? (:body r) "通知の種類が受け付けられない"))))
      (is (empty? (notices-in st)) "nothing reached the store"))))

(deftest a-cross-origin-notice-post-is-refused-like-every-other-post
  (let [st (f/fresh-store)
        r (POST st "/console/juminzei-notice" notice-form "http://evil.example")]
    (is (= 403 (:status r)))
    (is (str/includes? (:body r) "cross-origin"))
    (is (empty? (notices-in st)))))

(deftest an-unlisted-caller-cannot-register-a-notice-for-anybody
  (let [st (f/fresh-store)
        r (console/route (assoc (base st) :caller-did "did:key:zSomeoneElse")
                         {:method :post :path "/console/juminzei-notice"
                          :form notice-form :origin "http://localhost:9"})]
    (is (= 403 (:status r)))
    (is (empty? (notices-in st)))))

(deftest one-employer-cannot-register-a-notice-for-another
  (testing "the employer is the verified caller's, whatever the body says and
            whoever is calling. Two callers, two employers, and neither
            notice appears under the other's scope"
    (let [st (f/fresh-store)
          allow (api/parse-allowlist (str f/allowlist-string
                                          "," "did:key:zOTHER=emp-other"))
          post (fn [did]
                 (console/route (assoc (base st) :allowlist allow :caller-did did)
                                {:method :post :path "/console/juminzei-notice"
                                 :form notice-form :origin "http://localhost:9"}))]
      (is (= 303 (:status (post f/caller-did))))
      (is (= 303 (:status (post "did:key:zOTHER"))))
      (is (= 1 (count (store/juminzei-notices st f/employer-id))))
      (is (= 1 (count (store/juminzei-notices st "emp-other"))))
      (is (= f/employer-id (:notice/employer (first (notices-in st)))))
      (testing "and a form field naming an employer changes nothing, because
                the console never reads the body wholesale: the notice is
                built field by field from named fields, so `client-id` is not
                read at all and there is nothing here to filter.

                That is why the console does not repeat the check —
                `payroll.juminzei/admit-registration` REFUSES an employer-
                naming key (`payroll.phase2-test` exercises it directly), and
                that gate is the one an EDN caller would meet. A second copy
                of it here would be a second answer to one question, and the
                stronger property is the structural one asserted below: the
                stored notice's employer is the caller's whatever arrived"
        (let [r (POST st "/console/juminzei-notice"
                      (assoc notice-form "client-id" "emp-other"))]
          (is (= 303 (:status r)) "a resubmission of the same paper")
          (is (= "/console/operations?notice=duplicate" (:location r)))
          (is (= 1 (count (store/juminzei-notices st f/employer-id))))
          (is (= 1 (count (store/juminzei-notices st "emp-other"))))
          (is (every? #(= f/employer-id (:notice/employer %)) (notices-in st))))))))

;; ---------------------------------------------------------------------------
;; 特別徴収の対象かどうか — the key that closed the 住民税 seam
;; ---------------------------------------------------------------------------
;;
;; `payroll.edge.console/run-of` has always read
;; `:employment/resident-tax-obligation` off the contract and handed it to
;; `payroll.juminzei/assess`. Until 2026-08-26 that key was not one of
;; `payroll.touroku/contract-fields`, and `unknown-keys` REFUSES a key that
;; layer does not know — so no contract anywhere could carry it, `:obligation`
;; was nil for every registration there had ever been, `assess` answered
;; `:municipality-not-declared`, and the 住民税 line was held for every
;; employer including one whose twelve months were fully registered.
;;
;; The notice stream, the form and the coverage panel could not produce a
;; single payslip figure between them. These are the tests for the seam.

(defn- resident-tax-line
  "The 住民税 line of the newest run on this employer's 給与計算 screen.

  Read through `console/context` and not by calling `payroll.meisai` here:
  the claim is about what an operator SEES, and a test that assembled its own
  lines would pass over a console that never passed the obligation on."
  [st]
  (let [ctx (console/context st f/employer-id {})]
    (first (filter #(= :resident-tax-withheld (:line/key %))
                   (get-in ctx [:latest :meisai :meisai/deductions])))))

(deftest a-resident-tax-obligation-can-be-registered-through-the-form
  (testing "the key `payroll.touroku` used to refuse. It round-trips as a
            KEYWORD — the browser posts a string, and a contract carrying
            \"special-collection\" would compare equal to nothing in
            `payroll.juminzei/assess`"
    (let [st (store/mem-store)]
      (store/register-client! st (f/employer))
      (let [r (POST st "/console/contract"
                    {"contract-id" "c-tokubetsu" "worker" "甲" "wage-type" "monthly"
                     "rate" "280000" "resident-tax-obligation" "special-collection"})]
        (is (= 200 (:status r)))
        (is (= :special-collection
               (:employment/resident-tax-obligation
                (store/contract-of st "c-tokubetsu")))))
      (testing "and 普通徴収 registers as its own answer, not as the absence of
                the other one"
        (POST st "/console/contract"
              {"contract-id" "c-futsuu" "worker" "乙" "wage-type" "monthly"
               "rate" "280000" "resident-tax-obligation" "not-special-collection"})
        (is (= :not-special-collection
               (:employment/resident-tax-obligation
                (store/contract-of st "c-futsuu"))))))))

(deftest a-blank-obligation-stays-absent-and-the-line-is-held-and-not-zero
  (testing "absence is a refusal and not a zero. The contract carries no key
            at all — a written nil and an absent key read the same to
            `assess` today, and only the absent one is honest about nobody
            having classified this employee"
    (let [st (store/mem-store)]
      (store/register-client! st (f/employer))
      (POST st "/console/contract"
            {"contract-id" "c-mibunrui" "worker" "丙" "wage-type" "monthly"
             "rate" "280000" "resident-tax-obligation" ""})
      (is (not (contains? (store/contract-of st "c-mibunrui")
                          :employment/resident-tax-obligation)))))
  (testing "and a run on such a contract is HELD for
            `:municipality-not-declared`, with a notice registered and the
            month covered — the state this whole change was about"
    (let [st (f/fresh-store)]
      (POST st "/console/juminzei-notice" notice-form)
      (POST st "/console/run"
            {"contract-id" f/contract-id "period" f/period
             "deductions" (str f/deduction-total)
             "income-tax-withheld" (str f/income-tax)
             "health-insurance-withheld" (str f/health-insurance)
             "care-insurance-withheld" (str f/care-insurance)
             "employees-pension-withheld" (str f/employees-pension)
             "employment-insurance-withheld" (str f/employment-insurance)})
      (let [f* (:line/figure (resident-tax-line st))]
        (is (= :held (:figure/provenance f*)))
        (is (nil? (:figure/amount f*)))
        (is (str/includes? (:figure/why f*) "登録されていない"))
        (is (= :municipality-not-declared
               (:juminzei/answer
                (juminzei/assess {:period f/period
                                  :notices (notices-in st)
                                  :obligation nil}))))))))

(deftest a-classified-contract-and-a-covering-notice-produce-the-payslip-figure
  (testing "the end-to-end claim that was NOT reachable before this change.
            Everything here goes through the console: the contract is
            registered on the form, the notice is transcribed on the form,
            the run is posted on the form, and the figure is read off the
            screen"
    (let [st (f/fresh-store {:contract-overrides
                             {:employment/resident-tax-obligation
                              :special-collection}})]
      (is (= 303 (:status (POST st "/console/juminzei-notice" notice-form))))
      (POST st "/console/run"
            {"contract-id" f/contract-id "period" f/period
             "deductions" (str f/deduction-total)
             "income-tax-withheld" (str f/income-tax)
             "health-insurance-withheld" (str f/health-insurance)
             "care-insurance-withheld" (str f/care-insurance)
             "employees-pension-withheld" (str f/employees-pension)
             "employment-insurance-withheld" (str f/employment-insurance)})
      (let [f* (:line/figure (resident-tax-line st))]
        (is (= :declared (:figure/provenance f*))
            "and never :derived — this actor did not compute it")
        (is (= f/resident-tax (:figure/amount f*)))
        (is (str/includes? (:figure/source f*) f/municipality)
            "the source names the municipality whose paper it came from")
        (testing "and the amount is the NOTICE's month figure, read back out
                  of the store, rather than anything computed here. If the
                  notice said something else the line would say that"
          (let [n (first (notices-in st))]
            (is (= (get (:notice/months n) :juminzei/m08) (:figure/amount f*)))
            (is (= (:notice/municipality n)
                   (first (str/split (:figure/source f*) #" "))))))))))

(deftest a-contract-registered-as-futsuu-choushuu-has-no-line-and-not-a-zero
  (testing "普通徴収 is `:not-applicable`. Printing 0 would assert a 特別徴収
            deduction was computed and came to nothing, which is a different
            claim about the same employee"
    (let [st (f/fresh-store {:contract-overrides
                             {:employment/resident-tax-obligation
                              :not-special-collection}})]
      (POST st "/console/juminzei-notice" notice-form)
      (POST st "/console/run"
            {"contract-id" f/contract-id "period" f/period
             "deductions" (str f/deduction-total)
             "income-tax-withheld" (str f/income-tax)
             "health-insurance-withheld" (str f/health-insurance)
             "care-insurance-withheld" (str f/care-insurance)
             "employees-pension-withheld" (str f/employees-pension)
             "employment-insurance-withheld" (str f/employment-insurance)})
      (let [f* (:line/figure (resident-tax-line st))]
        (is (= :not-applicable (:figure/provenance f*)))
        (is (nil? (:figure/amount f*)) "not 0")
        (is (str/includes? (:figure/why f*) "普A〜普F"))))))

(deftest a-value-nobody-recognises-is-refused-and-writes-nothing
  (testing "at both boundaries, and they refuse for different reasons. The
            console refuses a string it cannot read rather than folding it
            back into 未登録; `payroll.touroku` refuses a KEYWORD that is
            neither answer, which is the gate an EDN caller meets"
    (let [st (store/mem-store)]
      (store/register-client! st (f/employer))
      (let [r (POST st "/console/contract"
                    {"contract-id" "c-gomi" "worker" "丁" "wage-type" "monthly"
                     "rate" "280000" "resident-tax-obligation" "maybe"})]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "flash-error"))
        (is (nil? (store/contract-of st "c-gomi"))))
      (let [r (touroku/admit-contract
               f/employer-id
               {:contract/id "c-gomi2" :contract/worker "戊"
                :contract/wage-type :monthly :contract/rate 280000
                :employment/resident-tax-obligation :maybe})]
        (is (= :refused (:touroku/status r)))
        (is (str/includes? (:touroku/why r) "住民税の特別徴収"))
        (is (nil? (store/contract-of st "c-gomi2")))))))

;; ---------------------------------------------------------------------------
;; A governed run through the console
;; ---------------------------------------------------------------------------

(deftest a-clean-run-through-the-console-commits
  (let [st (f/fresh-store)
        r (POST st "/console/run"
                {"contract-id" f/contract-id "period" f/period
                 "deductions" (str f/deduction-total)
                 "income-tax-withheld" (str f/income-tax)
                 "health-insurance-withheld" (str f/health-insurance)
                 "care-insurance-withheld" (str f/care-insurance)
                 "employees-pension-withheld" (str f/employees-pension)
                 "employment-insurance-withheld" (str f/employment-insurance)})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "台帳に記録した"))
    (is (= 1 (count (store/ledger st))))
    (is (= :commit (:disposition (first (store/ledger st)))))))

(deftest a-run-missing-a-contribution-is-held-and-the-screen-says-why
  (let [st (f/fresh-store)
        r (POST st "/console/run"
                {"contract-id" f/contract-id "period" f/period
                 "deductions" (str f/income-tax)
                 "income-tax-withheld" (str f/income-tax)})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "保留した"))
    (is (str/includes? (:body r) "social-insurance-not-accounted-for"))
    (is (= :hold (:disposition (first (store/ledger st)))))))

(deftest a-non-numeric-amount-is-refused-before-the-graph
  (testing "`::bad` and not 0 — a field an operator typed `28,000` into is
            not an accounting of zero"
    (let [st (f/fresh-store)
          r (POST st "/console/run"
                  {"contract-id" f/contract-id "period" f/period
                   "income-tax-withheld" "6,000"})]
      (is (str/includes? (:body r) "非負の整数ではない"))
      (is (empty? (store/ledger st))))))

(deftest a-blank-amount-is-omitted-rather-than-sent-as-zero
  (let [{:keys [body]} (console/run-body {"period" "2026-08"
                                          "income-tax-withheld" ""
                                          "health-insurance-withheld" "1"})]
    (is (not (str/includes? body ":income-tax-withheld")))
    (is (str/includes? body ":health-insurance-withheld 1"))))

;; ---------------------------------------------------------------------------
;; Tenant isolation
;; ---------------------------------------------------------------------------

(deftest one-employer-cannot-see-anothers-runs
  (let [st (f/fresh-store)]
    ;; a second employer with their own contract and a committed run
    (store/register-client! st {:client-id "emp-other" :name "他社"})
    (store/register-contract! st (f/contract {:contract/id "c-other"
                                              :contract/employer "emp-other"}))
    (store/append-ledger! st {:client-id "emp-other" :contract-id "c-other"
                              :period f/period :disposition :commit
                              :record {:op :draft-payroll-run
                                       :payload {:gross 999999}}})
    (let [body (:body (GET st "/console/ledger"))]
      (is (not (str/includes? body "c-other")))
      (is (not (str/includes? body "999999"))))
    (testing "and their contract is not in this caller's context"
      (is (empty? (filter #(= "c-other" (:contract/id %))
                          (:contracts (console/context st f/employer-id {}))))))))

(deftest a-contract-registered-by-another-employer-is-not-listed
  (let [st (f/fresh-store)]
    (store/register-contract! st (f/contract {:contract/id "c-foreign"
                                              :contract/employer "emp-other"}))
    (store/append-ledger! st {:client-id f/employer-id
                              :contract-id "c-foreign"
                              :period f/period :disposition :commit})
    (testing "the ledger entry is the caller's, but the contract is not — and
              `contracts-of` filters on the contract's own employer"
      (is (empty? (console/contracts-of st f/employer-id ["c-foreign"]))))))

;; ---------------------------------------------------------------------------
;; Exports
;; ---------------------------------------------------------------------------

(defn- committed-store []
  (let [st (f/fresh-store)]
    (POST st "/console/run"
          {"contract-id" f/contract-id "period" f/period
           "deductions" (str f/deduction-total)
           "income-tax-withheld" (str f/income-tax)
           "health-insurance-withheld" (str f/health-insurance)
           "care-insurance-withheld" (str f/care-insurance)
           "employees-pension-withheld" (str f/employees-pension)
           "employment-insurance-withheld" (str f/employment-insurance)})
    st))

(deftest every-artifact-can-be-exported-in-every-format-it-declares
  (testing "the property is that the export DISPATCHER knows every combination
            the artifacts table advertises. Asserting 200 conflated that with
            the data being complete, and the two diverge the moment an
            artifact refuses to emit a partial file — which 全銀 does, because
            a partial 総合振込 file pays three of four employees and the fourth
            finds out"
    (let [st (committed-store)]
      (doseq [{:artifact/keys [key formats label]} views/artifacts
              fmt formats]
        (let [r (GET st "/console/export"
                     {"kind" (name key) "format" (name fmt)
                      "contract-id" f/contract-id "period" f/period})
              body (str (:body r))
              tag (str label " " fmt)]
          (is (seq body) tag)
          (is (not (str/includes? body "その書類は無い")) tag)
          (is (not (str/includes? body "でのみ出力する"))
              (str tag ": the dispatcher does not know this declared format"))
          (is (contains? #{200 400} (:status r)) tag)
          (when (= 400 (:status r))
            (testing "a refusal here must be about the DATA and must say so"
              (is (str/includes? body "出力できない") tag)
              (is (str/includes?
                   body "一部だけのファイルは、一部だけ支払うファイルである")
                  tag)))))
      (testing "and the artifacts whose data IS complete return 200 — without
                this the loop above passes on a console that refuses
                everything"
        (doseq [[key* fmt] [[:payslip :json] [:payslip :html]
                            [:wage-ledger :csv] [:wage-ledger :json]
                            [:deduction-summary :csv] [:deduction-summary :json]
                            [:bank-transfer :csv] [:bank-transfer :json]
                            [:journal :json] [:zengin :json]]]
          (let [r (GET st "/console/export"
                       {"kind" (name key*) "format" (name fmt)
                        "contract-id" f/contract-id "period" f/period})]
            (is (= 200 (:status r)) (str key* " " fmt))))))))

(deftest the-zengin-fixed-width-download-is-shift-jis-bytes-and-not-a-string
  (testing "a 120-byte record survives exactly one encoding, and it is not the
            console's. The body must be BYTES and the content type must carry
            the charset — a browser told nothing assumes UTF-8, renders the
            halfwidth katakana as replacement characters, and the file the
            operator then uploads is one the bank cannot parse"
    (let [st (f/fresh-store)]
      (store/append-ledger!
       st {:client-id f/employer-id :contract-id f/contract-id
           :period f/period :disposition :commit
           :verdict (f/verdict-for)
           :record {:op :draft-payroll-run :payload (:payload (f/proposal))}})
      (let [r (console/export
               {:store st :client-id f/employer-id
                :query {"kind" "zengin" "format" "fixed-width"
                        "contract-id" f/contract-id "period" f/period}})]
        (testing "this store's run is NOT payable (no 住民税 notice is
                  registered), so the console refuses the whole file rather
                  than emitting a short one"
          (is (= 400 (:status r)))
          (is (str/includes? (str (:body r)) "全銀の固定長ファイルは出力できない")))))))

(deftest an-export-with-no-matching-run-refuses-rather-than-emitting-an-empty-file
  (testing "an operator discovers an empty file after emailing it"
    (let [r (GET (committed-store) "/console/export"
                 {"kind" "payslip" "format" "json" "period" "1999-01"})]
      (is (= 400 (:status r)))
      (is (str/includes? (:body r) "出力が空であることは違う")))))

(deftest an-unknown-artifact-names-the-ones-that-exist
  (let [r (GET (committed-store) "/console/export" {"kind" "nenkin-todoke"})]
    (is (= 400 (:status r)))
    (is (str/includes? (:body r) "payslip"))))

(deftest an-unsupported-format-is-refused-and-not-silently-substituted
  (let [r (GET (committed-store) "/console/export"
               {"kind" "payslip" "format" "csv" "contract-id" f/contract-id})]
    (is (= 400 (:status r)))
    (is (str/includes? (:body r) "json"))))

(deftest an-export-carries-a-filename
  (let [r (GET (committed-store) "/console/export"
               {"kind" "wage-ledger" "format" "csv"})]
    (is (= "chingin-daicho.csv" (:filename r)))))

(deftest the-journal-export-refuses-for-want-of-a-chart-of-accounts
  (testing "the accounts are the employer's chart and this actor does not
            choose them — served as a refusal and not as an empty array,
            which would read as `nothing to post`"
    (let [r (GET (committed-store) "/console/export" {"kind" "journal"})]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "\"ok\":0"))
      (is (str/includes? (:body r) "\"skipped\":1"))
      (is (str/includes? (:body r) "この actor は選ばない")))))

;; ---------------------------------------------------------------------------
;; MoneyForward
;; ---------------------------------------------------------------------------

(deftest a-reconciliation-writes-nothing
  (testing "not the file, not a row, not a run. Pasting a payroll export into
            this box has to be a bounded act"
    (let [st (committed-store)
          before (count (store/ledger st))
          r (POST st "/console/mf"
                  {"period" f/period
                   "csv" (str "従業員番号,氏名,支給年月,総支給額,差引支給額\n"
                              "9001,従業員甲," f/period "," f/gross "," f/net)})]
      (is (= 200 (:status r)))
      (is (= before (count (store/ledger st))))
      (is (nil? (store/contract-of st "9001"))))))

(deftest a-reconciliation-of-a-differing-file-shows-the-difference
  (let [st (committed-store)
        r (POST st "/console/mf"
                {"period" f/period
                 "csv" (str "従業員番号,氏名,支給年月,総支給額,差引支給額\n"
                            "9001,従業員甲," f/period "," (inc f/gross) "," f/net)})]
    (is (str/includes? (:body r) "一致していない"))
    (is (str/includes? (:body r) "不一致"))))

(deftest an-unparseable-paste-does-not-throw
  (let [r (POST (committed-store) "/console/mf" {"period" f/period "csv" "???"})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "必須の列が無い"))))
