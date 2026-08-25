(ns payroll.artifact.text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [payroll.artifact.text :as text]
            [payroll.provenance :as prov]))

;; ---------------------------------------------------------------------------
;; The cell that is not a value
;; ---------------------------------------------------------------------------

(deftest an-unknown-figure-is-never-an-empty-cell
  (testing "a blank is read as zero by the person opening it and by every
            spreadsheet that opens it"
    (is (= text/unknown-cell (text/cell (prov/unknown "x" "why"))))
    (is (= text/unknown-cell (text/cell (prov/held "x" "why"))))
    (is (not= "" text/unknown-cell))
    (is (not= "0" text/unknown-cell))))

(deftest a-line-that-does-not-arise-is-distinct-from-one-nobody-answered
  (testing "opposite instructions: one means go and find out, the other means
            there is nothing to find out"
    (is (not= (text/cell (prov/not-applicable "x" "not a 被保険者"))
              (text/cell (prov/unknown "x" "nobody observed it"))))))

(deftest an-imported-figure-keeps-its-number-in-the-amount-column
  (testing "the marker belongs in the provenance column; gluing it onto the
            amount would break the column's type for the sake of a warning"
    (is (= 1234 (text/cell (prov/imported "x" 1234 "file"))))))

;; ---------------------------------------------------------------------------
;; CSV — RFC 4180
;; ---------------------------------------------------------------------------

(deftest a-bare-carriage-return-is-quoted
  (testing "\\r alone is a row terminator every standard reader recognises;
            unquoted it splits one row into two corrupted ones"
    (is (= "\"a\rb\"" (text/csv-cell "a\rb")))))

(deftest commas-quotes-and-newlines-are-quoted
  (is (= "\"a,b\"" (text/csv-cell "a,b")))
  (is (= "\"a\"\"b\"" (text/csv-cell "a\"b")))
  (is (= "\"a\nb\"" (text/csv-cell "a\nb"))))

(deftest an-ordinary-value-is-not-quoted
  (is (= "280000" (text/csv-cell 280000)))
  (is (= "健康保険料" (text/csv-cell "健康保険料"))))

(deftest nil-is-an-empty-cell-and-a-figure-is-not
  (testing "a genuinely absent column is the artifact's shape; an unknown
            figure is the payroll's state"
    (is (= "" (text/csv-cell nil)))
    (is (= text/unknown-cell (text/cell (prov/unknown "x" "w"))))))

(deftest csv-column-order-is-the-vectors-and-not-a-maps
  (let [cols [{:column/key :b :column/header "B"}
              {:column/key :a :column/header "A"}]
        out (text/csv {:columns cols :rows [{:a 1 :b 2}]})]
    (is (= "B,A" (first (str/split-lines out))))
    (is (= "2,1" (second (str/split-lines out))))))

(deftest the-same-input-produces-the-same-bytes
  (testing "the property an export has to have: a diff between this month's
            file and last month's must show what changed in the payroll"
    (let [cols (vec (for [k [:a :b :c :d :e :f :g :h]]
                      {:column/key k :column/header (name k)}))
          rows (vec (for [i (range 40)]
                      (zipmap [:a :b :c :d :e :f :g :h] (repeat i))))
          once (text/csv {:columns cols :rows rows})]
      (dotimes [_ 20]
        (is (= once (text/csv {:columns cols :rows rows})))))))

(deftest a-figure-in-a-row-is-routed-through-cell
  (let [out (text/csv {:columns [{:column/key :amt :column/header "金額"}]
                       :rows [{:amt (prov/held "x" "why")}
                              {:amt (prov/derived "y" 100 "s")}]})]
    (is (= [ "金額" text/unknown-cell "100"] (str/split-lines out)))))

;; ---------------------------------------------------------------------------
;; JSON — RFC 8259
;; ---------------------------------------------------------------------------

(deftest every-control-character-is-escaped
  (testing "RFC 8259 §7 requires all of U+0000-U+001F, not just the five with
            short forms. A raw tab produces a document no strict parser reads"
    (is (= "\"a\\tb\"" (text/json-string "a\tb")))
    (is (= "\"a\\u0001b\"" (text/json-string "ab")))
    (is (= "\"a\\u001fb\"" (text/json-string "ab")))
    (is (= "\"a\\rb\"" (text/json-string "a\rb")))
    (is (= "\"a\\bb\"" (text/json-string "a\bb")))))

(deftest json-object-key-order-is-the-callers
  (is (= "{\"z\":1,\"a\":2}"
         (text/json-document [[:z 1] [:a 2]]))))

(deftest a-figure-carries-its-provenance-into-the-json
  (let [s (text/json-document
           (text/figure->json-pairs (prov/declared "所得税" 5000 "form")))]
    (is (str/includes? s "\"amount\":5000"))
    (is (str/includes? s "\"provenance\":\"declared\""))
    (is (str/includes? s "\"certified_by_this_repository\":false"))))

(deftest an-unknown-figure-is-json-null-and-says-why
  (testing "null is safe HERE and is not safe in CSV: a parser hands null
            back as a distinct value, while an empty CSV cell is
            indistinguishable from a zero"
    (let [s (text/json-document
             (text/figure->json-pairs (prov/unknown "健康保険料" "未観測")))]
      (is (str/includes? s "\"amount\":null"))
      (is (str/includes? s "\"provenance\":\"unknown\"")))))

(deftest a-nested-object-is-an-object-and-not-a-quoted-blob
  (testing "measured 2026-08-25: `json-object-of` returned a String, and a
            String is a JSON string — so every nested object was emitted as
            its own escaped source. The document parsed, every key was
            present, and every nested value was text"
    (let [s (text/json-document
             [[:outer (text/json-object-of [[:inner 1]])]])]
      (is (= "{\"outer\":{\"inner\":1}}" s))
      (is (not (str/includes? s "\\\""))))))

(deftest a-nested-figure-keeps-its-amount-as-a-number
  (let [s (text/json-document [[:gross (text/figure-json
                                        (prov/derived "総支給額" 280000 "s"))]])]
    (is (str/includes? s "\"amount\":280000"))
    (is (not (str/includes? s "\"amount\\\":280000")))))

(deftest json-refuses-a-value-it-cannot-represent
  (testing "##NaN and ##Inf have no JSON representation; an encoder that
            emitted them would produce a file no strict parser reads"
    (is (thrown? clojure.lang.ExceptionInfo (text/json-value ##NaN)))
    (is (thrown? clojure.lang.ExceptionInfo (text/json-value ##Inf)))
    (is (thrown? clojure.lang.ExceptionInfo (text/json-value ##-Inf)))))

(deftest the-finiteness-test-answers-through-a-function-parameter
  (testing "the idiomatic `(not= n n)` does NOT — `Util/equiv` short-circuits
            on reference identity, so it answers `equal` for a NaN reached
            through a parameter and `not equal` for the same NaN written
            inline. Measured 2026-08-25; this pins the working form"
    (is (false? (text/finite-number? ##NaN)))
    (is (false? (text/finite-number? ##Inf)))
    (is (false? (text/finite-number? ##-Inf)))
    (is (false? (text/finite-number? nil)))
    (is (false? (text/finite-number? "1")))
    (is (true? (text/finite-number? 0)))
    (is (true? (text/finite-number? 280000)))
    (is (true? (text/finite-number? -1)))
    (is (true? (text/finite-number? 1.5)))
    (testing "and the broken form really is broken, so the comment above is
              a measurement rather than a story"
      (is (false? ((fn [n] (not= n n)) ##NaN))))))

(deftest keywords-keep-their-namespace
  (is (= "\"scheme/health-insurance\"" (text/json-value :scheme/health-insurance)))
  (is (= "\"commit\"" (text/json-value :commit))))

;; ---------------------------------------------------------------------------
;; The evidence floor
;; ---------------------------------------------------------------------------

(deftest coverage-reports-zero-figures-as-zero
  (testing "an artifact that scanned nothing must not print as one that
            scanned everything and found it fine"
    (let [c (text/coverage [])]
      (is (zero? (:coverage/figures c)))
      (is (zero? (:coverage/certified c)))
      (is (empty? (:coverage/by-provenance c))))))

(deftest coverage-counts-certified-and-unverified-separately
  (let [c (text/coverage [(prov/derived "a" 1 "s")
                          (prov/declared "b" 2 "f")
                          (prov/held "c" "w")])]
    (is (= 3 (:coverage/figures c)))
    (is (= 1 (:coverage/certified c)))
    (is (= 2 (:coverage/unverified c)))
    (is (= {:derived 1 :declared 1 :held 1} (:coverage/by-provenance c)))))
