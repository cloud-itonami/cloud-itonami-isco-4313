(ns payroll.kotobase.transport
  "The seam between this actor and a kotobase.net node.

  ## No HTTP client is written here, deliberately

  `Transport` is a protocol. A deployment supplies an implementation; this
  repository supplies none that reaches a network, and
  `payroll.host.config` refuses to start a durable deployment that was not
  given one. That is the same discipline `payroll.edge.endpoints` keeps about
  CACAO verification — the boundary is named, the work is somebody else's, and
  nothing here pretends to have done it.

  It also means **the tests have no network**, which is what lets the restart
  and concurrency properties be measured rather than asserted.

  ## The four operations, and why they are these four

  kotobase's canonical surfaces are CID-native: an immutable block plane
  addressed by content, and a ref plane whose head moves under a
  compare-and-set (CLAUDE.md, ADR-2608039000 / ADR-260726). This protocol is
  those two and nothing else.

      put-block!   an immutable block, addressed by the hash of its bytes.
                   IDEMPOTENT by construction: the same bytes have the same
                   address, so a retried write is the same write.
      get-block    by CID.
      read-head    the ref's current CID, or nil for a ref nobody has written.
      cas-head!    move the ref from `expected` to `next`, or report a
                   conflict. NOT last-write-wins.

  There is no `delete`, no `list` and no `update`. A payroll ledger with a
  delete is a payroll ledger; `list` would let a caller enumerate another
  tenant's addresses; and an update is the thing content addressing exists to
  make impossible.

  ## Tenancy is an argument, not a convention

  Every operation takes a `tenant`. A transport is REQUIRED to scope by it —
  `payroll.kotobase.transport-test` asserts that one tenant cannot read
  another's block even given its CID, which is the failure that would
  otherwise be invisible: a content address is guessable from the content, so
  an unscoped block plane leaks whatever an attacker can reconstruct.

  ## What must never appear in `describe`

  The endpoint and the tenant may. A token may not, and
  `payroll.sensitive/log-violations` is what says so."
  (:require [payroll.sensitive :as sensitive]))

(defprotocol Transport
  (put-block! [t tenant cid block-bytes]
    "Store `block-bytes` (a vector of unsigned bytes) under `cid` for `tenant`.
    Returns `{:block/status :ok :block/cid cid}` or
    `{:block/status :refused :block/why …}`.

    Storing the same CID twice is a no-op that succeeds — the bytes are the
    address, so a duplicate write is not a conflict.")
  (get-block [t tenant cid]
    "`{:block/status :ok :block/bytes […]}`, or `{:block/status :missing}`.")
  (read-head [t tenant ref]
    "`{:head/status :ok :head/cid cid-or-nil}` — nil meaning `no ref yet`,
    which is different from a ref that could not be read.")
  (cas-head! [t tenant ref expected proposed]
    "Move `ref` from `expected` (nil = must not exist) to `proposed`.
    `{:cas/status :ok}` | `{:cas/status :conflict :cas/actual cid}` |
    `{:cas/status :refused :cas/why …}`.")
  (describe [t]
    "What this transport is, with no credential in it.

    MUST carry `:transport/durable?` — the deployment's own statement that
    what it stores outlives the process. `payroll.host.config` reports the
    durability of a `:kotobase` deployment from this and nothing else: the
    store's reconstruction is measured here, but whether the bytes are still
    there tomorrow is a property of the thing on the other side of this
    protocol, and a store that asserted it on the transport's behalf would be
    asserting something it cannot see.

    An absent `:transport/durable?` reads as FALSE, so a transport that
    forgot to say is not thereby durable."))

(defn ref-for
  "The ref name for one tenant's stream.

  Built here rather than by each caller so that the tenant is structurally
  part of every ref. A store that built its own ref names could build one
  without a tenant in it, and the two employers would then share a head."
  [tenant stream]
  (str "payroll/" tenant "/" (name stream)))

(defn describes-safely? [t]
  (empty? (sensitive/log-violations (describe t))))

(def max-cas-attempts
  "How many times a durable append retries a lost compare-and-set before it
  reports the conflict to the caller.

  Bounded, and small. A retry loop with no bound turns a permanently
  contended ref into a process that never returns and never says why — and
  the operator watching it has no way to tell that from slow. Five is enough
  to absorb the concurrency a single-employer payroll console produces
  (`payroll.store.kotobase-test` reaches this branch with two stores writing
  the same ref) and small enough that a genuine deadlock surfaces as an
  error an operator can read."
  5)
