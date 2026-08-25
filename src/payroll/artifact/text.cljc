(ns payroll.artifact.text
  "Deterministic CSV and JSON. Pure, portable, and byte-identical for equal
  input on every runtime this repository targets.

  An export is a file somebody hands to a bank, an accountant or a tax office.
  Two properties matter more than any feature:

  1. **The same input produces the same bytes.** Not `equal maps`, not
     `equivalent JSON` — the same bytes. A diff between this month's file and
     last month's has to show what changed in the payroll, and a key order
     that depends on hash seed shows a diff every month. So every writer here
     takes an explicit COLUMN VECTOR and never iterates a map.
  2. **A value this repository does not have is never a blank cell.** A blank
     is read as zero by the person opening it and as zero by every spreadsheet
     that opens it. `unknown-cell` is what goes there instead, and it is not
     a value a downstream parser will silently accept.

  ## What was taken from `kotoba.labor.export`, and what was not

  That namespace has the CSV and JSON escaping this one needs, and its
  comments record two bugs found by testing against real parsers: a bare `\\r`
  is a CSV line break and has to be quoted, and RFC 8259 §7 requires EVERY
  control character U+0000-U+001F to be escaped in a JSON string. Both fixes
  are reproduced here **because its versions are private**, and both are
  re-tested here rather than trusted — a fix copied without its test is a fix
  that is one refactor away from being gone.

  What is NOT taken is its number handling. `(or (:payroll/gross p) 0)`
  turns a missing figure into a zero in the JSON, which is the single thing
  this repository exists to refuse, so the writers here take figures rather
  than numbers and refuse."
  (:require [clojure.string :as str]
            [payroll.provenance :as prov]))

;; ---------------------------------------------------------------------------
;; The cell that is not a value
;; ---------------------------------------------------------------------------

(def unknown-cell
  "What goes in a cell whose figure carries no number.

  Not the empty string, which every spreadsheet reads as zero. Not `0`. Not
  `N/A`, which is a value some parsers coerce. A marker that is obviously not
  a number, that survives a CSV round-trip unquoted, and that a downstream
  numeric parse fails on **loudly** — failing there is the correct outcome,
  because the figure genuinely is not known and a pipeline that continued
  would be continuing on a number nobody has."
  "未確定")

(def not-applicable-cell
  "A line that does not arise. Distinct from `unknown-cell` because they are
  opposite instructions: one means somebody has to go and find out, the other
  means there is nothing to find out."
  "該当なし")

(defn cell
  "A `payroll.provenance` figure as a cell.

  The three numberless provenances get their marker; the rest get the number.
  An `:imported` figure gets the number and NOT a marker — the marker belongs
  in the provenance column, which every artifact here carries, rather than
  glued onto the amount where it would break the column's type for the sake
  of a warning."
  [f]
  (case (:figure/provenance f)
    :not-applicable not-applicable-cell
    (:unknown :held) unknown-cell
    (:figure/amount f)))

;; ---------------------------------------------------------------------------
;; CSV — RFC 4180
;; ---------------------------------------------------------------------------

(defn csv-cell
  "One field, quoted per RFC 4180.

  A field containing a comma, a double quote, a line feed **or a carriage
  return** is quoted. `\\r` alone is a row terminator every standard reader
  recognises, so a field carrying one and left unquoted splits into two
  corrupted rows on read-back — the bug `kotoba.labor.export` records having
  found against Python's `csv` module."
  [v]
  (let [s (str (if (nil? v) "" v))]
    (if (re-find #"[\",\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn csv-row [vals] (str/join "," (map csv-cell vals)))

(def line-terminator
  "`\\n`, stated rather than assumed.

  RFC 4180 says CRLF and this emits LF, which every reader in use accepts and
  which keeps the bytes stable across the platforms this runs on. It is
  declared here so an artifact whose consumer genuinely requires CRLF is a
  change to one name rather than a search."
  "\n")

(defn csv
  "`{:columns [{:column/key :column/header} …] :rows [row-map …]}` → a CSV
  string.

  Column order is the vector's, never a map's. A row missing a column's key
  yields an EMPTY cell and not an error, because a row map is allowed to be
  sparse — but a row whose value is a FIGURE goes through `cell`, so an
  unknown figure yields the marker rather than a blank. The two cases are
  different: a genuinely absent column is the artifact's shape, an unknown
  figure is the payroll's state."
  [{:keys [columns rows]}]
  (str/join line-terminator
            (cons (csv-row (map :column/header columns))
                  (for [r rows]
                    (csv-row (for [c columns]
                               (let [v (get r (:column/key c))]
                                 (if (and (map? v) (contains? v :figure/provenance))
                                   (cell v)
                                   v))))))))

;; ---------------------------------------------------------------------------
;; JSON — RFC 8259
;; ---------------------------------------------------------------------------

(def ^:private json-hex-digits "0123456789abcdef")

(defn- json-hex4
  "4-digit hex for a `\\uXXXX` escape. Bit ops and a lookup table rather than
  host interop, so the same code runs on both runtimes."
  [n]
  (apply str (for [shift [12 8 4 0]]
               (nth json-hex-digits (bit-and (bit-shift-right n shift) 0xf)))))

(def ^:private string-escapes
  "RFC 8259 §7: every control character U+0000-U+001F must be escaped, not
  merely the five with short forms. An operator-supplied field carrying a raw
  tab would otherwise be copied through and produce invalid JSON."
  (into {\" "\\\"" \\ "\\\\"}
        (for [i (range 0x20)]
          [(char i) (case i
                      8 "\\b" 9 "\\t" 10 "\\n" 12 "\\f" 13 "\\r"
                      (str "\\u" (json-hex4 i)))])))

(defn json-string [v]
  (str "\"" (str/escape (str (if (nil? v) "" v)) string-escapes) "\""))

(defn finite-number?
  "Is `n` a number JSON can represent?

  `(== n n)` and **not** `(not= n n)`. The idiomatic NaN test does not work
  through a function parameter: `clojure.lang.Util/equiv` short-circuits on
  reference identity (`if(k1 == k2) return true`), and both arguments are the
  same boxed Double, so `(= n n)` answers TRUE for a NaN and `not=` answers
  false. Measured 2026-08-25: the same expression answered correctly when
  written inline over a `let` binding and incorrectly inside a `defn`, which
  is exactly the shape of bug that survives a REPL check.

  `==` goes through `clojure.lang.Numbers` and has no identity shortcut, so
  it gives the numeric answer. Infinity IS equal to itself under `==` and
  needs the separate bound."
  [n]
  (and (number? n)
       (== n n)
       (< ##-Inf n ##Inf)))

(defn- json-number
  "A number, or a refusal.

  Refuses anything that is not finite — `##NaN` and `##Inf` have no JSON
  representation and every encoder that emits them produces a document no
  strict parser will read. Refusing here means the artifact writer fails on
  the row that is wrong rather than the consumer failing on the file."
  [n]
  (if (finite-number? n)
    (str n)
    (throw (ex-info "not a JSON-representable number" {:value n}))))

(declare json-value)

(defrecord JsonText [text])

(defn raw-json
  "Already-rendered JSON, so it can be nested without being escaped again.

  This type exists because of a bug the tests caught: `json-object-of`
  returned a String, and a String is a JSON string — so every nested object
  was emitted as a quoted, backslash-escaped blob of its own source. The
  document parsed, every key was present, and every nested value was text.
  Measured 2026-08-25.

  A distinct type rather than a convention (a marker key, a `str/starts-with?
  \"{\"` check) because a convention is exactly what produced the bug: a
  String meant two things depending on where it came from, and nothing at the
  boundary could tell."
  [s]
  (->JsonText s))

(defn- json-object [pairs]
  (str "{"
       (str/join "," (for [[k v] pairs]
                       (str (json-string (if (keyword? k) (name k) k))
                            ":" (json-value v))))
       "}"))

(defn json-value
  "EDN → JSON text.

  Key order is the caller's: a map is emitted in `seq` order, so callers that
  need determinism pass an ordered sequence of pairs via `json-object-of`
  rather than a hash map. A map IS accepted, because refusing one would make
  every nested structure ceremonial, and small literal maps in source keep
  their written order on both runtimes — but nothing at the top level of an
  artifact relies on that."
  [v]
  (cond
    ;; FIRST, and before `map?` — a JsonText is a record and therefore a map,
    ;; so a later branch would never be reached.
    (instance? JsonText v) (:text v)
    (nil? v) "null"
    (boolean? v) (str v)
    (number? v) (json-number v)
    (keyword? v) (json-string (if (namespace v)
                                (str (namespace v) "/" (name v))
                                (name v)))
    (symbol? v) (json-string (str v))
    (string? v) (json-string v)
    (map? v) (json-object (seq v))
    (sequential? v) (str "[" (str/join "," (map json-value v)) "]")
    (set? v) (str "[" (str/join "," (map json-value (sort-by str v))) "]")
    :else (json-string (str v))))

(defn json-object-of
  "An ordered JSON object from `[[k v] …]`, as a **nestable** value.

  Returns `JsonText`, not a String, so `json-value` emits it as an object and
  not as a quoted blob. Use `json-document` at an artifact's top level, where
  a String is what the caller wants."
  [pairs]
  (raw-json (json-object pairs)))

(defn json-document
  "An artifact's top-level JSON, as a String. Key order is the caller's."
  [pairs]
  (json-object pairs))

;; ---------------------------------------------------------------------------
;; A figure, in a machine-readable file
;; ---------------------------------------------------------------------------

(defn figure->json-pairs
  "A figure as ordered JSON pairs: the amount, then how it is known.

  The amount is `null` for the numberless provenances. That is safe HERE and
  is not safe in CSV, because JSON `null` is a distinct value a parser hands
  back as such, whereas an empty CSV cell is indistinguishable from a zero.
  The provenance rides alongside either way, so a consumer that reads the
  amount alone still cannot mistake an unverified figure for a certified one
  — it has to have chosen not to look."
  [f]
  [[:amount (:figure/amount f)]
   [:provenance (:figure/provenance f)]
   [:certified_by_this_repository (= :derived (:figure/provenance f))]
   [:label (:figure/label f)]
   [:why (:figure/why f)]
   [:source (:figure/source f)]])

(defn figures->json
  "`[[key figure] …]` → an ordered JSON object of figure objects, nestable."
  [named-figures]
  (json-object-of
   (for [[k f] named-figures]
     [k (json-object-of (figure->json-pairs f))])))

(defn figure-json
  "One figure as a nestable JSON object."
  [f]
  (json-object-of (figure->json-pairs f)))

;; ---------------------------------------------------------------------------
;; The evidence floor every artifact carries
;; ---------------------------------------------------------------------------

(defn coverage
  "How much of an artifact is a figure this repository certified.

  Every artifact writer emits this, and it is not decoration. An export whose
  every figure is `:declared` is a faithful transcription of what somebody
  typed and certifies nothing; one where the count of figures is ZERO is an
  empty file that will read as a clean one. Reporting the counts makes both
  visible in the artifact itself rather than in a docstring.

  `:figures` being 0 is the evidence floor: an artifact that scanned nothing
  must not print as an artifact that scanned everything and found it fine."
  [figures]
  (let [by (frequencies (map :figure/provenance figures))]
    {:coverage/figures (count figures)
     :coverage/by-provenance (into {} (for [p prov/provenances
                                            :let [n (get by p 0)]
                                            :when (pos? n)]
                                        [p n]))
     :coverage/certified (get by :derived 0)
     :coverage/unverified (count (filter prov/unverified? figures))}))
