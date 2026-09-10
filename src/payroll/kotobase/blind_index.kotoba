(ns payroll.kotobase.blind-index
  "The keyed seam that decides whether two writes are the same write.

  ## What was here before, and why it was a hole

  `payroll.store.kotobase` used to tag each keyed write with a plain
  `SHA-256` over `tenant|stream|key`. Its own docstring admitted the
  consequence and shipped anyway:

    > It is a plain SHA-256 and **not** a keyed MAC: an attacker who can
    > guess a contract id can confirm the guess from a node.

  That is a **confirmation oracle**, and on this actor's data it is not a
  theoretical one. The keyed streams are `clients`, `contracts` and
  `cutover`, whose keys are `emp-1`, `c-tanaka` and `emp-1/2026-08` — a
  space small enough to enumerate on a laptop. Anybody who can read the
  chain (the nodes are deliberately NOT sealed, so that a key rotation
  cannot take the history with it) could hash a guessed employee id and
  learn whether that person is on this employer's payroll, without ever
  opening a single sealed payload.

  So the tag becomes a **keyed blind index**: `tag(tenant, stream, key)` is
  computed by a provider holding a secret this repository never sees.
  Without the secret the tags are unlinkable to the keys, and the oracle is
  gone. The store's idempotency is unaffected, because idempotency only ever
  needed tag equality and never needed the tag to be reversible.

  ## The key MUST NOT be the envelope's key

  `distinct-from-envelope?` is checked at construction, not documented as a
  convention, and `payroll.store.kotobase/store` refuses when it fails.

  Two reasons, and either alone is sufficient:

  1. **Different lifetimes.** An envelope key is rotated when it is believed
     compromised, and old ciphertext is re-sealed. A blind-index key can
     NEVER be rotated without losing the ability to recognise every write
     that came before it — rotating it turns the whole history into
     tags nothing can match, so a redelivered `:commit` writes a second
     payment. Two facts that must rotate on different schedules cannot be
     one secret.
  2. **Different exposure.** The tags travel in the CLEAR on every node, so
     the blind-index key is under continuous offline attack by anybody who
     can read the chain; the envelope key only ever sees ciphertext nobody
     can check a guess against. Sharing one secret hands the envelope the
     blind index's threat model.

  This repository ships **no** provider — the same rule
  `payroll.kotobase.envelope` keeps, for the same reason. A default here
  would be a default secret, and a default secret is no secret."
  (:require [payroll.kotobase.envelope :as envelope]
            [payroll.sensitive :as sensitive]))

(defprotocol BlindIndex
  (tag [b tenant stream k]
    "`[tenant stream key] -> {:index/status :ok :index/tag \"…\"}` or
    `{:index/status :refused :index/why …}`.

    The tag MUST be a stable, opaque, non-reversible string: the same three
    arguments give the same tag forever, and a party without the key cannot
    go from a tag back to `k` nor confirm a guess at `k`.

    `tenant` and `stream` are separate arguments rather than concatenated by
    the caller so a provider can bind them as domain separation without
    relying on a delimiter never appearing in a key.")
  (describe [b]
    "What this provider is, with no secret in it — for the health surface."))

(def refusing-blind-index
  "What a deployment gets when it configures none. It refuses to tag, so a
  durable store built on it cannot be built at all."
  (reify BlindIndex
    (tag [_ _ _ _]
      {:index/status :refused
       :index/why (str "blind index プロバイダが設定されていない。"
                       "鍵無しの SHA-256 は、"
                       "契約 ID を当てられる者にとって確認オラクルになる"
                       "（PAYROLL_BLIND_INDEX を参照）")})
    (describe [_]
      {:index/scheme :none
       :index/configured? false
       :index/why "未設定。durable mode はこの状態では起動しない"})))

(defn configured?
  "Does this provider actually tag, and does it tag STABLY?

  Measured by asking it twice with the same arguments and once with a
  different key, rather than by reading a flag it sets about itself. A
  provider that returned a fresh value per call would make every write a
  first write — a redelivered `:commit` would be a second payment, which is
  the exact failure the tag exists to prevent — and a provider that returned
  a CONSTANT would collapse two different contracts into one registration.

  Both probes run over a throwaway tenant at start-up, where an operator can
  still act on the answer."
  [b]
  (boolean
   (and (satisfies? BlindIndex b)
        (let [a (tag b "probe" :probe :k1)
              a' (tag b "probe" :probe :k1)
              c (tag b "probe" :probe :k2)
              d (tag b "probe" :other :k1)
              e (tag b "probe2" :probe :k1)]
          (and (= :ok (:index/status a))
               (string? (:index/tag a))
               (seq (:index/tag a))
               (= (:index/tag a) (:index/tag a'))
               (not= (:index/tag a) (:index/tag c))
               (not= (:index/tag a) (:index/tag d))
               (not= (:index/tag a) (:index/tag e)))))))

(defn describes-safely?
  "Does `describe` avoid every key that must never be logged?"
  [b]
  (empty? (sensitive/log-violations (describe b))))

(defn distinct-from-envelope?
  "Is this blind index keyed by something OTHER than the envelope's key?

  Two checks, because the failure has two shapes and each is invisible to
  the other's test:

  1. **Identity.** One object implementing both protocols is one secret
     wearing two names.
  2. **Declared key id.** Two objects that both say `:key-id \"payroll-2026\"`
     are two handles on one secret; a deployment that wired the same
     keychain item into both env vars is the likely way this happens, and it
     would otherwise pass check 1.

  A provider that declines to publish a `:key-id` cannot be checked this
  way, and that is reported as UNKNOWN by `why-not-distinct` rather than
  silently passing — see that function. This predicate is deliberately
  conservative in the other direction: it answers `false` only when the two
  are demonstrably the same, because refusing a deployment on the strength
  of a missing field would refuse every provider that publishes nothing."
  [b envelope*]
  (not (or (identical? b envelope*)
           (let [bk (:index/key-id (describe b))
                 ek (:envelope/key-id (envelope/describe envelope*))]
             (and (some? bk) (some? ek) (= bk ek))))))

(defn key-separation
  "What is actually KNOWN about whether the two keys are separate, as a
  three-valued answer for the health surface.

    :separate  both published a key id and the ids differ
    :same      the same object, or the same published key id — a refusal
    :unknown   at least one provider publishes no key id, so this was NOT
               checked. Reported as unknown and never as separate, because
               `we did not look` and `we looked and they differ` are the two
               answers this repository most often finds collapsed."
  [b envelope*]
  (let [bk (:index/key-id (describe b))
        ek (:envelope/key-id (envelope/describe envelope*))]
    (cond
      (identical? b envelope*) :same
      (and (some? bk) (some? ek) (= bk ek)) :same
      (and (some? bk) (some? ek)) :separate
      :else :unknown)))
