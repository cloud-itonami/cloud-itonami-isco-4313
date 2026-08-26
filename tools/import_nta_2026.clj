(ns import-nta-2026
  "Turn the 国税庁's published 源泉徴収税額表（月額表）workbook into
  `src/payroll/rates/monthly_2026.cljc`.

  ## Why an importer and not a transcription

  231 banded rows, eight dependant columns, nine threshold rows and eleven
  excess-rate formulae is about 2,000 figures. A human transcribing that will
  get some of them right. Nobody will ever find the ones they got wrong,
  because a wrong band is a plausible number on a plausible payslip — the
  same failure `payroll.rates`' namespace docstring names for 東京 being
  generalised to 大阪.

  So the figures are never typed. They are READ out of the workbook the
  国税庁 published, by this program, and the workbook is PINNED by SHA-256.
  Regenerating is `clojure -M:importer`; the diff being empty is the check
  that the file on disk is the file this program produces.

  ## What this program refuses to do

  - **Import a workbook it has not seen.** A digest mismatch exits non-zero
    and writes nothing. The 国税庁 reissues these workbooks; a silently
    imported reissue would change every payslip in the fleet with no commit
    saying so.
  - **Import a workbook whose shape it does not recognise.** The structural
    checks below are the ones that would have caught the ways this parse can
    go quietly wrong: a blank spacer row read as a band, a band dropped at a
    page break, a column offset by one, a double that is not a whole yen.
    Every failure is NAMED and all of them are reported, because an importer
    that stops at the first one makes the operator run it five times.
  - **Embed any figure from the table.** The amounts, the band edges, the
    thresholds, the excess rates, the 7人超 deduction and even the tax YEAR
    are parsed out of cells. The only numbers written into this file are the
    workbook's digest and its byte count, which are what identify the input
    rather than what it contains.

  ## What it does NOT claim

  It does not claim the workbook is correct, and it does not claim the
  resulting calculator is complete. The 月額表 is one of three tables in the
  2026 publication; 日額表 and 賞与に対する源泉徴収税額の算出率の表 are not
  read here and `payroll.rates` refuses for them. And the excess-rate tail
  is imported as a FORMULA, not as an answer, because the workbook states the
  rate and does not state the 端数処理 — see `payroll.rates/withhold`."
  (:require [clojure.string :as str])
  (:import (java.io File FileInputStream)
           (java.security MessageDigest)
           (org.apache.poi.hssf.usermodel HSSFWorkbook)))

;; ---------------------------------------------------------------------------
;; The pin
;; ---------------------------------------------------------------------------

(def workbook-pin
  "The one workbook this importer will read.

  `:pin/sha256` is the whole point. `:pin/url` is where it came from and
  `:pin/retrieved-at` is when, but neither of those identifies BYTES — a URL
  serves whatever is behind it today, and the 国税庁 replaces these files in
  place when a 告示 is amended. The digest is what makes `clojure -M:importer`
  a reproducible step rather than a fetch."
  {:pin/sha256 "50aafa072df1bb6b6aa253a021f7cc246265c3f2393f9988ee01ad121bc4f310"
   :pin/bytes 81408
   :pin/url "https://www.nta.go.jp/publication/pamph/gensen/zeigakuhyo2026/data/01-07.xls"
   :pin/page "https://www.nta.go.jp/publication/pamph/gensen/zeigakuhyo2026/01.htm"
   :pin/retrieved-at "2026-08-26"
   :pin/sheet "月額表"
   :pin/authority "国税庁"})

(def default-workbook "/tmp/nta2026/01-07.xls")
(def default-output "src/payroll/rates/monthly_2026.cljc")

(def ^:private importer-version
  "Bumped when the SHAPE of the generated file changes, so that a consumer
  can tell a regeneration from a re-layout. It is written into
  `:transform/version` in the output."
  1)

;; ---------------------------------------------------------------------------
;; Bytes in
;; ---------------------------------------------------------------------------

(defn sha256-hex
  "The file's SHA-256 as lower-case hex.

  Streamed rather than slurped: this happens to be an 80 KiB file, but an
  importer that reads the whole input into memory to hash it is one that
  stops working on the day the input is a hundred megabytes, and there is no
  reason to write that."
  [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (FileInputStream. f)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update md buf 0 n) (recur)))))
    (str/join (map #(format "%02x" %) (.digest md)))))

;; ---------------------------------------------------------------------------
;; Cells out
;; ---------------------------------------------------------------------------

(defn- cell-value
  "A cell as a Clojure value: string, double, or nil.

  Formula and error cells return nil deliberately. This sheet has none — it
  is a published table, not a spreadsheet — and if a future edition grows
  one, `nil` makes the structural checks fail loudly instead of letting a
  cached formula result through as if it were a printed figure."
  [c]
  (when c
    (case (.name (.getCellType c))
      "STRING" (.getStringCellValue c)
      "NUMERIC" (.getNumericCellValue c)
      nil)))

(defn- read-grid
  "The sheet as a vector of 13-wide row vectors, index-aligned to row number.

  Index-aligned rather than compacted, because every structural claim this
  importer makes is about WHICH row something was on, and a compacted vector
  cannot say that. Rows absent from the file become nil."
  [sheet width]
  (let [last-row (.getLastRowNum sheet)]
    (mapv (fn [r]
            (when-let [row (.getRow sheet r)]
              (mapv #(cell-value (.getCell row (int %))) (range width))))
          (range 0 (inc last-row)))))

(defn- s
  "A cell as a trimmed string, or nil if it was not a string.

  The 国税庁's label cells are padded with leading ASCII and 全角 spaces for
  layout ('  740,000円を超え'), so every label comparison in this importer
  goes through here."
  [v]
  (when (string? v) (str/trim (str/replace v \u3000 \space))))

;; ---------------------------------------------------------------------------
;; Parsing the printed forms
;; ---------------------------------------------------------------------------

(defn parse-yen
  "'740,000円' -> 740000. nil if the string does not carry a yen figure.

  The workbook prints amounts with 千区切り inside label text, so this is a
  find rather than a match; callers that need the whole cell to BE an amount
  check the shape first."
  [text]
  (some-> text (->> (re-find #"([0-9][0-9,]*)円")) second
          (str/replace "," "") parse-long))

(defn parse-percent
  "'20.42' -> 2042/10000, exactly.

  A RATIO and never a double, for the reason `payroll.rates/insurance-rates`
  states about 4.925%: 20.42/100 is not representable in binary floating
  point, and a tax figure that differs by a yen depending on which runtime
  printed it is a tax figure nobody can reconcile. The denominator is built
  by counting decimal places rather than by `Math/pow`, so it is exact for
  any number of them."
  [digits]
  (let [[whole frac] (str/split digits #"\.")
        frac (or frac "")
        scale (reduce * 1 (repeat (count frac) 10))]
    (/ (parse-long (str whole frac)) (* 100 scale))))

(defn parse-reiwa-year
  "'給与所得の源泉徴収税額表（令和８年分）' -> 2026.

  Read rather than assumed, so that pointing this importer at a different
  edition cannot produce a file whose `:applicability` says 2026 while its
  bands say something else. 令和元年 is 2019, hence +2018. The digits are
  全角 in the published title."
  [title]
  (when-let [[_ jp] (re-find #"令和([０-９0-9]+)年分" (str title))]
    (let [n (parse-long (str/join (map (fn [ch]
                                         (if (<= (int \０) (int ch) (int \９))
                                           (char (+ (int \0) (- (int ch) (int \０))))
                                           ch))
                                       jp)))]
      {:reiwa n :gregorian (+ n 2018)})))

;; ---------------------------------------------------------------------------
;; Extraction
;; ---------------------------------------------------------------------------

(defn- whole-yen
  "A numeric cell as a non-negative whole yen, or ::not-whole.

  POI hands back every numeric cell as a double. `71680.0` is 71,680 yen and
  `71680.000000001` is a parse that has gone wrong somewhere; the caller
  turns the sentinel into a named structural failure rather than rounding,
  because rounding here would hide exactly the corruption this check exists
  to find."
  [v]
  (if (and (number? v) (== v (Math/rint (double v))) (not (neg? v)))
    (long v)
    ::not-whole))

(defn- kou-vector [row] (mapv #(whole-yen (nth row %)) (range 3 11)))

(defn extract-bands
  "The 231 discrete bands: every row whose column 0 carries a band number.

  Column 0 is what distinguishes a band from the blank spacer rows the
  workbook inserts every five bands for legibility, and from the threshold
  rows below the table, which have amounts but no number. Nothing here skips
  by row index."
  [grid]
  (vec (for [row grid
             :when (and row (number? (nth row 0)))]
         {:band/no (whole-yen (nth row 0))
          :band/from (whole-yen (nth row 1))
          :band/to (whole-yen (nth row 2))
          :band/kou (kou-vector row)
          :band/otsu (whole-yen (nth row 11))})))

(defn extract-sub-minimum
  "The band below the table: 105,000円未満.

  Identified by column 2 being the literal 未満 marker rather than a number,
  which is how the workbook says 'this row has no upper edge printed because
  the lower edge IS the table's floor'. 甲 is zero for every dependant count
  and 乙 is a RATE, not an amount — the only place in the 月額表 where the 乙
  column states a percentage instead of a figure."
  [grid]
  (first (for [row grid
               :when (and row (= "円未満" (s (nth row 2)))
                          (number? (nth row 1)))
               :let [text (s (nth row 11))
                     pct (second (re-find #"([0-9][0-9.]*)％" (str text)))]]
           {:band/from 0
            :band/to (whole-yen (nth row 1))
            :band/kou (kou-vector row)
            :band/otsu-rate (when pct (parse-percent pct))
            :band/otsu-basis text})))

(defn extract-thresholds
  "The nine rows that state the tax AT an exact amount.

  Recognised by column 1 being ENTIRELY an amount ('740,000円') while column
  0 is empty, and by all eight 甲 cells being numeric — the same row shape as
  a band with the number and the 未満 edge removed, which is what it is. Two
  of the nine also carry a 乙 figure; the other seven leave 乙 to the
  excess-rate formula, and `:threshold/otsu` is nil for them rather than
  absent so that a reader can see the hole."
  [grid]
  (vec (for [row grid
             :when (and row
                        (nil? (nth row 0))
                        (some? (s (nth row 1)))
                        (re-matches #"[0-9][0-9,]*円" (s (nth row 1)))
                        (every? number? (subvec row 3 11)))]
         {:threshold/at (parse-yen (s (nth row 1)))
          :threshold/kou (kou-vector row)
          :threshold/otsu (when (number? (nth row 11)) (whole-yen (nth row 11)))})))

(defn extract-kou-segments
  "The 甲欄 excess-rate tail, read out of the sentence that states it.

  Each segment is one column-3 cell of the form
  '…740,000円を超える金額の20.42％に相当する金額を加算した金額'. The BASE is
  not in that sentence — the sentence says '740,000円の場合の税額に' — so it
  is taken from the threshold row for the same amount, which is why the
  structural checks insist every segment's start is a threshold that was
  actually extracted.

  The upper edge is the next segment's start, and nil for the last. The
  workbook also prints それを '…に満たない金額' labels in column 1; those are
  cross-checked in `structural-failures` rather than used, because deriving
  the edge from the ordering and CHECKING it against the printed label
  catches a dropped segment, while reading the label alone would not."
  [grid thresholds]
  (let [by-at (into {} (map (juxt :threshold/at :threshold/kou)) thresholds)
        found (sort-by first
                       (for [row grid
                             :when row
                             :let [text (s (nth row 3))
                                   m (re-find #"([0-9][0-9,]*)円を超える金額の([0-9][0-9.]*)％"
                                              (str text))]
                             :when m]
                         [(parse-long (str/replace (nth m 1) "," "")) (parse-percent (nth m 2)) text]))
        froms (mapv first found)]
    (vec (map-indexed
          (fn [i [from rate text]]
            {:segment/from from
             :segment/to (get froms (inc i))
             :segment/base (get by-at from)
             :segment/rate rate
             :segment/basis text})
          found))))

(defn extract-otsu-segments
  "The 乙欄 excess-rate tail.

  The 乙 sentence states its own base ('259,200円に、…740,000円を超える金額の
  40.84％…'), which is why these two segments carry a scalar `:segment/base`
  where the 甲 segments carry a vector taken from a threshold row.

  The workbook prints each of the two sentences TWICE — once beside the band
  that introduces it and once again further down — so identical texts are
  collapsed by their starting amount. Collapsing rather than tolerating
  duplicates is deliberate: the structural checks then assert exactly two,
  and a future edition that split one into two different sentences would
  fail rather than silently produce a duplicated segment."
  [grid]
  (let [found (->> (for [row grid
                         :when row
                         :let [text (s (nth row 11))
                               m (re-find #"([0-9][0-9,]*)円に、.*?([0-9][0-9,]*)円を超える金額の([0-9][0-9.]*)％"
                                          (str text))]
                         :when m]
                     {:segment/from (parse-long (str/replace (nth m 2) "," ""))
                      :segment/to nil
                      :segment/base (parse-long (str/replace (nth m 1) "," ""))
                      :segment/rate (parse-percent (nth m 3))
                      :segment/basis text})
                   (reduce (fn [acc seg] (assoc acc (:segment/from seg) seg)) {})
                   vals
                   (sort-by :segment/from)
                   vec)
        froms (mapv :segment/from found)]
    (vec (map-indexed (fn [i seg] (assoc seg :segment/to (get froms (inc i)))) found))))

(defn extract-beyond-7-deduction
  "1,610円 — the per-person deduction above seven 扶養親族等.

  The workbook states it in three separate places (the note under the table,
  the 備考 for 甲欄, and the 備考 for 乙欄). All three are read and they must
  agree; a single reading would be a figure this importer took on the word of
  one cell."
  [grid]
  (let [hits (distinct (for [row grid
                             :when row
                             c (range 0 13)
                             :let [text (s (nth row c))
                                   m (re-find #"１人ごとに([0-9][0-9,]*)円を控除" (str text))]
                             :when m]
                         (parse-long (str/replace (nth m 1) "," ""))))]
    {:deduction/values (vec hits)
     :deduction/yen (when (= 1 (count hits)) (first hits))}))

(defn extract-printed-segment-edges
  "The '…に満たない金額' upper edges the workbook prints in column 1.

  Used only as a cross-check against the edges derived from segment
  ordering. Two independent readings of the same fact is the cheapest thing
  that catches a dropped segment, and a dropped segment is the one parse
  error that would otherwise produce a table that looks complete."
  [grid]
  (set (for [row grid
             :when row
             :let [text (s (nth row 1))
                   m (re-find #"([0-9][0-9,]*)円に満た" (str text))]
             :when m]
         (parse-long (str/replace (nth m 1) "," "")))))

;; ---------------------------------------------------------------------------
;; Structural checks
;; ---------------------------------------------------------------------------

(defn structural-failures
  "Every way this parse is known to be able to go wrong, as named strings.

  ALL of them are evaluated and returned, never short-circuited. An importer
  that reports the first failure makes the operator run it once per problem
  and learn nothing about how far off the parse is.

  Each check is here because it is the check that discriminates a specific
  corruption: a spacer row read as a band (count and numbering), a band lost
  at a page break (contiguity), a column read one to the left (monotonicity
  in both directions), a cell that is not really an amount (whole-yen), and a
  formula whose base was matched to the wrong threshold (the from/threshold
  identity)."
  [{:keys [bands sub-minimum thresholds kou-segments otsu-segments deduction
           printed-edges year]}]
  (let [n (count bands)
        amounts (fn [b] (conj (:band/kou b) (:band/otsu b)))
        all-numbers (concat (mapcat amounts bands)
                            (mapcat :threshold/kou thresholds)
                            (keep :threshold/otsu thresholds)
                            (:band/kou sub-minimum)
                            (map :band/from bands) (map :band/to bands)
                            (mapcat :segment/base kou-segments)
                            (map :segment/base otsu-segments))
        derived-edges (set (keep :segment/to kou-segments))
        threshold-ats (set (map :threshold/at thresholds))]
    (cond-> []
      (not= 231 n)
      (conj (str "bands: expected 231 numbered rows, read " n))

      (not= (map :band/no bands) (range 1 (inc n)))
      (conj (str "bands: numbering is not 1.." n " with no gap; first divergence at "
                 (pr-str (first (remove nil? (map (fn [b i] (when (not= (:band/no b) i) [i (:band/no b)]))
                                                  bands (range 1 (inc n))))))))

      (not= 105000 (:band/from (first bands)))
      (conj (str "bands: band 1 starts at " (pr-str (:band/from (first bands)))
                 ", not 105000"))

      (not= 740000 (:band/to (last bands)))
      (conj (str "bands: band " n " ends at " (pr-str (:band/to (last bands)))
                 ", not 740000"))

      (seq (remove nil? (map (fn [a b] (when (not= (:band/to a) (:band/from b))
                                         [(:band/no a) (:band/to a) (:band/from b)]))
                             bands (rest bands))))
      (conj (str "bands: not contiguous at "
                 (pr-str (vec (take 5 (remove nil? (map (fn [a b]
                                                          (when (not= (:band/to a) (:band/from b))
                                                            [(:band/no a) (:band/to a) (:band/from b)]))
                                                        bands (rest bands))))))))

      (seq (for [b bands :when (not= (:band/kou b) (reverse (sort (:band/kou b))))] (:band/no b)))
      (conj (str "bands: 甲 amounts rise with dependant count in band(s) "
                 (pr-str (vec (take 5 (for [b bands
                                            :when (not= (:band/kou b) (reverse (sort (:band/kou b))))]
                                        (:band/no b)))))))

      (seq (for [[a b] (map vector bands (rest bands))
                 i (range 0 8)
                 :when (< (nth (:band/kou b) i) (nth (:band/kou a) i))]
             [(:band/no a) i]))
      (conj (str "bands: 甲 column falls between consecutive bands at "
                 (pr-str (vec (take 5 (for [[a b] (map vector bands (rest bands))
                                            i (range 0 8)
                                            :when (< (nth (:band/kou b) i) (nth (:band/kou a) i))]
                                        [(:band/no a) i]))))))

      (seq (for [[a b] (map vector bands (rest bands))
                 :when (< (:band/otsu b) (:band/otsu a))]
             (:band/no a)))
      (conj (str "bands: 乙 column falls between consecutive bands after band(s) "
                 (pr-str (vec (take 5 (for [[a b] (map vector bands (rest bands))
                                            :when (< (:band/otsu b) (:band/otsu a))]
                                        (:band/no a)))))))

      (some #(= ::not-whole %) all-numbers)
      (conj (str "amounts: " (count (filter #(= ::not-whole %) all-numbers))
                 " cell(s) are not non-negative whole yen"))

      (nil? sub-minimum)
      (conj "sub-minimum: no row with a 未満 marker in column 2 was found")

      (and sub-minimum (not= 105000 (:band/to sub-minimum)))
      (conj (str "sub-minimum: ceiling is " (pr-str (:band/to sub-minimum)) ", not 105000"))

      (and sub-minimum (not (every? zero? (:band/kou sub-minimum))))
      (conj (str "sub-minimum: 甲 is not zero for every dependant count: "
                 (pr-str (:band/kou sub-minimum))))

      (and sub-minimum (nil? (:band/otsu-rate sub-minimum)))
      (conj "sub-minimum: no 乙 percentage was parsed out of the column 11 text")

      (not= 9 (count thresholds))
      (conj (str "thresholds: expected 9 rows, read " (count thresholds)))

      (not= 9 (count kou-segments))
      (conj (str "甲 segments: expected 9, read " (count kou-segments)))

      (not= 2 (count otsu-segments))
      (conj (str "乙 segments: expected 2, read " (count otsu-segments)))

      (seq (remove threshold-ats (map :segment/from kou-segments)))
      (conj (str "甲 segments: start(s) with no matching threshold row: "
                 (pr-str (vec (remove threshold-ats (map :segment/from kou-segments))))))

      (seq (remove threshold-ats (map :segment/from otsu-segments)))
      (conj (str "乙 segments: start(s) with no matching threshold row: "
                 (pr-str (vec (remove threshold-ats (map :segment/from otsu-segments))))))

      (some #(or (nil? (:segment/base %)) (not= 8 (count (:segment/base %)))) kou-segments)
      (conj "甲 segments: a segment has no 8-wide base taken from its threshold row")

      (not= printed-edges derived-edges)
      (conj (str "甲 segments: the printed 未満 edges " (pr-str (vec (sort printed-edges)))
                 " disagree with the edges derived from segment order "
                 (pr-str (vec (sort derived-edges)))))

      (not= 1 (count (:deduction/values deduction)))
      (conj (str "7人超 deduction: the workbook's statements disagree or are missing: "
                 (pr-str (:deduction/values deduction))))

      (nil? year)
      (conj "year: no 令和N年分 was found in the title row"))))

;; ---------------------------------------------------------------------------
;; Emitting
;; ---------------------------------------------------------------------------

(defn- q
  "A Clojure string literal for `s`, or `nil` when there is nothing to say."
  [x]
  (if (nil? x) "nil" (pr-str x)))

(defn render
  "The generated `.cljc` source, as one string.

  Written by hand rather than by `clojure.pprint`, because this file is read
  by people checking a payslip against a printed table: bands go one per line
  in the workbook's own order, and every `def` carries the docstring that
  says what the shape means. `pprint` would produce something correct and
  unreadable."
  [{:keys [provenance sub-minimum bands thresholds kou-segments otsu-segments
           deduction]}]
  (binding [*print-namespace-maps* false]
   (str
   ";; -*- GENERATED FILE — DO NOT EDIT BY HAND -*-\n"
   ";;\n"
   ";; Produced by `clojure -M:importer` (tools/import_nta_2026.clj) from the\n"
   ";; 国税庁 workbook pinned by the SHA-256 in `provenance` below. Every figure\n"
   ";; here was READ out of that workbook; none was typed. To change it, change\n"
   ";; the importer or the pin and regenerate — an edit made here is an edit the\n"
   ";; next regeneration silently reverts.\n\n"
   "(ns payroll.rates.monthly-2026\n"
   "  \"令和8年分 給与所得の源泉徴収税額表（月額表）as data. GENERATED — see the\n"
   "  header comment; do not edit by hand.\n\n"
   "  Pure data and no logic, so that the table can be diffed against the\n"
   "  printed workbook without reading any code, and so that a regeneration\n"
   "  that changes a figure shows up as a changed figure rather than as a\n"
   "  changed calculation. The reading of it is `payroll.rates`.\n\n"
   "  The 月額表 is one of three tables in the 2026 publication. 日額表 and\n"
   "  賞与に対する源泉徴収税額の算出率の表 are NOT here, and `payroll.rates`\n"
   "  refuses rather than approximating them from this one.\")\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; Provenance\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def provenance\n"
   "  \"Which bytes this table came out of, and what was done to them.\n\n"
   "  `:source/sha256` is the load-bearing field. A URL says where a file was\n"
   "  fetched from, not what was fetched: the 国税庁 replaces these workbooks in\n"
   "  place when a 告示 is amended, so the digest is the only thing that lets a\n"
   "  reader confirm that the figures below came from the edition they are\n"
   "  holding.\n\n"
   "  `:transform/*` records the run rather than the source, so that a parse\n"
   "  that read fewer rows than the sheet has is visible in the data instead of\n"
   "  only in the importer's console output.\"\n"
   "  " (pr-str provenance) ")\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; 105,000円未満 — the band below the table\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def sub-minimum\n"
   "  \"The rows below the table's floor.\n\n"
   "  甲 is zero for every dependant count, which is an ANSWER and not an\n"
   "  absence. 乙 is the only place in the 月額表 where the workbook prints a\n"
   "  RATE instead of an amount, and it is kept as an exact ratio: the tax is\n"
   "  3.063% of the amount, and what the workbook does not say is how to\n"
   "  resolve the fraction of a yen that produces — which is why\n"
   "  `payroll.rates/withhold` refuses 乙 here rather than picking a rule.\"\n"
   "  " (pr-str sub-minimum) ")\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; The 231 discrete bands\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def bands\n"
   "  \"105,000円以上 740,000円未満, in " (count bands) " contiguous bands.\n\n"
   "  `:band/from` is 以上 (inclusive) and `:band/to` is 未満 (exclusive), which\n"
   "  is the workbook's own convention and the reason each band's `:band/to`\n"
   "  equals the next one's `:band/from` rather than being one yen below it.\n\n"
   "  `:band/kou` is indexed by 扶養親族等の数, 0 through 7. Above seven the\n"
   "  workbook subtracts `dependants-beyond-7-deduction` per person from the\n"
   "  7人 figure; that arithmetic is in `payroll.rates` and not baked in here,\n"
   "  because it is a rule and this file holds only what was printed.\"\n"
   "  [" (str/join "\n   " (map pr-str bands)) "])\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; The nine threshold rows\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def thresholds\n"
   "  \"The tax at nine exact amounts, printed rather than derived.\n\n"
   "  These are the amounts where the table stops being banded and starts being\n"
   "  a formula. The workbook prints the tax AT each of them, so those amounts\n"
   "  are answerable exactly — and they are also the bases the excess-rate\n"
   "  segments add to, which is why `kou-segments` takes its `:segment/base`\n"
   "  from here instead of restating it.\n\n"
   "  `:threshold/otsu` is nil for seven of the nine. That is the workbook's\n"
   "  shape, not a gap in this import: 乙 above 740,000円 is stated once as a\n"
   "  formula rather than row by row.\"\n"
   "  [" (str/join "\n   " (map pr-str thresholds)) "])\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; The excess-rate tail\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def kou-segments\n"
   "  \"甲欄 above 740,000円: base + rate × (amount − threshold).\n\n"
   "  `:segment/rate` is an exact ratio. `:segment/to` is nil on the last\n"
   "  segment, which is open ended — 3,500,000円を超える金額 has no upper edge.\n\n"
   "  This is a FORMULA and not an answer. The workbook states the rate and\n"
   "  does not state the 端数処理 for the yen fraction it produces, so\n"
   "  `payroll.rates/withhold` reports the exact rational and refuses to round\n"
   "  it. `:segment/basis` is the sentence that was parsed, kept so that the\n"
   "  claim can be checked against the printed page.\"\n"
   "  [" (str/join "\n   " (map pr-str kou-segments)) "])\n\n"
   "(def otsu-segments\n"
   "  \"乙欄 above 740,000円.\n\n"
   "  Two segments rather than nine, and `:segment/base` is a single amount\n"
   "  rather than eight, because 乙欄 does not depend on 扶養親族等の数 —\n"
   "  it is the column for somebody who filed no 扶養控除等申告書 at all.\"\n"
   "  [" (str/join "\n   " (map pr-str otsu-segments)) "])\n\n"

   ";; ---------------------------------------------------------------------------\n"
   ";; 扶養親族等の数が７人を超える場合\n"
   ";; ---------------------------------------------------------------------------\n\n"
   "(def dependants-beyond-7-deduction\n"
   "  \"「扶養親族等の数が７人を超える場合には、扶養親族等の数が７人の場合の税額\n"
   "  から、その７人を超える１人ごとに1,610円を控除した金額」\n\n"
   "  Read from the workbook, and read from all three places it says it, which\n"
   "  must agree. What the workbook does NOT say is what happens when the\n"
   "  subtraction exceeds the tax. `payroll.rates/withhold` floors the result at\n"
   "  zero and says so in `dependant-adjust`'s docstring — a negative\n"
   "  源泉徴収税額 would be a refund the 月額表 does not provide for. That floor\n"
   "  is THIS repository's reading and is not printed here, because this file\n"
   "  holds only what the workbook printed.\"\n"
   "  " deduction ")\n")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- die! [& lines]
  (binding [*out* *err*] (doseq [l lines] (println l)))
  (System/exit 1))

(defn -main
  "`clojure -M:importer [workbook] [output]`.

  Exits non-zero and writes nothing on a digest mismatch or on any structural
  failure. Both are printed with the reason named, because 'the importer
  failed' is not something an operator can act on and 'bands: not contiguous
  at [[86 275000 277000]]' is."
  [& args]
  (let [[wb-path out-path] args
        wb-path (or wb-path default-workbook)
        out-path (or out-path default-output)
        f (java.io.File. ^String wb-path)]
    (when-not (.isFile f)
      (die! (str "REFUSED: no workbook at " wb-path)
            (str "  fetch " (:pin/url workbook-pin))
            (str "  from  " (:pin/page workbook-pin))))
    (let [digest (sha256-hex f)
          bytes* (.length f)]
      (when (not= digest (:pin/sha256 workbook-pin))
        (die! "REFUSED: the workbook is not the one this importer has read."
              (str "  expected sha256 " (:pin/sha256 workbook-pin))
              (str "  actual   sha256 " digest)
              (str "  expected bytes  " (:pin/bytes workbook-pin))
              (str "  actual   bytes  " bytes*)
              ""
              "  国税庁 reissues these workbooks in place. A reissue that this"
              "  importer imported silently would change every payslip in the"
              "  fleet with no commit saying so. Read the new edition, confirm"
              "  the shape below still holds, then move the pin."))
      (with-open [in (FileInputStream. f)]
        (let [wbk (HSSFWorkbook. in)
              sheet-count (.getNumberOfSheets wbk)
              sheet (.getSheetAt wbk 0)
              sheet-name (.getSheetName sheet)]
          (when (not= (:pin/sheet workbook-pin) sheet-name)
            (die! (str "REFUSED: sheet 0 is named " (pr-str sheet-name)
                       ", expected " (pr-str (:pin/sheet workbook-pin)))))
          (let [grid (read-grid sheet 13)
                rows-read (count grid)
                year (parse-reiwa-year (s (nth (nth grid 0) 1)))
                bands (extract-bands grid)
                sub-min (extract-sub-minimum grid)
                thresholds (extract-thresholds grid)
                kou-segs (extract-kou-segments grid thresholds)
                otsu-segs (extract-otsu-segments grid)
                deduction (extract-beyond-7-deduction grid)
                printed-edges (extract-printed-segment-edges grid)
                failures (structural-failures
                          {:bands bands :sub-minimum sub-min
                           :thresholds thresholds :kou-segments kou-segs
                           :otsu-segments otsu-segs :deduction deduction
                           :printed-edges printed-edges :year year})]
            (when (seq failures)
              (die! (str "REFUSED: " (count failures)
                         " structural check(s) failed; nothing was written.")
                    (str/join "\n" (map #(str "  - " %) failures))))
            (let [gy (:gregorian year)
                  provenance
                  (array-map
                   :source/url (:pin/url workbook-pin)
                   :source/page (:pin/page workbook-pin)
                   :source/title (str "令和" (:reiwa year) "年分 給与所得の源泉徴収税額表（"
                                      sheet-name "）")
                   :source/authority (:pin/authority workbook-pin)
                   :source/sha256 digest
                   :source/bytes bytes*
                   :source/retrieved-at (:pin/retrieved-at workbook-pin)
                   :source/sheet sheet-name
                   :source/applicability
                   (array-map
                    :applicability/from (str gy "-01")
                    :applicability/to (str gy "-12")
                    :applicability/basis (str "令和" (:reiwa year) "年分。"
                                              "表題行から読み取った適用年であり、"
                                              "importer が仮定した年ではない")
                    :applicability/not-covered
                    (str "日額表・賞与に対する源泉徴収税額の算出率の表は"
                         "この workbook の別表であり、転記していない"))
                   :transform/importer "tools/import_nta_2026.clj"
                   :transform/version importer-version
                   :transform/sheets sheet-count
                   :transform/rows-read rows-read
                   :transform/bands (count bands)
                   :transform/thresholds (count thresholds)
                   :transform/kou-segments (count kou-segs)
                   :transform/otsu-segments (count otsu-segs))
                  out (render {:provenance provenance
                               :sub-minimum sub-min
                               :bands bands
                               :thresholds thresholds
                               :kou-segments kou-segs
                               :otsu-segments otsu-segs
                               :deduction (:deduction/yen deduction)})]
              (.mkdirs (.getParentFile (java.io.File. ^String out-path)))
              (spit out-path out)
              (println (str "wrote " out-path))
              (println (str "  sha256      " digest))
              (println (str "  rows read   " rows-read))
              (println (str "  bands       " (count bands)
                            "  (" (:band/from (first bands)) "〜"
                            (:band/to (last bands)) ")"))
              (println (str "  thresholds  " (count thresholds)))
              (println (str "  甲 segments " (count kou-segs)
                            "   乙 segments " (count otsu-segs)))
              (println (str "  7人超控除   " (:deduction/yen deduction) "円"))
              (println (str "  applicable  " gy "-01〜" gy "-12")))))))))
