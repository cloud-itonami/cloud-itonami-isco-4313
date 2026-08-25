(ns payroll.fixtures-test
  "The fixtures carry no real payroll data, checked mechanically.

  This repository is public under AGPL, and a fixture is the easiest place
  for a real employee's wage, a real account number or a real MoneyForward
  employee id to enter a repository and never leave it — `git` keeps it after
  the file is fixed.

  Prose in a docstring does not survive somebody adding a fixture in a hurry.
  These assertions scan the fixture and test sources for the shapes real data
  takes, so the rule is enforced by the suite rather than remembered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.bank-transfer :as bank]
            [payroll.fixtures :as f]))

(def ^:private scanned-files
  "Every file that may hold a fixture. Listed by walking `test/`, not typed —
  a hand-kept list is one a new test file is absent from, and absent is
  exactly how this check would come to scan nothing."
  (->> (file-seq (io/file "test"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
       (mapv #(.getPath ^java.io.File %))))

(deftest the-scan-actually-has-something-to-scan
  (testing "the evidence floor. A scan over an empty file list finds no real
            data and would print exactly like a clean one"
    (is (>= (count scanned-files) 10)
        (str "only " (count scanned-files) " files found under test/"))
    (is (some #(str/includes? % "fixtures.clj") scanned-files))))

(deftest no-fixture-carries-a-plausible-bank-account
  (testing "a Japanese ordinary account number is seven digits and a branch
            code is three. The fixture uses all-zeroes for both, which is not
            an account anybody holds"
    (doseq [path scanned-files
            :let [src (slurp path)]]
      (doseq [m (re-seq #":bank/account-number\s+\"(\d+)\"" src)]
        (is (re-matches #"0+" (second m))
            (str path ": :bank/account-number " (pr-str (second m))
                 " is not all zeroes")))
      (doseq [m (re-seq #":bank/financial-institution-code\s+\"(\d+)\"" src)]
        (is (re-matches #"0+" (second m))
            (str path ": a real-looking 金融機関コード"))))))

(deftest the-fixture-payee-name-is-halfwidth-and-fictional
  (is (bank/halfwidth? (:bank/payee-name-kana (f/contract))))
  (is (str/includes? (:bank/payee-name-kana (f/contract)) "ｶｸｳ")
      "the fixture name says `fictional` in its own characters"))

(deftest the-fixture-employer-is-marked-fictional-in-its-name
  (is (str/includes? (:name (f/employer)) "架空")))

(deftest no-fixture-carries-a-did-that-is-not-marked-as-one
  (testing "a DID in a fixture is a public key. The fixture's says FIXTURE in
            the middle of it, so it cannot be mistaken for one somebody holds"
    (doseq [path scanned-files
            :let [src (slurp path)]
            m (re-seq #"\"(did:key:z[A-Za-z0-9]+)\"" src)]
      (let [did (second m)]
        (is (or (str/includes? did "FIXTURE")
                (str/includes? did "Test")
                (str/includes? did "Nope")
                (str/includes? did "SomeoneElse")
                ;; the endpoint suite predates these fixtures and uses short
                ;; obviously-synthetic keys; they are listed rather than
                ;; blanket-allowed so a new one has to be looked at.
                (< (count did) 24))
            (str path ": " did " does not identify itself as synthetic"))))))

(deftest the-fixture-amounts-are-round-and-obviously-synthetic
  (testing "not a proof of anything, but a real payroll figure is very
            unlikely to be a multiple of 10 across the board — and a fixture
            that stopped being round is one somebody should look at"
    (doseq [[label n] [["gross" f/gross]
                       ["standard-remuneration" f/standard-remuneration]
                       ["health-insurance" f/health-insurance]
                       ["care-insurance" f/care-insurance]
                       ["employment-insurance" f/employment-insurance]
                       ["income-tax" f/income-tax]]]
      (is (zero? (mod n 10)) (str label " = " n)))))

(deftest the-pension-fixture-is-the-exact-statutory-figure
  (testing "280000 × 183 / 2000 = 25620. It is not a round number because it
            is the one figure in the fixture a statute determines, and a
            different value would be rejected by the governor rather than
            being a fixture choice"
    (is (= 25620 f/employees-pension))
    (is (= (/ (* f/standard-remuneration 183) 2000) f/employees-pension))))

(deftest the-fixture-arithmetic-holds
  (testing "so a test that changes one figure and not the others fails here
            rather than somewhere confusing"
    (is (= f/deduction-total
           (+ f/income-tax f/health-insurance f/care-insurance
              f/employees-pension f/employment-insurance)))
    (is (= f/net (- f/gross f/deduction-total)))))

(deftest no-fixture-file-contains-a-japanese-personal-name-pattern
  (testing "the fixture worker is 従業員甲 — a placeholder, not a name.
            This scans for the `姓 名` shape a real name takes in a fixture"
    (is (= "従業員甲" f/worker))
    (doseq [path scanned-files
            :let [src (slurp path)]
            m (re-seq #":contract/worker\s+\"([^\"]+)\"" src)]
      (let [w (second m)]
        (is (not (re-matches #"[一-龥]{2,4}\s+[一-龥]{2,4}" w))
            (str path ": :contract/worker " (pr-str w)
                 " looks like a real name"))))))
