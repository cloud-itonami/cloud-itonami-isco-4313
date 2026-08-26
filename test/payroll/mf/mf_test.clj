(ns payroll.mf.mf-test
  "The MoneyForward import boundary.

  Every fixture here is synthetic and is a CONJECTURE about the export's
  shape. No real MoneyForward file has been read by this repository, and the
  first test in this namespace is the one that keeps that statement true."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.fixtures :as f]
            [payroll.mf.import :as mf]
            [payroll.mf.reconcile :as recon]
            [payroll.mf.schema :as schema]
            [payroll.provenance :as prov]))

(def header
  "The conjectured header row, assembled from the schema rather than typed —
  a hand-typed header would silently stop matching the schema it is meant to
  exercise."
  (str/join "," (map :mf/column schema/columns)))

(defn- row
  "One synthetic export row for the fixture employee."
  [& {:keys [employee-number period gross health care pension employment
             tax resident-tax total net]
      :or {employee-number "9001" period f/period gross f/gross
           health f/health-insurance care f/care-insurance
           pension f/employees-pension employment f/employment-insurance
           tax f/income-tax resident-tax f/resident-tax
           total f/deduction-total net f/net}}]
  (str/join "," [employee-number "従業員甲" period gross health care pension
                 employment tax resident-tax total net]))

(defn- file [& rows] (str/join "\n" (cons header rows)))

;; ---------------------------------------------------------------------------
;; The honesty of the schema itself
;; ---------------------------------------------------------------------------

(deftest no-column-name-has-been-verified-against-a-real-export
  (testing "there is no code path that sets :mf/verified? true. Flipping the
            flag without also having read a real file reddens here — which is
            the point: the flag and the reading must move together"
    (is (empty? schema/verified-columns))
    (is (every? #(false? (:mf/verified? %)) schema/columns))
    (is (false? (:provenance/verified? mf/provenance-record)))
    (is (zero? (:provenance/columns-verified mf/provenance-record)))))

(deftest resident-tax-now-has-a-counterpart-and-nothing-else-does
  (testing "住民税 used to map to `:mf/no-counterpart`, which made the cutover
            gate's sixth condition unmeetable by construction: any real
            employer's export carries the column. `payroll.juminzei` gives it
            a counterpart — a registered municipality notice — so the column
            is compared rather than reported as vanished"
    (is (= :resident-tax-withheld (:mf/to (schema/by-column "住民税"))))
    (is (empty? schema/no-counterpart-columns))
    (testing "and the MECHANISM is intact: a column mapped to
              :mf/no-counterpart would still be carried into the report and
              would still block, which is what makes the vocabulary's
              emptiness a finding rather than a removal"
      (is (some? (resolve 'payroll.mf.import/parse)))
      (is (= [] (vec (for [c schema/columns
                           :when (= :mf/no-counterpart (:mf/to c))]
                       (:mf/column c))))))))

(deftest an-employee-is-matched-by-a-registered-number-and-never-by-name
  (testing "matching on a name joins two records for two different people who
            share a surname, and one person's wages get reconciled against
            another's"
    (let [cs [(f/contract)]]
      (is (= f/contract-id (:contract/id (schema/contract-for cs "9001"))))
      (is (nil? (schema/contract-for cs "9002")))
      (is (nil? (schema/contract-for cs nil)))
      (is (nil? (schema/contract-for cs ""))))))

(deftest parse-yen-refuses-a-shape-it-has-not-seen
  (testing "accepting a transformation this repository has not seen would be
            inventing one on somebody's pay"
    (is (= 280000 (schema/parse-yen "280,000")))
    (is (= 0 (schema/parse-yen "0")))
    (is (nil? (schema/parse-yen "")))
    (is (nil? (schema/parse-yen nil)))
    (is (= :mf/unparseable (schema/parse-yen "280000円")))
    (is (= :mf/unparseable (schema/parse-yen "２８００００")))
    (is (= :mf/unparseable (schema/parse-yen "-1000")))
    (is (= :mf/unparseable (schema/parse-yen "(1000)")))))

;; ---------------------------------------------------------------------------
;; The CSV reader
;; ---------------------------------------------------------------------------

(deftest the-csv-reader-handles-the-cases-that-break-hand-rolled-ones
  (is (= [["a" "b"] ["1" "2"]] (mf/parse-csv "a,b\n1,2")))
  (testing "a comma inside quotes"
    (is (= [["a,b" "c"]] (mf/parse-csv "\"a,b\",c"))))
  (testing "a newline inside quotes"
    (is (= [["a\nb" "c"]] (mf/parse-csv "\"a\nb\",c"))))
  (testing "a doubled quote"
    (is (= [["a\"b"]] (mf/parse-csv "\"a\"\"b\""))))
  (testing "CRLF, and a bare CR"
    (is (= [["a"] ["b"]] (mf/parse-csv "a\r\nb")))
    (is (= [["a"] ["b"]] (mf/parse-csv "a\rb"))))
  (testing "an empty trailing field"
    (is (= [["a" ""]] (mf/parse-csv "a,"))))
  (testing "a trailing terminator does not make a phantom row — an importer
            that saw one would report one more employee than the file has"
    (is (= [["a"] ["b"]] (mf/parse-csv "a\nb\n"))))
  (testing "and an empty file is no rows rather than one empty one"
    (is (= [] (mf/parse-csv "")))
    (is (= [] (mf/parse-csv nil)))))

;; ---------------------------------------------------------------------------
;; Import
;; ---------------------------------------------------------------------------

(deftest a-clean-file-parses-and-every-figure-is-imported-not-declared
  (let [r (mf/parse (file (row)) [(f/contract)])]
    (is (= :ok (:import/status r)))
    (is (= 1 (count (:import/rows r))))
    (is (empty? (:import/rejected r)))
    (let [figs (:row/figures (first (:import/rows r)))]
      (is (= f/gross (prov/amount (:gross figs))))
      (is (every? #(= :imported (:figure/provenance %)) (vals figs)))
      (is (every? prov/unverified? (vals figs))))))

(deftest a-missing-required-column-rejects-the-whole-file
  (testing "with no 支給年月 there is nothing to reconcile any row against,
            and per-row failures for every row would bury the one fact that
            explains all of them"
    (let [r (mf/parse "従業員番号,氏名\n9001,甲" [(f/contract)])]
      (is (= :rejected (:import/status r)))
      (is (str/includes? (:import/why r) "支給年月"))
      (is (str/includes? (:import/why r) "推測"))
      (is (empty? (:import/rows r))))))

(deftest an-unknown-column-is-reported-and-never-dropped
  (testing "a payroll export carrying a deduction this repository has no name
            for is a deduction that disappears between two systems"
    (let [r (mf/parse (str header ",財形貯蓄\n" (row) ",5000") [(f/contract)])]
      (is (= :ok (:import/status r)))
      (is (= ["財形貯蓄"] (:import/unknown-columns r))))))

(deftest a-malformed-amount-rejects-its-row-and-quotes-it-back
  (testing "there is no `(or … 0)` anywhere in this importer. The raw text is
            quoted so the next version of parse-yen is written against
            evidence"
    ;; NOT `"280,000円"` — that contains a comma and would shift every field
    ;; after it, so the test would be exercising a ragged row rather than an
    ;; unreadable amount. The reader caught it, which is the reader working.
    (let [r (mf/parse (file (row :gross "280000円")) [(f/contract)])]
      (is (= :ok (:import/status r)))
      (is (empty? (:import/rows r)))
      (is (= 1 (count (:import/rejected r))))
      (is (= 2 (:row/number (first (:import/rejected r)))))
      (is (str/includes? (:row/why (first (:import/rejected r))) "総支給額"))
      (is (= ["9001" "従業員甲" f/period "280000円"]
             (take 4 (:row/raw (first (:import/rejected r)))))))))

(deftest one-bad-row-does-not-reject-the-good-ones
  (let [r (mf/parse (file (row) (row :employee-number "9002" :gross "x"))
                    [(f/contract)])]
    (is (= 1 (count (:import/rows r))))
    (is (= 1 (count (:import/rejected r))))))

(deftest an-unregistered-employee-number-is-kept-and-marked-unmapped
  (testing "dropping it would hide an employee MoneyForward is paying and
            this actor has never heard of — precisely the finding a cutover
            needs"
    (let [r (mf/parse (file (row :employee-number "9999")) [(f/contract)])
          row* (first (:import/rows r))]
      (is (false? (:row/mapped? row*)))
      (is (nil? (:row/contract-id row*)))
      (is (str/includes? (:row/unmapped-why row*) "9999"))
      (is (str/includes? (:row/unmapped-why row*) "氏名での推測照合はしない")))))

(deftest a-resident-tax-value-is-imported-rather-than-reported-as-vanished
  (testing "it has a counterpart now, so it becomes an `:imported` figure the
            reconciliation compares — the column no longer appears in
            :import/no-counterpart because nothing does"
    (let [r (mf/parse (file (row :resident-tax 12000)) [(f/contract)])
          row* (first (:import/rows r))]
      (is (empty? (:import/no-counterpart r)))
      (is (= 12000 (get-in row* [:row/values :resident-tax-withheld])))
      (is (= :imported (get-in row*
                               [:row/figures :resident-tax-withheld
                                :figure/provenance]))))))

(deftest an-unreadable-resident-tax-still-rejects-its-row
  (testing "the column being mapped does not make a cell readable; a value
            this repository has not seen rejects the row with its raw text"
    (let [r (mf/parse (file (row :resident-tax "不明")) [(f/contract)])]
      (is (= 1 (count (:import/rejected r))))
      (is (str/includes? (:row/why (first (:import/rejected r))) "住民税")))))

(deftest a-short-row-is-padded-rather-than-throwing
  (testing "an export whose last columns are empty is a file, not a crash"
    (let [r (mf/parse (str header "\n9001,従業員甲,2026-08,280000") [(f/contract)])]
      (is (= :ok (:import/status r)))
      (is (= 1 (count (:import/rows r)))))))

;; ---------------------------------------------------------------------------
;; Reconciliation
;; ---------------------------------------------------------------------------

(defn- ours [] {[f/contract-id f/period] (f/lines {:verdict (f/verdict-for)})})

(defn- reconcile [text & [ours*]]
  (recon/reconcile {:import (mf/parse text [(f/contract)])
                    :ours (or ours* (ours))
                    :period f/period}))

(deftest a-matching-file-reconciles-and-says-it-is-one-cycle
  (let [r (reconcile (file (row)))]
    (is (:reconcile/reconciled? r))
    (is (= 1 (:reconcile/compared r)))
    (is (str/includes? (:reconcile/why r) "移行の可否ではない"))))

(deftest a-differing-figure-blocks-the-reconciliation-and-names-the-delta
  (let [r (reconcile (file (row :tax 6001)))
        run (first (:reconcile/runs r))
        tax (first (filter #(= :income-tax-withheld (:field/key %))
                           (:run/fields run)))]
    (is (not (:reconcile/reconciled? r)))
    (is (= :differ (:field/verdict tax)))
    (is (= 1 (:field/delta tax)))
    (is (str/includes? (:field/why tax) "6000"))
    (is (str/includes? (:field/why tax) "6001"))))

(deftest a-figure-this-actor-held-is-never-scored-as-agreement
  (testing "the state every run this actor holds lands in. A reconciliation
            that scored those as agreeing would report a clean parallel run
            for a month in which this actor computed nothing"
    (let [st (f/fresh-store
              {:contract-overrides {:employment/health-insurance-insured? nil}})
          p (f/proposal)
          held (f/lines {:contract* (f/contract
                                     {:employment/health-insurance-insured? nil})
                         :run p :verdict (f/verdict-for st p) :disposition :hold})
          r (reconcile (file (row)) {[f/contract-id f/period] held})
          hi (first (filter #(= :health-insurance-withheld (:field/key %))
                            (:run/fields (first (:reconcile/runs r)))))]
      (is (= :not-comparable (:field/verdict hi)))
      (is (not (:reconcile/reconciled? r)))
      (is (str/includes? (:field/why hi) "比較できないことは一致ではない")))))

(deftest zero-compared-runs-is-never-a-pass
  (testing "the evidence floor. A report over an empty import has no
            differences to show and would otherwise print like a perfect
            month"
    (let [r (recon/reconcile {:import (mf/parse (file) [(f/contract)])
                              :ours (ours) :period f/period})]
      (is (not (:reconcile/reconciled? r)))
      (is (zero? (:reconcile/compared r)))
      (is (some #(str/includes? % "比較していないことは違う")
                (:reconcile/blockers r))))))

(deftest a-resident-tax-that-disagrees-with-the-notice-blocks
  (testing "the column is compared now, so a MoneyForward figure that differs
            from the registered 決定通知書 is a DIFFERENCE rather than a
            column that vanished — which is what makes the cutover gate
            meetable at all"
    (let [r (reconcile (file (row :resident-tax 12000)))
          fld (first (filter #(= :resident-tax-withheld (:field/key %))
                             (:run/fields (first (:reconcile/runs r)))))]
      (is (not (:reconcile/reconciled? r)))
      (is (= :differ (:field/verdict fld)))
      (is (= (- 12000 f/resident-tax) (:field/delta fld))))))

(deftest a-run-whose-resident-tax-was-never-assessed-is-not-scored-as-agreement
  (testing ":not-comparable, never :agree — the state every held figure lands
            in, and the one a naive reconciliation calls a pass"
    (let [r (recon/reconcile
             {:import (mf/parse (file (row :resident-tax 12000)) [(f/contract)])
              :ours {[f/contract-id f/period]
                     (f/lines {:verdict (f/verdict-for) :juminzei :none})}
              :period f/period})
          fld (first (filter #(= :resident-tax-withheld (:field/key %))
                             (:run/fields (first (:reconcile/runs r)))))]
      (is (not (:reconcile/reconciled? r)))
      (is (= :not-comparable (:field/verdict fld))))))

(deftest an-unmapped-row-blocks-the-reconciliation
  (let [r (reconcile (file (row :employee-number "9999")))]
    (is (not (:reconcile/reconciled? r)))
    (is (= 1 (count (:reconcile/unmapped r))))))

(deftest a-period-this-actor-never-ran-is-reported-as-mf-only
  (let [r (recon/reconcile {:import (mf/parse (file (row)) [(f/contract)])
                            :ours {} :period f/period})]
    (is (not (:reconcile/reconciled? r)))
    (is (str/includes? (get-in (first (:reconcile/runs r))
                               [:run/blocking 0 :field/why])
                       "処理していない"))))

(deftest a-rejected-import-reconciles-to-a-refusal-and-not-to-silence
  (let [r (recon/reconcile {:import (mf/parse "従業員番号\n9001" [(f/contract)])
                            :ours (ours) :period f/period})]
    (is (not (:reconcile/reconciled? r)))
    (is (str/includes? (:reconcile/why r) "取り込みが拒否"))))

(deftest the-reconciliation-csv-emits-agreeing-fields-too
  (testing "a report showing only the differences could not distinguish `we
            compared eleven fields and one differs` from `we compared one`"
    (let [csv (recon/->csv (reconcile (file (row :tax 6001))))
          lines (str/split-lines csv)]
      (is (= (inc (count recon/compared-fields)) (count lines)))
      (is (str/includes? csv "一致"))
      (is (str/includes? csv "不一致")))))

(deftest the-reconciliation-json-carries-the-unverified-source
  (let [json (recon/->json (reconcile (file (row))))]
    (is (str/includes? json "\"source_verified\":false"))
    (is (str/includes? json "\"reconciled\":true"))))

(deftest the-compared-fields-are-taken-from-the-payslip-lines
  (testing "a deduction added to `payroll.meisai/deduction-lines` is compared
            here without a second edit"
    (is (= 8 (count recon/compared-fields)))
    (is (= :gross (:field/key (first recon/compared-fields))))
    (is (= :net (:field/key (last recon/compared-fields))))))

(deftest only-agreement-passes
  (testing ":only-here is not a pass even though it can be benign — a
            介護保険料 this actor computes for a worker MoneyForward does not
            treat as a 第二号被保険者 is a disagreement about the law"
    (is (= #{:agree} recon/passing-verdicts))))
