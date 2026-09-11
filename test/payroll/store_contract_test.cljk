(ns payroll.store-contract-test
  "MemStore ≡ DatomicStore for the payroll store.

  Every assertion in this file runs against BOTH backends, out of one
  `backends` map. That is the whole point of it: a second backend that quietly
  disagrees with the first is worse than no second backend at all, because the
  disagreement only shows up in production, on the deployment that has the
  durable one.

  Three properties are load-bearing here and the file is organised around them.

  **Timesheets are the wage.** `kotoba.labor/wages-for` sums `:ts/hours` over
  whatever `timesheets-of` returns and the governor recomputes `:gross` from
  it. A backend that dropped an entry, or leaked another worker's entries into
  the sum, would not raise anything — it would compute a different lawful-
  looking wage, and the governor would then HOLD the honest proposal for
  `:wage-mismatch`. The disagreement surfaces as a payroll clerk being told
  their arithmetic is wrong.

  **The statute's facts survive the round trip.** `:jurisdiction` on the
  employer and `:employment/recipient-residency` / `:employment/paid-in` on the
  contract are what 所得税法 第百八十三条第一項 turns on. A backend that dropped
  one of them would silently move a run between `:checked`, `:out-of-scope` and
  `:not-declared` — that is, between three different answers about whether the
  law was even consulted.

  **The ledger is ordered, append-only and scoped.** It is the only record of
  what this actor refused. A backend that returned it out of order would
  produce an audit trail in which the corrected run precedes the held one, and
  nothing in the actor would notice — the graph never reads the ledger back."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.labor :as labor]
            [payroll.actor :as actor]
            [payroll.juminzei :as juminzei]
            [payroll.store :as store]))

(def backends {:mem store/mem-store :datomic store/datomic-store})

(def ^:private employer
  {:client-id "emp-jp" :name "Hanako's Bakery" :jurisdiction [:jp]})

(def ^:private employment
  (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
         {:employment/recipient-residency :resident
          :employment/paid-in :domestic}
         ;; 社会保険: six more operator-registered facts that must survive the
         ;; blob round trip. A backend that dropped
         ;; `:employment/standard-remuneration-monthly-yen` would move a run
         ;; from committed to HELD; one that dropped a coverage boolean would
         ;; move it from `accounted for` to `nobody observed whether this
         ;; worker is insured`. Both are silent and both happen only where the
         ;; durable backend is deployed — the same argument this file already
         ;; makes for `:employment/paid-in`.
         {:employment/health-insurance-insured? true
          :employment/employees-pension-insured? true
          :employment/care-insurance-second-category? false
          :employment/employment-insurance-insured? true
          :employment/standard-remuneration-monthly-yen 280000
          :employment/standard-remuneration-month "2026-06"}))

(defn- seeded [make]
  (doto (make)
    (store/register-client! employer)
    (store/register-contract! employment)
    (store/register-timesheet! (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! (labor/timesheet "worker-1" "2026-07-02" 6))))

(defn- each-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (seeded make)))))

(defn- each-empty-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (make)))))

;; ---------------------------------------------------------------------------
;; Directory reads
;; ---------------------------------------------------------------------------

(deftest an-employer-round-trips-with-the-jurisdiction-the-governor-reads
  (each-backend
   (fn [st]
     (is (= employer (store/client st "emp-jp")))
     (testing "the jurisdiction lives here and nowhere a proposal can reach it"
       (is (= [:jp] (:jurisdiction (store/client st "emp-jp")))))
     (testing "an unregistered employer is nil, which is a HARD hold"
       (is (nil? (store/client st "ghost")))))))

(deftest an-employer-that-declares-no-jurisdiction-round-trips-declaring-none
  (testing "absent and `an unknown jurisdiction` are different facts: the first
            consults no withholding law at all, the second is a HARD hold. A
            backend that turned one into the other would move a run between
            passing and being refused"
    (each-backend
     (fn [st]
       (store/register-client! st {:client-id "emp-silent" :name "Quiet Co"})
       (store/register-client! st {:client-id "emp-atlantis" :name "Elsewhere"
                                   :jurisdiction [:atlantis]})
       (is (nil? (:jurisdiction (store/client st "emp-silent"))))
       (is (= [:atlantis] (:jurisdiction (store/client st "emp-atlantis"))))))))

(deftest a-sub-national-jurisdiction-path-survives-as-a-path
  (each-backend
   (fn [st]
     (store/register-client! st {:client-id "emp-us" :jurisdiction [:us :ca]})
     (is (= [:us :ca] (:jurisdiction (store/client st "emp-us")))))))

(deftest re-registering-an-employer-id-replaces-rather-than-forks
  (each-backend
   (fn [st]
     (store/register-client! st (assoc employer :jurisdiction [:atlantis]))
     (is (= [:atlantis] (:jurisdiction (store/client st "emp-jp"))))
     (testing "one employer, relocated — not two disagreeing about the law that
               applies to the same payroll"
       (is (= 1 (count (distinct [(store/client st "emp-jp")]))))))))

(deftest the-statute-bearing-contract-fields-survive-the-round-trip
  (testing "a backend that dropped one of these would move the run between
            :checked, :out-of-scope and a withholding HOLD, silently, and only
            where it is deployed"
    (each-backend
     (fn [st]
       (let [c (store/contract-of st "c-jp")]
         (is (= :resident (:employment/recipient-residency c))
             "所得税法 第百八十三条第一項 binds a payer 「居住者に対し」")
         (is (= :domestic (:employment/paid-in c))
             "…「国内において」")
         (is (= :hourly (:contract/wage-type c)))
         (is (= 2000 (:contract/rate c)))
         (is (= "emp-jp" (:contract/employer c))
             "ownership, which the governor's :contract-wrong-employer reads")
         (is (= employment c)))))))

(deftest a-contract-silent-about-residency-round-trips-as-silent
  (testing "silence is the UNCHECKED case, not the article's exclusion — a
            backend that coerced a missing residency to :resident or to
            :non-resident would be answering the question for the operator"
    (each-backend
     (fn [st]
       (store/register-contract!
        st (labor/contract "c-silent" "worker-2" "emp-jp" "baker" :hourly 1000))
       (let [c (store/contract-of st "c-silent")]
         (is (nil? (:employment/recipient-residency c)))
         (is (nil? (:employment/paid-in c))))
       (testing "and an unregistered contract is nil, which is :unknown-contract"
         (is (nil? (store/contract-of st "c-999"))))))))

(deftest an-empty-store-answers-empty-not-nil
  (each-empty-backend
   (fn [st]
     (is (empty? (store/timesheets-of st "worker-1")))
     (is (empty? (store/records-of st "emp-jp")))
     (is (empty? (store/ledger st)))
     (is (empty? (store/ledger-of st "emp-jp")))
     (is (empty? (store/run-history st "c-jp"))))))

;; ---------------------------------------------------------------------------
;; Timesheets — the only admissible basis for an hourly wage
;; ---------------------------------------------------------------------------

(deftest timesheets-append-in-order-and-recompute-the-same-wage
  (testing "order is not decoration here: the governor recomputes :gross from
            exactly this sequence and holds the proposal if it disagrees"
    (each-backend
     (fn [st]
       (doseq [[d h] [["2026-07-03" 7] ["2026-07-04" 5] ["2026-07-05" 4]]]
         (store/register-timesheet! st (labor/timesheet "worker-1" d h)))
       (let [ts (store/timesheets-of st "worker-1")]
         (is (= 5 (count ts)))
         (is (= ["2026-07-01" "2026-07-02" "2026-07-03" "2026-07-04" "2026-07-05"]
                (mapv :ts/date ts)))
         (is (= [8 6 7 5 4] (mapv :ts/hours ts)))
         (is (= 60000 (labor/wages-for (store/contract-of st "c-jp") ts))
             "30h × 2000 — the number the governor will recompute"))))))

(deftest timesheets-are-scoped-to-their-worker
  (testing "a backend that leaked another worker's hours into this sum would
            pay one worker for another's shifts, and the arithmetic would
            still balance"
    (each-backend
     (fn [st]
       (store/register-timesheet! st (labor/timesheet "worker-2" "2026-07-01" 99))
       (is (= [8 6] (mapv :ts/hours (store/timesheets-of st "worker-1"))))
       (is (= [99] (mapv :ts/hours (store/timesheets-of st "worker-2"))))
       (is (= 28000 (labor/wages-for (store/contract-of st "c-jp")
                                     (store/timesheets-of st "worker-1"))))
       (is (empty? (store/timesheets-of st "worker-3")))))))

(deftest identical-timesheet-entries-both-land-rather-than-de-duplicating
  (testing "two eight-hour days on the same date are two shifts on this actor's
            reading — a store that collapsed them would halve the pay, and no
            rule anywhere would report it"
    (each-backend
     (fn [st]
       (dotimes [_ 3]
         (store/register-timesheet! st (labor/timesheet "worker-9" "2026-07-01" 8)))
       (is (= 3 (count (store/timesheets-of st "worker-9"))))
       (is (= 24 (reduce + (map :ts/hours (store/timesheets-of st "worker-9")))))))))

;; ---------------------------------------------------------------------------
;; The append-only streams
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-append-only-and-ordered
  (testing "an audit trail whose order depends on the backend is not one"
    (each-backend
     (fn [st]
       (doseq [n (range 6)]
         (store/append-ledger! st {:disposition :hold :client-id "emp-jp"
                                   :contract-id (str "c-" n) :n n}))
       (is (= 6 (count (store/ledger st))))
       (is (= [0 1 2 3 4 5] (mapv :n (store/ledger st))))
       (testing "and appending more does not disturb what is already there"
         (store/append-ledger! st {:disposition :commit :n 6})
         (is (= [0 1 2 3 4 5 6] (mapv :n (store/ledger st)))))))))

(deftest identical-ledger-facts-both-land-rather-than-de-duplicating
  (testing "two holds of the same run for the same reason are two events; a
            store that collapsed them would under-report a repeated attempt to
            get the same payroll past the governor"
    (each-backend
     (fn [st]
       (dotimes [_ 3]
         (store/append-ledger! st {:disposition :hold :contract-id "c-jp"
                                   :client-id "emp-jp"}))
       (is (= 3 (count (store/ledger st))))))))

(deftest records-append-in-order-and-are-scoped-to-their-employer
  (each-backend
   (fn [st]
     (store/register-client! st {:client-id "emp-2"})
     (doseq [n (range 4)]
       (store/commit-record! st {:client-id "emp-jp" :n n
                                 :payload {:gross (* 1000 n)}}))
     (store/commit-record! st {:client-id "emp-2" :n 99})
     (is (= [0 1 2 3] (mapv :n (store/records-of st "emp-jp"))))
     (is (= [99] (mapv :n (store/records-of st "emp-2"))))
     (is (empty? (store/records-of st "emp-3"))))))

;; ---------------------------------------------------------------------------
;; Derived reads over the ledger
;; ---------------------------------------------------------------------------

(deftest run-history-is-the-whole-life-of-one-contract-in-order
  (each-backend
   (fn [st]
     (store/append-ledger! st {:disposition :hold :contract-id "c-jp"
                               :client-id "emp-jp"})
     (store/append-ledger! st {:disposition :commit :contract-id "c-other"
                               :client-id "emp-jp"})
     (store/append-ledger! st {:disposition :commit :contract-id "c-jp"
                               :client-id "emp-jp"})
     (is (= [:hold :commit] (mapv :disposition (store/run-history st "c-jp"))))
     (testing "a contract nobody has heard of is empty, which is not a verdict"
       (is (= [] (store/run-history st "c-999"))))
     (testing "a nil id is nil, never every run that cited no contract"
       (store/append-ledger! st {:disposition :hold :client-id "emp-jp"
                                 :contract-id nil})
       (is (nil? (store/run-history st nil)))))))

(deftest ledger-of-scopes-to-one-employer
  (each-backend
   (fn [st]
     (store/append-ledger! st {:disposition :commit :contract-id "c-jp"
                               :client-id "emp-jp"})
     (store/append-ledger! st {:disposition :commit :contract-id "c-2"
                               :client-id "emp-2"})
     (is (= ["c-jp"] (mapv :contract-id (store/ledger-of st "emp-jp"))))
     (is (= ["c-2"] (mapv :contract-id (store/ledger-of st "emp-2"))))
     (is (nil? (store/ledger-of st nil))))))

;; ---------------------------------------------------------------------------
;; The actor runs unchanged on either backend
;; ---------------------------------------------------------------------------

(defn- run [make request thread]
  (let [st (seeded make)
        g (actor/build-graph {:store st})
        r (actor/run-request! g request {} thread)]
    {:store st :graph g :result r}))

(defn- observed [{:keys [store result]}]
  {:status (:status result)
   :disposition (get-in result [:state :disposition])
   :records (count (store/records-of store "emp-jp"))
   :gross (get-in result [:state :record :payload :gross])
   :ledger (mapv :disposition (store/ledger store))
   :contract-ids (mapv :contract-id (store/ledger store))
   :rules (mapv :rule (get-in result [:state :verdict :violations]))
   :withholding (get-in result [:state :verdict :tax :withholding :taxlaw/coverage])})

(def ^:private clean-run
  {:client-id "emp-jp" :op :draft-payroll-run :stake :low
   :contract-id "c-jp" :period "2026-07" :deductions 3000
   :income-tax-withheld 8420
   ;; 280000 × 183 / 2000 = 25620 exactly (厚年法 第八十一条第四項)
   :health-insurance-withheld 13860
   :employees-pension-withheld 25620
   :employment-insurance-withheld 168})

(deftest a-clean-run-commits-identically-on-both-backends
  (let [go #(observed (run % clean-run "t-1"))]
    (is (= (go store/mem-store) (go store/datomic-store)))
    (is (= {:status :done :disposition :commit :records 1 :gross 28000
            :ledger [:commit] :contract-ids ["c-jp"] :rules []
            :withholding :checked}
           (go store/datomic-store)))))

(deftest a-run-with-no-withholding-accounted-for-is-held-identically
  (testing "the wage is recomputed from the timesheet stream on both backends,
            so the ONLY violation must be the withholding one — a
            :wage-mismatch appearing here would mean a backend lost an hour"
    (let [go #(observed (run % (dissoc clean-run :income-tax-withheld) "t-2"))]
      (is (= (go store/mem-store) (go store/datomic-store)))
      (is (= {:status :done :disposition :hold :records 0 :gross nil
              :ledger [:hold] :contract-ids ["c-jp"]
              :rules [:income-tax-not-withheld] :withholding :checked}
             (go store/datomic-store))))))

(deftest invented-employment-is-held-identically-on-both-backends
  (let [go #(observed (run % (assoc clean-run :contract-id nil) "t-3"))]
    (is (= (go store/mem-store) (go store/datomic-store)))
    (is (= {:status :done :disposition :hold :records 0 :gross nil
            :ledger [:hold] :contract-ids [nil] :rules [:no-contract]
            :withholding :checked}
           (go store/datomic-store))
        "the withholding rules fire on the EMPLOYER's declared jurisdiction, so
         they still run for a run that cites no contract: the residency facts
         are simply absent, and absent stays IN scope. The run is held for
         :no-contract alone, and both backends must agree on that — a backend
         that lost :jurisdiction would report :not-declared here instead")))

(deftest a-disbursement-escalates-then-commits-identically-on-both-backends
  (testing "the escalation is in the ledger BEFORE the human signs, on both
            backends — otherwise `awaiting approval` and `never requested` are
            the same observation, and this is the op that moves real money"
    (let [go (fn [make]
               (let [{:keys [store graph] :as ctx}
                     (run make {:client-id "emp-jp" :op :disburse-wages
                                :stake :high :contract-id "c-jp"}
                          "t-4")
                     pending (observed ctx)]
                 (actor/approve! graph "t-4")
                 {:pending pending
                  :after-ledger (mapv :disposition (store/ledger store))
                  :after-records (count (store/records-of store "emp-jp"))
                  :history (mapv :disposition (store/run-history store "c-jp"))}))]
      (is (= (go store/mem-store) (go store/datomic-store)))
      (is (= {:pending {:status :interrupted :disposition :request-approval
                        :records 0 :gross nil :ledger [:request-approval]
                        :contract-ids ["c-jp"] :rules [] :withholding nil}
              :after-ledger [:request-approval :commit]
              :after-records 1
              :history [:request-approval :commit]}
             (go store/datomic-store))))))

(deftest what-taxlaw-could-not-say-survives-the-blob-round-trip
  (testing "the ledger carries the verdict, and the verdict's four-valued
            withholding report is exactly the part a naive encoder flattens"
    (let [go (fn [make jurisdiction contract-overrides]
               (let [st (make)]
                 (store/register-client! st (assoc employer :jurisdiction jurisdiction))
                 (store/register-contract! st (merge employment contract-overrides))
                 (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
                 (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
                 (actor/run-request! (actor/build-graph {:store st})
                                     clean-run {} "t-5")
                 (let [e (last (store/ledger st))]
                   {:disposition (:disposition e)
                    :coverage (get-in e [:verdict :tax :withholding :taxlaw/coverage])
                    :year-end (get-in e [:verdict :tax :year-end-adjustment
                                         :taxlaw/coverage])
                    :amount-checked? (get-in e [:verdict :tax :withholding
                                                :taxlaw/amount-checked?])
                    ;; 社会保険 is the second report a naive encoder flattens,
                    ;; and it is the deeper of the two — a map of four scheme
                    ;; reports rather than a handful of keywords.
                    :social-insurance (get-in e [:verdict :social-insurance
                                                 :shakai-hoken/answer])
                    :accounted (get-in e [:verdict :social-insurance
                                          :shakai-hoken/accounted])
                    :rules (mapv :rule (get-in e [:verdict :violations]))}))) ]
      (testing "an uncatalogued jurisdiction — :none, and a HARD hold"
        (is (= (go store/mem-store [:atlantis] {}) (go store/datomic-store [:atlantis] {})))
        ;; refused TWICE, by two bodies of law that were each unread for their
        ;; own reason. Collapsing them to one rule would tell an operator that
        ;; cataloguing the tax would be enough.
        (is (= {:disposition :hold :coverage :none :year-end :not-evaluated
                :amount-checked? nil :social-insurance :not-catalogued
                :accounted nil
                :rules [:unchecked-jurisdiction
                        :unchecked-social-insurance-jurisdiction]}
               (go store/datomic-store [:atlantis] {}))))
      (testing "no jurisdiction declared — :not-declared, and NOT a hold"
        (is (= (go store/mem-store nil {}) (go store/datomic-store nil {})))
        (is (= {:disposition :commit :coverage :not-declared
                :year-end :not-evaluated :amount-checked? nil
                :social-insurance :jurisdiction-not-declared :accounted nil
                :rules []}
               (go store/datomic-store nil {}))))
      (testing "declared outside the one article that was read — :out-of-scope"
        (let [overrides {:employment/paid-in :overseas}]
          (is (= (go store/mem-store [:jp] overrides)
                 (go store/datomic-store [:jp] overrides)))
          (is (= :out-of-scope (:coverage (go store/datomic-store [:jp] overrides))))))
      (testing "checked — and never certifying the amount"
        (is (= {:disposition :commit :coverage :checked :year-end :not-evaluated
                :amount-checked? false :social-insurance :answered
                :accounted [:scheme/health-insurance
                            :scheme/employees-pension
                            :scheme/employment-insurance]
                :rules []}
               (go store/datomic-store [:jp] {}))))
      (testing "and a contract whose 社会保険 registrations did NOT survive is
                held, on both backends"
        (let [overrides {:employment/standard-remuneration-monthly-yen nil}]
          (is (= (go store/mem-store [:jp] overrides)
                 (go store/datomic-store [:jp] overrides)))
          (is (= {:disposition :hold :coverage :checked :year-end :not-evaluated
                  :amount-checked? false :social-insurance :refused
                  :accounted [:scheme/employment-insurance]
                  :rules [:standard-remuneration-not-observed
                          :standard-remuneration-not-observed]}
                 (go store/datomic-store [:jp] overrides))))))))

(deftest the-backends-are-actually-two
  ;; Evidence floor. Every test above loops over `backends`; if that map ever
  ;; lost an entry the whole file would keep passing while checking ONE
  ;; implementation, which is the state this file exists to end. A contract
  ;; test that silently degrades to one backend passes forever.
  (is (= 2 (count backends)))
  (is (= #{:mem :datomic} (set (keys backends))))
  (testing "and they really are two different types, not one aliased twice"
    (is (not= (type (store/mem-store)) (type (store/datomic-store)))))
  (testing "and every backend in the map really implements the protocol"
    (doseq [[label make] backends]
      (is (satisfies? store/Store (make)) (str label " must implement Store")))))

;; ---------------------------------------------------------------------------
;; 住民税 notices — the seventh stream, on the same contract as everything else
;;
;; A notice stream that only one backend got right would be discovered the way
;; every other backend disagreement here would be: on the deployment that has
;; the durable one, months after the notice was transcribed, by an employee
;; whose 住民税 line stopped matching the paper they were sent.
;; ---------------------------------------------------------------------------

(def ^:private decision-notice
  {:notice/id "emp-jp/架空区/2026/decision/R8-0000-0000/0"
   :notice/employer "emp-jp"
   :notice/kind :notice/decision
   :notice/municipality "架空区"
   :notice/tax-year "2026"
   :notice/reference "R8-0000-0000"
   :notice/revision 0
   :notice/replaces nil
   :notice/registered-at "2026-05-31"
   :notice/annual-total (* 12 8200)
   :notice/months (into {} (for [k juminzei/month-keys] [k 8200]))})

(def ^:private revision-notice
  {:notice/id "emp-jp/架空区/2026/revision/R8-0000-0001/1"
   :notice/employer "emp-jp"
   :notice/kind :notice/revision
   :notice/municipality "架空区"
   :notice/tax-year "2026"
   :notice/reference "R8-0000-0001"
   :notice/revision 1
   :notice/replaces (:notice/id decision-notice)
   :notice/registered-at "2026-09-30"
   :notice/annual-total nil
   :notice/effective-from :juminzei/m10
   :notice/months (into {} (for [k (drop-while #(not= :juminzei/m10 %)
                                               juminzei/month-keys)]
                             [k 9000]))})

(deftest notices-append-in-order-and-are-scoped-to-their-employer
  (testing "registration order is the correction history: a store that
            returned the 決定通知書 after the 変更通知書 that replaced it would
            make the superseded figure look like the current one"
    (each-backend
     (fn [st]
       (store/register-juminzei-notice! st decision-notice)
       (store/register-juminzei-notice! st revision-notice)
       (store/register-juminzei-notice!
        st (assoc decision-notice :notice/employer "emp-2"
                  :notice/id "emp-2/架空区/2026/decision/R8-0000-0000/0"))
       (is (= [(:notice/id decision-notice) (:notice/id revision-notice)]
              (mapv :notice/id (store/juminzei-notices st "emp-jp"))))
       (testing "one employer's notices are another employer's tax bill"
         (is (= ["emp-2/架空区/2026/decision/R8-0000-0000/0"]
                (mapv :notice/id (store/juminzei-notices st "emp-2"))))
         (is (empty? (store/juminzei-notices st "emp-3"))))
       (testing "and a nil client-id is nil, never every notice that named
                 no employer"
         (is (nil? (store/juminzei-notices st nil))))))))

(deftest a-superseded-notice-is-still-in-the-store
  (testing "nothing is overwritten. The correction is a new entry naming what
            it replaces, and what is current is DERIVED — which is what lets
            the console show what a municipality corrected rather than only
            what it last said"
    (each-backend
     (fn [st]
       (store/register-juminzei-notice! st decision-notice)
       (store/register-juminzei-notice! st revision-notice)
       (let [all (store/juminzei-notices st "emp-jp")]
         (is (= 2 (count all)))
         (is (some #(= (:notice/id decision-notice) (:notice/id %)) all)
             "the replaced notice is still readable")
         (testing "and the derived effective set is the correction alone"
           (is (= [(:notice/id revision-notice)]
                  (mapv :notice/id (juminzei/effective-notices all))))))))))

(deftest the-twelve-months-survive-the-blob-round-trip-byte-for-byte
  (testing "a backend that dropped one month would not raise anything — it
            would silently reduce somebody's deduction for that month, and
            `payroll.juminzei/assess` would answer :month-not-in-notice, which
            reads as `the municipality never sent that month`"
    (each-backend
     (fn [st]
       (let [uneven (into {} (map-indexed (fn [i k] [k (+ 8000 i)])
                                          juminzei/month-keys))
             n (assoc decision-notice
                      :notice/months uneven
                      :notice/annual-total (reduce + (vals uneven)))]
         (store/register-juminzei-notice! st n)
         (let [back (first (store/juminzei-notices st "emp-jp"))]
           (is (= n back) "the whole record, not merely the months")
           (is (= uneven (:notice/months back)))
           (is (= 12 (count (:notice/months back))))
           (is (= (mapv uneven juminzei/month-keys)
                  (mapv (:notice/months back) juminzei/month-keys))
               "every month, in the collection order, with its own figure")
           (testing "the keyword keys survive as keywords rather than as
                     strings — `:juminzei/m06` read back as \"juminzei/m06\"
                     would make every month absent, which is not zero"
             (is (every? keyword? (keys (:notice/months back)))))
           (testing "and nil stays nil: a 変更通知書 has no 年税額, and a
                     backend that coerced that to 0 would assert the
                     municipality decided the year came to nothing"
             (store/register-juminzei-notice! st revision-notice)
             (let [r (last (store/juminzei-notices st "emp-jp"))]
               (is (nil? (:notice/annual-total r)))
               (is (contains? r :notice/annual-total))
               (is (= :juminzei/m10 (:notice/effective-from r)))))))))))

(deftest an-empty-store-answers-empty-for-notices-too
  (each-empty-backend
   (fn [st]
     (is (empty? (store/juminzei-notices st "emp-jp")))
     (is (nil? (store/juminzei-notices st nil))))))
