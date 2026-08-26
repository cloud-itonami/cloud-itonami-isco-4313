(ns payroll.kotobase.envelope
  "The encryption seam between a payroll payload and a durable block.

  ## This repository ships no cipher, and that is the design

  There is no `aes-provider` here and there is no default. `Envelope` is a
  protocol a deployment implements, `refusing-envelope` is what you get if
  you do not, and `payroll.host.config` will not start a durable deployment
  without one. A payroll actor that quietly wrote plaintext because nobody
  configured a key would be doing the thing it exists to refuse — and unlike
  a wrong amount, that one is invisible until somebody else reads the store.

  Writing a cipher here was considered and rejected for the reason
  `payroll.artifact.bank-transfer` gives about the 全銀 layout: the cost of
  being subtly wrong is not proportional to the cost of not shipping it.
  Key derivation, nonce discipline, rotation and authenticated associated
  data are four separate ways to build something that encrypts and does not
  protect, and none of them fails loudly.

  ## What the seam guarantees regardless of the provider

  - **`seal` returns a value, not bytes.** `{:envelope/scheme :envelope/key-id
    :envelope/ciphertext}` — so a store can record WHICH key sealed a block
    without holding the key, and a rotation is a readable fact rather than an
    archaeology problem.
  - **`describe` may not carry a secret.** `payroll.sensitive/log-violations`
    is run over it by `payroll.kotobase.envelope-test`, because a health
    endpoint printing `:envelope/key` is exactly the shape of accident this
    namespace is otherwise preventing.
  - **`open` may refuse.** A block sealed under a key this provider does not
    have is `{:envelope/status :refused}` and never an exception carrying the
    ciphertext, and never a partial plaintext."
  (:require [payroll.sensitive :as sensitive]))

(defprotocol Envelope
  (seal [e tenant plaintext]
    "Plaintext EDN string -> `{:envelope/status :ok :envelope/scheme …
    :envelope/key-id … :envelope/ciphertext …}` or
    `{:envelope/status :refused :envelope/why …}`.

    `tenant` is passed so a provider MAY bind the ciphertext to the tenant
    (associated data). Whether it does is the provider's business; that it is
    given the opportunity is this seam's.")
  (open [e tenant envelope*]
    "The inverse, or `{:envelope/status :refused :envelope/why …}`.")
  (describe [e]
    "What this provider is, with no secret in it — for the health surface."))

(def refusing-envelope
  "The provider a deployment gets when it configures none.

  It refuses to seal, which means a durable store built on it cannot write.
  That is the intended failure: loud, at start-up, on the first write, rather
  than quiet and discovered later by whoever reads the blocks."
  (reify Envelope
    (seal [_ _ _]
      {:envelope/status :refused
       :envelope/why (str "暗号化プロバイダが設定されていない。"
                          "給与の台帳を平文で durable store に書くことは"
                          "この actor の設定では起こらない"
                          "（PAYROLL_ENCRYPTION を参照）")})
    (open [_ _ _]
      {:envelope/status :refused
       :envelope/why "暗号化プロバイダが設定されていないので、復号もできない"})
    (describe [_]
      {:envelope/scheme :none
       :envelope/configured? false
       :envelope/why "未設定。durable mode はこの状態では起動しない"})))

(defn configured?
  "Does this provider actually seal? Measured by asking it to, with a
  one-character probe over a throwaway tenant, rather than by reading a flag
  it sets about itself.

  A flag would be a claim; this is the same check the store will make on its
  first real write, run at start-up where an operator can still act on it."
  [e]
  (boolean (and (satisfies? Envelope e)
                (= :ok (:envelope/status (seal e "probe" "x"))))))

(defn describes-safely?
  "Does `describe` avoid every key that must never be logged?"
  [e]
  (empty? (sensitive/log-violations (describe e))))
