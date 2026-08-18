(ns payroll.governor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [kotoba.labor :as labor]
            [payroll.nenmatsu :as nenmatsu]
            [payroll.store :as store]
            [payroll.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "emp-1" :name "Hanako's Bakery"})
    (store/register-contract! st (labor/contract "c-1" "worker-1" "emp-1" "baker" :hourly 2000))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
    (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
    st))
;; registered hours: 14h × 2000 = 28000 gross

(defn- clean-proposal []
  {:op :draft-payroll-run :effect :propose :contract-id "c-1"
   :period "2026-07" :gross 28000 :deductions 3000 :net 25000
   :confidence 0.9 :stake :low})

(deftest ok-on-clean-recomputable-run
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {} (clean-proposal) st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-employer
  (let [st (fresh-store)
        v (governor/check {:client-id "no-such-employer"} {} (clean-proposal) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-missing-contract
  (testing "a payroll run without a contract is invented employment"
    (let [st (fresh-store)
          v (governor/check {:client-id "emp-1"} {}
                            (assoc (clean-proposal) :contract-id nil) st)]
      (is (:hard? v))
      (is (some #(= :no-contract (:rule %)) (:violations v))))))

(deftest hard-on-unknown-contract
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :contract-id "c-999") st)]
    (is (:hard? v))
    (is (some #(= :unknown-contract (:rule %)) (:violations v)))))

(deftest hard-on-contract-of-another-employer
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "emp-2" :name "Taro's Garage"})
    (let [v (governor/check {:client-id "emp-2"} {} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :contract-wrong-employer (:rule %)) (:violations v))))))

(deftest hard-on-wage-mismatch
  (testing "fair pay is arithmetic, not opinion — an inflated or shorted
            gross is held even at confidence 0.99"
    (let [st (fresh-store)
          v (governor/check {:client-id "emp-1"} {}
                            (assoc (clean-proposal) :gross 27999 :net 24999
                                   :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :wage-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-net-mismatch
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          (assoc (clean-proposal) :net 26000) st)]
    (is (:hard? v))
    (is (some #(= :net-mismatch (:rule %)) (:violations v)))))

(deftest monthly-contract-ignores-timesheet-hours
  (testing "kotoba.labor semantics pass through: monthly rate is the gross"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "emp-3" :name "Studio K"})
      (store/register-contract! st (labor/contract "c-3" "worker-3" "emp-3" "editor" :monthly 300000))
      (let [v (governor/check {:client-id "emp-3"} {}
                              {:op :draft-payroll-run :effect :propose :contract-id "c-3"
                               :period "2026-07" :gross 300000 :deductions 0 :net 300000
                               :confidence 0.9 :stake :low} st)]
        (is (:ok? v))))))

(deftest escalates-wage-disbursement
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          {:op :disburse-wages :effect :propose
                           :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check {:client-id "emp-1"} {}
                          {:op :reconcile-timesheets :effect :propose
                           :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

;; ---------------------------------------------------------------------------
;; 源泉徴収 — 所得税法 第百八十三条第一項, via kotoba.taxlaw
;; ---------------------------------------------------------------------------

(defn- jp-store
  "Same fixture, but the employer DECLARES where it pays wages and the
  contract carries the two facts the article turns on. Both are registered by
  the operator; neither is proposed by the advisor.

  `overrides` patches the registered contract, so a test can declare a
  non-resident worker or a payment made abroad."
  ([] (jp-store {} {}))
  ([client-overrides contract-overrides]
   (let [st (store/mem-store)]
     (store/register-client! st (merge {:client-id "emp-jp" :name "Hanako's Bakery"
                                        :jurisdiction [:jp]}
                                       client-overrides))
     (store/register-contract!
      st (merge (labor/contract "c-jp" "worker-1" "emp-jp" "baker" :hourly 2000)
                {:employment/recipient-residency :resident
                 :employment/paid-in :domestic}
                contract-overrides))
     (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-01" 8))
     (store/register-timesheet! st (labor/timesheet "worker-1" "2026-07-02" 6))
     st)))

(defn- jp-proposal [& {:as extra}]
  (merge {:op :draft-payroll-run :effect :propose :contract-id "c-jp"
          :period "2026-07" :gross 28000 :deductions 3000 :net 25000
          :confidence 0.9 :stake :low}
         extra))

(deftest hard-when-a-jp-payroll-run-does-not-account-for-withheld-income-tax
  (testing "所得税法 第百八十三条第一項 — 「その支払の際、その給与等について
            所得税を徴収し」。計上の無い run は零税額の run ではなく未回答"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))
      (testing "the hold names the article it rests on"
        (is (some #(clojure.string/includes? (:detail %) "所得税法 第百八十三条第一項")
                  (:violations v)))))))

(deftest no-confidence-buys-past-the-withholding-hold
  (testing "a HARD rule is never escalatable — an approver cannot be invited
            to wave through a payroll run with no withholding accounted for"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :confidence 0.99) st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (not (:ok? v))))))

(deftest ok-when-the-run-accounts-for-withheld-income-tax
  (let [st (jp-store)
        v (governor/check {:client-id "emp-jp"} {}
                          (jp-proposal :income-tax-withheld 8420) st)]
    (is (:ok? v))
    (is (not (:hard? v)))))

(deftest zero-withheld-is-an-answer-and-a-malformed-amount-is-not
  (testing "zero is accepted: the article says collect THE tax on that 給与等,
            and neither taxlaw nor this governor read 別表第二"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 0) (jp-store))]
      (is (:ok? v))))
  (testing "a negative or non-numeric amount is not an accounting"
    (doseq [bad [-1 "8420"]]
      (let [v (governor/check {:client-id "emp-jp"} {}
                              (jp-proposal :income-tax-withheld bad) (jp-store))]
        (is (:hard? v) (str "should hold " (pr-str bad)))
        (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))))))

(deftest hard-on-a-jurisdiction-nobody-catalogued
  (testing "declaring an unknown jurisdiction must not be the cheap way past
            the withholding rule — an unchecked jurisdiction is a hold"
    (let [st (jp-store {:jurisdiction [:atlantis]} {})
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :unchecked-jurisdiction (:rule %)) (:violations v)))
      (is (not (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))
          "taxlaw could not check it, so this governor must not claim it did"))))

;; ---------------------------------------------------------------------------
;; Where the governor deliberately does NOT hold, the verdict says so
;; ---------------------------------------------------------------------------

(deftest an-employer-with-no-declared-jurisdiction-is-not-held-but-is-reported
  (testing "nobody asserted where these wages are paid, so no withholding law
            was consulted — and that must not look like a clean check"
    (let [st (fresh-store)                       ; emp-1 declares no jurisdiction
          v (governor/check {:client-id "emp-1"} {} (clean-proposal) st)]
      (is (:ok? v) "not held: nothing was asserted")
      (is (nil? (get-in v [:tax :jurisdiction])))
      (is (= :not-declared (get-in v [:tax :withholding :taxlaw/coverage]))
          "`nobody looked` and `we looked and it was fine` must differ")
      (is (not (clojure.string/blank? (get-in v [:tax :withholding :taxlaw/why])))))))

(deftest a-payment-outside-the-read-article-is-not-held-and-says-why
  (testing "所得税法 第百八十三条第一項 binds a payer 「居住者に対し国内において」。
            A declared non-resident is outside it — and outside is NOT a
            finding that no obligation exists, because the provisions that
            govern those payments were never read"
    (doseq [[label overrides]
            [[:non-resident {:employment/recipient-residency :non-resident}]
             [:overseas {:employment/paid-in :overseas}]]]
      (testing (str label)
        (let [st (jp-store {} overrides)
              v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
          (is (:ok? v) "not held")
          (is (= :out-of-scope (get-in v [:tax :withholding :taxlaw/coverage])))
          (is (= "所得税法 第百八十三条第一項"
                 (get-in v [:tax :withholding :taxlaw/read-provision]))
              "the verdict names the article that WAS read, so a reader can
               see the limit of the check"))))))

(deftest silence-about-residency-does-not-exempt-a-run
  (testing "a registered contract that says nothing about residency or place
            stays IN scope — absence of a declaration is the unchecked case,
            and the unchecked case never buys the article's exclusion"
    (let [st (jp-store {} {:employment/recipient-residency nil
                           :employment/paid-in nil})
          v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v))))))

(deftest the-amount-is-never-certified
  (testing "an accounted-for run passes, but the verdict does not claim the
            AMOUNT was checked — 別表第二 / 別表第五 were not read"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 1) (jp-store))]
      (is (:ok? v))
      (is (false? (get-in v [:tax :withholding :taxlaw/amount-checked?]))
          "1 yen of withholding on 28000 of wages passes this gate; saying
           the amount was checked would be a lie the next reader acts on"))))

(deftest year-end-adjustment-is-reported-as-not-evaluated
  (testing "所得税法 第百九十条 is read and catalogued upstream and nothing
            here calls it. A rule that is silently never called looks exactly
            like a rule that was called and passed"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 8420) (jp-store))]
      (is (:ok? v))
      (is (= :not-evaluated (get-in v [:tax :year-end-adjustment :taxlaw/coverage])))
      (is (not (clojure.string/blank?
                (get-in v [:tax :year-end-adjustment :taxlaw/why])))))))

(deftest the-proposal-cannot-choose-its-own-jurisdiction
  (testing "the jurisdiction is the EMPLOYER's. An advisor that could pick one
            could pick the one whose rules it satisfies — here [:atlantis],
            which taxlaw cannot check and which would therefore silence the
            withholding rule"
    (let [st (jp-store)
          v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :jurisdiction [:atlantis]) st)]
      (is (:hard? v))
      (is (some #(= :income-tax-not-withheld (:rule %)) (:violations v))
          "the proposal's jurisdiction must be ignored entirely")
      (is (= [:jp] (get-in v [:tax :jurisdiction]))))))

(deftest non-draft-ops-carry-no-tax-report-at-all
  (testing "a disbursement or reconciliation asserts nothing about a payment
            of 給与等, so there is nothing for taxlaw to answer about"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            {:op :reconcile-timesheets :effect :propose
                             :confidence 0.9} (jp-store))]
      (is (nil? (:tax v))))))

;; ---------------------------------------------------------------------------
;; Two more jurisdictions in taxlaw, and the sentence that became false
;;
;; `kotoba-lang/taxlaw` gained `[:eu]` and `[:us]`, and made coverage per
;; FACET rather than per jurisdiction. Neither instrument's withholding rule
;; was read — the VAT Directive is not a payroll instrument, and IRC §3402 was
;; not opened — so both are `:out-of-scope` and both are still held.
;;
;; What changed is the explanation. "kotoba.taxlaw に無い" is now false for
;; the United States, and for payroll that is the expensive direction to be
;; wrong in: the US **does** oblige an employer to withhold. An operator told
;; the jurisdiction is unknown may conclude no obligation exists.
;; ---------------------------------------------------------------------------

(deftest a-us-payroll-run-is-held-and-told-why-it-could-not-be-checked
  (let [st (jp-store {:jurisdiction [:us]} {})
        v (governor/check {:client-id "emp-jp"} {} (jp-proposal) st)
        hit (first (filter #(= :unchecked-jurisdiction (:rule %)) (:violations v)))]
    (is (:hard? v))
    (is (some? hit))
    (is (= :jurisdiction/wage-withholding (:taxlaw/out-of-scope hit)))
    (testing "the reason names the provision nobody read, not a missing country"
      (is (str/includes? (:detail hit) "3402"))
      (is (not (str/includes? (:detail hit) "kotoba.taxlaw に無い"))))
    (testing "and says out loud that unread is not absent — the whole risk here"
      (is (str/includes? (:detail hit) "義務が無いという意味ではない")))
    (testing "rule 6 still does not fire; taxlaw could not check it"
      (is (not (some #(= :income-tax-not-withheld (:rule %)) (:violations v)))))))

(deftest an-eu-payroll-run-is-held-too-and-for-its-own-reason
  (testing "the VAT Directive is not a payroll instrument. Being catalogued
            for invoicing says nothing about withholding"
    (let [st (jp-store {:jurisdiction [:eu]} {})
          hit (first (filter #(= :unchecked-jurisdiction (:rule %))
                             (:violations (governor/check {:client-id "emp-jp"} {}
                                                          (jp-proposal) st))))]
      (is (some? hit))
      (is (= :jurisdiction/wage-withholding (:taxlaw/out-of-scope hit)))
      (is (str/includes? (:detail hit) "VAT")))))

(deftest a-jurisdiction-genuinely-absent-still-says-it-is-absent
  (testing "the old sentence is right where it is right, and a test keeps it
            from being replaced wholesale by the new one"
    (let [hit (first (filter #(= :unchecked-jurisdiction (:rule %))
                             (:violations (governor/check
                                           {:client-id "emp-jp"} {} (jp-proposal)
                                           (jp-store {:jurisdiction [:atlantis]} {})))))]
      (is (nil? (:taxlaw/out-of-scope hit)))
      (is (str/includes? (:detail hit) "kotoba.taxlaw に無い")))))

(deftest a-clean-jp-run-is-untouched-by-any-of-this
  (testing "the bump must not move the one jurisdiction that was working"
    (is (:ok? (governor/check {:client-id "emp-jp"} {}
                              (jp-proposal :income-tax-withheld 8420) (jp-store))))))

;; ---------------------------------------------------------------------------
;; 年末調整 — 所得税法 第百九十条, as a governed op
;;
;; taxlaw read and catalogued the article and nothing in this workspace called
;; it. `:assess-year-end-adjustment` calls it. The point of these tests is
;; that the new op HOLDS on everything unread and widens nothing: the draft
;; path's verdicts are untouched, and four of the assessment's nine answers
;; are HARD violations rather than a quiet commit.
;; ---------------------------------------------------------------------------

(defn- nen-store
  "A JP employer, a contract that may or may not record the 申告書, and the
  runs this actor has already committed for the year."
  [& {:keys [jurisdiction declaration runs employer]
      :or {jurisdiction [:jp] employer "emp-jp"}}]
  (let [st (store/mem-store)]
    (store/register-client! st (cond-> {:client-id employer :name "Hanako's Bakery"}
                                 jurisdiction (assoc :jurisdiction jurisdiction)))
    (store/register-contract!
     st (cond-> (labor/contract "c-jp" "worker-1" employer "baker" :hourly 2000)
          (some? declaration)
          (assoc :employment/year-end-declaration-filed? declaration)))
    (doseq [r (or runs [])] (store/commit-record! st r))
    st))

(defn- committed-run [& {:keys [period gross withheld]
                         :or {period "2026-07" gross 280000 withheld 8420}}]
  {:client-id "emp-jp" :op :draft-payroll-run :contract-id "c-jp"
   :payload {:op :draft-payroll-run :period period :gross gross
             :income-tax-withheld withheld}})

(defn- nen-request [& {:as extra}]
  (merge {:client-id "emp-jp" :op :assess-year-end-adjustment
          :contract-id "c-jp" :year "2026"}
         extra))

(defn- nen-proposal [& {:as extra}]
  ;; what `payroll.advisor/mock-advisor` actually produces for this op: the
  ;; base proposal and nothing else.
  (merge {:op :assess-year-end-adjustment :effect :propose
          :confidence 0.95 :stake :low}
         extra))

(defn- nen-check [& {:keys [store request proposal]}]
  (governor/check (or request (nen-request)) {}
                  (or proposal (nen-proposal))
                  (or store (nen-store :declaration true))))

;; --- the answers that commit -----------------------------------------------

(deftest a-fully-observed-year-end-question-is-answered-and-committed
  (let [v (nen-check :request (nen-request :final-payment-of-year? true))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (= :owed (get-in v [:nenmatsu :nenmatsu/answer])))
    (testing "and the committed answer still refuses to produce a figure"
      (is (= :not-computable
             (get-in v [:nenmatsu :nenmatsu/amount :nenmatsu/over-or-under]))))))

(deftest not-yet-commits-and-is-not-a-finding-about-the-employee
  (testing "「その年最後に給与等の支払をする場合」 may simply not have happened.
            That is an instruction to come back, not a refusal, so it commits
            — and it must not print as the same thing as disqualification"
    (let [not-yet (nen-check :request (nen-request :final-payment-of-year? false))
          never (nen-check :store (nen-store :declaration false)
                           :request (nen-request :final-payment-of-year? true))]
      (is (:ok? not-yet))
      (is (:ok? never))
      (is (= :year-not-finished (get-in not-yet [:nenmatsu :nenmatsu/answer])))
      (is (= :declaration-not-filed (get-in never [:nenmatsu :nenmatsu/answer])))
      (is (not= (get-in not-yet [:nenmatsu :nenmatsu/why])
                (get-in never [:nenmatsu :nenmatsu/why]))))))

(deftest the-ceiling-is-checked-against-what-this-actor-recorded
  (testing "二千万円以下 — inclusive. And over is certain while under is not"
    (let [at (nen-check :store (nen-store :declaration true
                                          :runs [(committed-run :gross 20000000)])
                        :request (nen-request :final-payment-of-year? true))
          over (nen-check :store (nen-store :declaration true
                                            :runs [(committed-run :gross 20000001)])
                          :request (nen-request :final-payment-of-year? true))]
      (is (= :owed (get-in at [:nenmatsu :nenmatsu/answer])))
      (is (= :above-ceiling (get-in over [:nenmatsu :nenmatsu/answer])))
      (is (:ok? over) "outside the article is an answer, not a refusal")
      (is (false? (get-in over [:nenmatsu :nenmatsu/evidence :ceiling
                                :nenmatsu/establishes-inside?]))))))

;; --- the four answers that HOLD --------------------------------------------

(deftest an-unobserved-declaration-is-held-never-passed
  (testing "給与所得者の扶養控除等申告書 is a piece of paper. The same discipline
            as `:yuryo-chobo-declared?` in the sibling bookkeeping actor:
            undeclared is its own answer and it is not a pass"
    (let [v (nen-check :store (nen-store) ; no :employment/year-end-declaration-filed?
                       :request (nen-request :final-payment-of-year? true))]
      (is (:hard? v))
      (is (not (:ok? v)))
      (is (some #(= :year-end-declaration-not-observed (:rule %)) (:violations v))))))

(deftest an-undeclared-final-payment-is-held
  (testing "this actor has no clock. `we do not know whether the year is over`
            is not `the year is over`"
    (let [v (nen-check)]
      (is (:hard? v))
      (is (some #(= :final-payment-not-declared (:rule %)) (:violations v))))))

(deftest an-employer-with-no-jurisdiction-cannot-be-asked-a-question-of-law
  (testing "rule 8 holds where rule 5 passes, on purpose: a DRAFT asserts
            nothing, but an assessment ASKS whether an adjustment is owed and
            that question has no answer without a jurisdiction"
    (let [v (nen-check :store (nen-store :jurisdiction nil :declaration true)
                       :request (nen-request :final-payment-of-year? true))]
      (is (:hard? v))
      (is (some #(= :year-end-jurisdiction-not-declared (:rule %)) (:violations v))))))

(deftest a-jurisdiction-whose-year-end-facet-is-unread-is-held-with-its-reason
  (doseq [[j fragment] [[[:us] "annual return"] [[:eu] "Member State law"]]]
    (testing (pr-str j)
      (let [v (nen-check :store (nen-store :jurisdiction j :declaration true)
                         :request (nen-request :final-payment-of-year? true))
            hit (first (filter #(= :unchecked-year-end-jurisdiction (:rule %))
                               (:violations v)))]
        (is (:hard? v))
        (is (some? hit))
        (is (= :jurisdiction/year-end-adjustment (:taxlaw/out-of-scope hit))
            "the operator learns WHICH facet was missing")
        (is (str/includes? (:detail hit) fragment)
            "the catalog's own reason, not a generic `unknown country`")))))

(deftest an-uncatalogued-jurisdiction-is-held-without-inventing-a-reason
  (let [v (nen-check :store (nen-store :jurisdiction [:atlantis] :declaration true)
                     :request (nen-request :final-payment-of-year? true))
        hit (first (filter #(= :unchecked-year-end-jurisdiction (:rule %))
                           (:violations v)))]
    (is (:hard? v))
    (is (nil? (:taxlaw/out-of-scope hit)))
    (is (str/includes? (:detail hit) "kotoba.taxlaw に無い"))))

(deftest every-nenmatsu-refusal-has-a-hard-rule
  (testing "a refusal `payroll.nenmatsu` adds and this governor has not
            classified would fall through to a commit. The map is checked
            against the set rather than trusted"
    (is (= nenmatsu/refusals (set (keys governor/refusal-rules))))
    (is (not (contains? (set (vals governor/refusal-rules))
                        :unclassified-year-end-refusal)))))

(deftest no-confidence-buys-past-a-year-end-hold
  (let [v (nen-check :store (nen-store)
                     :request (nen-request :final-payment-of-year? true)
                     :proposal (nen-proposal :confidence 0.99))]
    (is (:hard? v))
    (is (not (:escalate? v)))
    (is (not (:ok? v)))))

;; --- structural: an assessment must name an employee and a year ------------

(deftest an-assessment-must-name-a-registered-contract-of-this-employer
  (testing "three different failures, each naming what was wrong"
    (let [none (nen-check :request (nen-request :contract-id nil
                                                :final-payment-of-year? true))
          unknown (nen-check :request (nen-request :contract-id "c-999"
                                                   :final-payment-of-year? true))
          foreign (nen-check :store (doto (nen-store :declaration true
                                                     :employer "emp-other")
                                      (store/register-client!
                                       {:client-id "emp-jp" :name "Other"
                                        :jurisdiction [:jp]}))
                             :request (nen-request :final-payment-of-year? true))]
      (is (some #(= :no-assessment-contract (:rule %)) (:violations none)))
      (is (some #(= :unknown-contract (:rule %)) (:violations unknown)))
      (is (some #(= :contract-wrong-employer (:rule %)) (:violations foreign)))
      (doseq [v [none unknown foreign]] (is (:hard? v))))))

(deftest an-assessment-must-name-a-year
  (doseq [bad [nil "" "   "]]
    (testing (pr-str bad)
      (let [v (nen-check :request (nen-request :year bad
                                               :final-payment-of-year? true))]
        (is (:hard? v))
        (is (some #(= :no-assessment-year (:rule %)) (:violations v)))))))

;; --- the advisor is not the thing deciding ---------------------------------

(deftest an-advisor-cannot-move-the-year-end-answer
  (testing "an advisor that could name the contract would be the thing
            deciding whose 年末調整 gets looked at, and one that could set the
            declaration could file a piece of paper nobody signed"
    (let [st (nen-store)                      ; the 申告書 is NOT registered
          honest (nen-check :store st :request (nen-request :final-payment-of-year? true))
          lying (nen-check :store st
                           :request (nen-request :final-payment-of-year? true)
                           :proposal (nen-proposal
                                      :contract-id "c-jp"
                                      :year "2026"
                                      :employment/year-end-declaration-filed? true
                                      :final-payment-of-year? true
                                      :year-end-adjustment-settled? true
                                      :jurisdiction [:atlantis]))]
      (is (= (:nenmatsu honest) (:nenmatsu lying))
          "the assessment is identical; nothing the advisor wrote was read")
      (is (:hard? lying))
      (is (some #(= :year-end-declaration-not-observed (:rule %))
                (:violations lying))))))

(deftest a-non-boolean-declaration-does-not-buy-a-pass-through-the-governor
  (testing "the edge refuses a string with a 400, and this is the half of the
            guarantee that does not depend on the edge"
    (doseq [bad ["true" :yes 1]]
      (testing (pr-str bad)
        (is (:hard? (nen-check :store (nen-store :declaration bad)
                               :request (nen-request :final-payment-of-year? true))))
        (is (:hard? (nen-check :request (nen-request :final-payment-of-year? bad))))))))

;; --- adding the op widened nothing ------------------------------------------

(deftest the-draft-path-still-reports-year-end-as-not-evaluated
  (testing "a payroll-run draft still asserts nothing about the year's final
            payment, so the article is still not evaluated there — and the
            verdict still says so rather than falling silent"
    (let [v (governor/check {:client-id "emp-jp"} {}
                            (jp-proposal :income-tax-withheld 8420) (jp-store))]
      (is (:ok? v))
      (is (= :not-evaluated (get-in v [:tax :year-end-adjustment :taxlaw/coverage])))
      (is (nil? (:nenmatsu v)) "and carries no assessment"))))

(deftest an-assessment-verdict-carries-no-withholding-report
  (testing "an assessment asserts no payment of 給与等, so there is nothing for
            第百八十三条第一項 to answer about"
    (let [v (nen-check :request (nen-request :final-payment-of-year? true))]
      (is (nil? (:tax v))))))
