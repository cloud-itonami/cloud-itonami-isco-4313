(ns payroll.digest
  "Content addressing, in portable ClojureScript-and-Clojure arithmetic.

  `payroll.store.kotobase` stores every payload under the hash of its own
  bytes. That is not decoration: a payroll record that can be rewritten in
  place is a payroll record whose history is a claim rather than a fact, and
  the whole argument for putting this actor's ledger somewhere durable is
  that afterwards somebody can ask what it refused and get an answer nobody
  could have edited.

  So the address has to be a function of the content, and the function has to
  be the one kotobase.net actually accepts: `PUT /ipfs/:cid` takes a **raw
  CIDv1 over SHA-256** and nothing else (CLAUDE.md records the 400
  `not-raw-sha256` this repository would otherwise earn).

  ## Why SHA-256 is written out here rather than taken from a host

  `MessageDigest` is JVM-only and `crypto.subtle` is async and browser-only.
  This namespace is `.cljc` for the same reason every other namespace here is
  — the store, the projection and the cutover evidence all have to be
  testable without a platform — and a digest that existed on one runtime
  would make the content address a property of where the code ran.

  **It is checked against the published vectors, not against itself.**
  `payroll.digest-test` runs FIPS 180-4's `\"abc\"`, the empty string, the
  56-character double-block message and a 1,000,000-character message, plus
  RFC 4648's base32 vectors. A hash function that agrees with nothing but its
  own previous output is a hash function that will disagree with the node.

  ## What is measured and what is not

  Measured on the JVM, by the suite in this repository. The ClojureScript
  path is written with `u32` after every operation precisely because the two
  runtimes disagree about integer width — `bit-and` returns a signed 32-bit
  int in JavaScript and a 64-bit long on the JVM — but **this repository's
  suite does not run under ClojureScript**, so that path is careful rather
  than verified. That distinction is the kind this repository exists to keep,
  so it is written here rather than left to be assumed."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 32-bit arithmetic that means the same thing on both runtimes
;; ---------------------------------------------------------------------------

(defn- u32
  "`x` reduced to an unsigned 32-bit value.

  On the JVM a mask is enough — everything is a long. In JavaScript `bit-and`
  yields a SIGNED int32, so `(bit-and x 0xFFFFFFFF)` answers -1 for
  0xFFFFFFFF and every comparison afterwards is wrong; `>>> 0` is the
  conversion that produces an unsigned value, and `unsigned-bit-shift-right`
  by zero is how it is spelled."
  [x]
  #?(:clj (bit-and x 0xFFFFFFFF)
     :cljs (unsigned-bit-shift-right x 0)))

(defn- rotr [x n] (u32 (bit-or (unsigned-bit-shift-right (u32 x) n)
                               (u32 (bit-shift-left x (- 32 n))))))

(defn- shr [x n] (unsigned-bit-shift-right (u32 x) n))

(def ^:private round-constants
  "The 64 constants of FIPS 180-4 §4.2.2 — the first 32 bits of the fractional
  parts of the cube roots of the first 64 primes. Transcribed, and checked by
  the published digests rather than by re-deriving them."
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1
   0x923f82a4 0xab1c5ed5 0xd807aa98 0x12835b01 0x243185be 0x550c7dc3
   0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174 0xe49b69c1 0xefbe4786
   0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147
   0x06ca6351 0x14292967 0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13
   0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85 0xa2bfe8a1 0xa81a664b
   0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a
   0x5b9cca4f 0x682e6ff3 0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208
   0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

(def ^:private initial-state
  "FIPS 180-4 §5.3.3 — the square roots of the first eight primes."
  [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a
   0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19])

;; ---------------------------------------------------------------------------
;; UTF-8, written out for the same reason the digest is
;; ---------------------------------------------------------------------------

(defn utf8-bytes
  "A string as a vector of unsigned bytes.

  Written here rather than taken from a host because `.getBytes` is JVM-only
  and `TextEncoder` is not on every ClojureScript target. Surrogate pairs are
  combined before encoding: a payroll record can carry a name outside the
  basic plane, and encoding each half separately would produce CESU-8 — bytes
  a node would accept, store under an address nothing else computes, and
  hand back as a name nobody can read."
  [s]
  (loop [i 0 out (transient [])]
    (if (>= i (count s))
      (persistent! out)
      (let [c #?(:clj (int (.charAt ^String s i))
                 :cljs (.charCodeAt ^string s i))
            surrogate? (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) (count s)))
            lo (when surrogate?
                 #?(:clj (int (.charAt ^String s (inc i)))
                    :cljs (.charCodeAt ^string s (inc i))))
            pair? (and surrogate? (>= lo 0xDC00) (<= lo 0xDFFF))
            cp (if pair?
                 (+ 0x10000 (bit-shift-left (- c 0xD800) 10) (- lo 0xDC00))
                 c)
            step (if pair? 2 1)]
        (recur (+ i step)
               (cond
                 (< cp 0x80) (conj! out cp)
                 (< cp 0x800)
                 (-> out
                     (conj! (bit-or 0xC0 (bit-shift-right cp 6)))
                     (conj! (bit-or 0x80 (bit-and cp 0x3F))))
                 (< cp 0x10000)
                 (-> out
                     (conj! (bit-or 0xE0 (bit-shift-right cp 12)))
                     (conj! (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                     (conj! (bit-or 0x80 (bit-and cp 0x3F))))
                 :else
                 (-> out
                     (conj! (bit-or 0xF0 (bit-shift-right cp 18)))
                     (conj! (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F)))
                     (conj! (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                     (conj! (bit-or 0x80 (bit-and cp 0x3F))))))))))

;; ---------------------------------------------------------------------------
;; SHA-256
;; ---------------------------------------------------------------------------

(defn- padded
  "FIPS 180-4 §5.1.1: append `0x80`, then zeros, then the 64-bit bit length."
  [bytes*]
  (let [len (count bytes*)
        bit-len (* 8 len)
        zeros (mod (- 56 (inc len)) 64)
        ;; `quot` by a literal divisor rather than a shift: a message of
        ;; 2^29 bytes overflows a 32-bit shift in JavaScript and would
        ;; silently record the wrong length, which is the one field of the
        ;; padding an attacker chooses. The divisors are written out because
        ;; `Math/pow` returns a double and `long` does not exist in
        ;; ClojureScript.
        length-bytes (for [d [72057594037927936 281474976710656 1099511627776
                              4294967296 16777216 65536 256 1]]
                       (mod (quot bit-len d) 256))]
    (into (into (conj bytes* 0x80) (repeat zeros 0)) length-bytes)))

(defn- word-at [block i]
  (u32 (+ (* 16777216 (nth block (* 4 i)))
          (* 65536 (nth block (+ 1 (* 4 i))))
          (* 256 (nth block (+ 2 (* 4 i))))
          (nth block (+ 3 (* 4 i))))))

(defn- schedule
  "The 64-word message schedule for one 512-bit block."
  [block]
  (loop [t 16 w (mapv #(word-at block %) (range 16))]
    (if (= t 64)
      w
      (let [w15 (nth w (- t 15))
            w2 (nth w (- t 2))
            s0 (bit-xor (rotr w15 7) (rotr w15 18) (shr w15 3))
            s1 (bit-xor (rotr w2 17) (rotr w2 19) (shr w2 10))]
        (recur (inc t)
               (conj w (u32 (+ (nth w (- t 16)) s0 (nth w (- t 7)) s1))))))))

(defn- compress [state block]
  (let [w (schedule block)]
    (loop [t 0 [a b c d e f g h] state]
      (if (= t 64)
        (mapv u32 (map + state [a b c d e f g h]))
        (let [s1 (bit-xor (rotr e 6) (rotr e 11) (rotr e 25))
              ch (bit-xor (bit-and e f) (bit-and (u32 (bit-not e)) g))
              t1 (u32 (+ h s1 ch (nth round-constants t) (nth w t)))
              s0 (bit-xor (rotr a 2) (rotr a 13) (rotr a 22))
              maj (bit-xor (bit-and a b) (bit-and a c) (bit-and b c))
              t2 (u32 (+ s0 maj))]
          (recur (inc t)
                 [(u32 (+ t1 t2)) a b c (u32 (+ d t1)) e f g]))))))

(defn sha256
  "The SHA-256 digest of a byte vector, as a vector of 32 unsigned bytes."
  [bytes*]
  (let [msg (padded (vec bytes*))
        blocks (partition 64 msg)
        state (reduce compress initial-state blocks)]
    (vec (mapcat (fn [word]
                   [(bit-and (unsigned-bit-shift-right word 24) 0xFF)
                    (bit-and (unsigned-bit-shift-right word 16) 0xFF)
                    (bit-and (unsigned-bit-shift-right word 8) 0xFF)
                    (bit-and word 0xFF)])
                 state))))

(def ^:private hex-digits "0123456789abcdef")

(defn hex
  "A byte vector as lower-case hex — the form every published SHA-256 vector
  is written in, so the test can compare against the source rather than
  against a re-encoding of it."
  [bytes*]
  (apply str (mapcat (fn [b] [(nth hex-digits (quot b 16))
                              (nth hex-digits (mod b 16))])
                     bytes*)))

(defn sha256-hex [s] (hex (sha256 (utf8-bytes s))))

;; ---------------------------------------------------------------------------
;; multibase base32 and CIDv1
;; ---------------------------------------------------------------------------

(def ^:private base32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn base32
  "RFC 4648 base32, lower-case, **unpadded** — which is what multibase `b`
  means. Padding is omitted rather than stripped: multibase does not define
  `=` and a node comparing addresses as strings would see two."
  [bytes*]
  (loop [bs (seq bytes*) acc 0 bits 0 out (transient [])]
    (cond
      (>= bits 5)
      (recur bs
             (bit-and acc (dec (bit-shift-left 1 (- bits 5))))
             (- bits 5)
             (conj! out (nth base32-alphabet
                             (unsigned-bit-shift-right acc (- bits 5)))))

      (seq bs)
      (recur (next bs) (+ (* acc 256) (first bs)) (+ bits 8) out)

      (pos? bits)
      (persistent! (conj! out (nth base32-alphabet
                                   (bit-shift-left acc (- 5 bits)))))

      :else (persistent! out))))

(def cid-prefix
  "The four bytes in front of a raw CIDv1's digest.

  `0x01` CID version 1, `0x55` the `raw` multicodec, `0x12` the `sha2-256`
  multihash code, `0x20` its 32-byte length. Named rather than inlined so the
  test can decode a produced CID back to these four bytes and the digest —
  which checks the assembly without needing a memorised base32 string to
  compare against."
  [0x01 0x55 0x12 0x20])

(defn cid
  "A raw CIDv1 over SHA-256 for `bytes*`, as the `b`-prefixed multibase string
  kotobase's `PUT /ipfs/:cid` accepts."
  [bytes*]
  (str "b" (apply str (base32 (into cid-prefix (sha256 bytes*))))))

(defn cid-of-string [s] (cid (utf8-bytes s)))

(defn base32-decode
  "The inverse of `base32`, for the test that decodes a CID rather than
  comparing it to a remembered string. Returns nil on a character outside the
  alphabet — a CID with a typo is not a CID with a nearby meaning."
  [s]
  (loop [cs (seq (str/lower-case (str s))) acc 0 bits 0 out (transient [])]
    (if-let [c (first cs)]
      (if-let [i (str/index-of base32-alphabet (str c))]
        (let [acc' (+ (* acc 32) i) bits' (+ bits 5)]
          (if (>= bits' 8)
            (recur (next cs)
                   (bit-and acc' (dec (bit-shift-left 1 (- bits' 8))))
                   (- bits' 8)
                   (conj! out (unsigned-bit-shift-right acc' (- bits' 8))))
            (recur (next cs) acc' bits' out)))
        nil)
      (persistent! out))))

(defn cid?
  "Does `x` look like a raw CIDv1 this repository produced?

  Decoded and checked against `cid-prefix`, never matched with a regex. A
  string that merely starts with `bafkrei` is not a content address, and the
  place that would accept one is the place a payload gets stored under an
  address nothing can reproduce."
  [x]
  (boolean
   (and (string? x)
        (str/starts-with? x "b")
        (when-let [bs (base32-decode (subs x 1))]
          (and (= (count bs) (+ (count cid-prefix) 32))
               (= (vec (take (count cid-prefix) bs)) cid-prefix))))))

(defn utf8-string
  "The inverse of `utf8-bytes`.

  Written out for the same reason: `String.` with a charset is JVM-only. A
  byte sequence that is not valid UTF-8 returns nil rather than a string with
  replacement characters in it — a payroll record that came back subtly
  altered would round-trip through every check in this repository and reach a
  payslip, whereas a nil fails at the read."
  [bytes*]
  (loop [bs (vec bytes*) i 0 out (transient [])]
    (if (>= i (count bs))
      (apply str (persistent! out))
      (let [b (nth bs i)
            cont (fn [n] (let [tail* (subvec bs (inc i) (min (count bs) (+ i 1 n)))]
                           (when (and (= n (count tail*))
                                      (every? #(= 0x80 (bit-and % 0xC0)) tail*))
                             tail*)))]
        (cond
          (< b 0x80) (recur bs (inc i) (conj! out (char b)))

          (= 0xC0 (bit-and b 0xE0))
          (when-let [t (cont 1)]
            (recur bs (+ i 2)
                   (conj! out (char (bit-or (bit-shift-left (bit-and b 0x1F) 6)
                                            (bit-and (nth t 0) 0x3F))))))

          (= 0xE0 (bit-and b 0xF0))
          (when-let [t (cont 2)]
            (recur bs (+ i 3)
                   (conj! out (char (bit-or (bit-shift-left (bit-and b 0x0F) 12)
                                            (bit-shift-left (bit-and (nth t 0) 0x3F) 6)
                                            (bit-and (nth t 1) 0x3F))))))

          (= 0xF0 (bit-and b 0xF8))
          (when-let [t (cont 3)]
            (let [cp (bit-or (bit-shift-left (bit-and b 0x07) 18)
                             (bit-shift-left (bit-and (nth t 0) 0x3F) 12)
                             (bit-shift-left (bit-and (nth t 1) 0x3F) 6)
                             (bit-and (nth t 2) 0x3F))
                  v (- cp 0x10000)]
              (recur bs (+ i 4)
                     (-> out
                         (conj! (char (+ 0xD800 (unsigned-bit-shift-right v 10))))
                         (conj! (char (+ 0xDC00 (bit-and v 0x3FF))))))))

          :else nil)))))
