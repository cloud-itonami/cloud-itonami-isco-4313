(ns payroll.store
  "SSoT for the ISCO-08 4313 community payroll actor. Store is a
  protocol injected into the `payroll.actor` StateGraph — `MemStore` is
  the default, deterministic, zero-dep backend, and a Datomic /
  kotobase-backed implementation swaps in without touching the actor,
  the governor or the edge (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.store; the labor-domain records (contracts, timesheets,
  payroll) use `kotoba.labor`'s record shapes verbatim — this actor
  CONSUMES kotoba-lang/labor, it does not reinvent wage arithmetic.

  Domain:

    client     — a registered employer (:client-id, :name, and optionally
                 :jurisdiction — a `kotoba.taxlaw` path such as [:jp]).
                 Declaring a jurisdiction is what puts the run under the
                 governor's withholding rules; an employer that declares
                 none is not held, and the verdict's :extra says so.
    contract   — a `kotoba.labor/contract` record, registered under its
                 :contract/id. Every payroll-run draft MUST cite one.
                 May additionally carry the facts 所得税法 第百八十三条第一項
                 turns on, which are the OPERATOR's to register and not the
                 advisor's to propose:
                   :employment/recipient-residency  :resident|:non-resident
                   :employment/paid-in              :domestic|:overseas
                   :employment/payment-kind         defaults to
                                                    :employment-income
                 Unstated is not the article's exclusion — taxlaw treats a
                 missing residency as in scope, not as exempt.
    timesheet  — `kotoba.labor/timesheet` entries registered per worker;
                 the ONLY admissible basis for hourly wage computation
                 (no invented hours).
    record     — a committed operating record (payroll-run draft,
                 timesheet reconciliation, wage disbursement) — written
                 ONLY via commit-record!.
    ledger     — append-only audit trail of every proposal/verdict/
                 disposition, commit or hold.

  THREE backends implement this protocol and `payroll.store-contract-test`
  runs the same assertions against all of them — `MemStore`, `DatomicStore`
  and `payroll.store.kotobase/KotobaseStore`, the last of which is the only
  one that survives the process. Three properties of this actor
  make a silent disagreement between them especially expensive:

    - **Timesheets are the wage.** `kotoba.labor/wages-for` sums
      `:ts/hours` over whatever `timesheets-of` returns, and the governor
      recomputes `:gross` from it. A backend that dropped one entry, or
      leaked another worker's entries into the sum, would not produce an
      error — it would produce a DIFFERENT LAWFUL-LOOKING WAGE, and the
      governor would then hold the honest proposal for `:wage-mismatch`.
    - **The contract carries the statute's facts.**
      `:employment/recipient-residency` and `:employment/paid-in` are what
      所得税法 第百八十三条第一項 turns on. A backend that dropped
      `:employment/paid-in` would move a run from `:out-of-scope` to a
      withholding HOLD; one that dropped `:jurisdiction` off the employer
      would move it to `:not-declared` and stop checking the law at all.
      Both changes are silent and both only happen where the durable
      backend is deployed.
    - **The ledger is the only record of what was refused.** Its order is
      load-bearing: an audit trail in which the corrected run precedes the
      held one is not an audit trail."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Durable
  "The evidence a backend can offer about its own durability. Implemented by
  exactly one backend, and that is the point.

  `payroll.cutover/evaluate` — the gate that answers `may MoneyForward be
  switched off` — will not pass a store that does not satisfy this protocol.
  A `MemStore` holding three reconciled cycles satisfies every OTHER
  condition, and switching off the incumbent on the strength of it would mean
  the only surviving record of three months of parallel running is inside a
  process that ends when it ends. `satisfies?` is what makes that structural
  rather than a check somebody remembers to write: `MemStore` and
  `DatomicStore` do not implement it and therefore cannot supply the
  evidence, and no argument to `evaluate` can substitute for it.

  It returns evidence and not a boolean, because the gate has to be able to
  say WHICH part is missing — `the transport does not claim durability` and
  `two of seven chains cannot be walked` are different operator actions."
  (durability-evidence [s]
    "`{:evidence/mode :evidence/survives-process-restart? :evidence/readable?
       :evidence/why …}` — measured, never asserted."))

(defprotocol Store
  (client [s client-id])
  (contract-of [s contract-id])
  (timesheets-of [s worker])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-contract! [s contract])
  (register-timesheet! [s entry])
  (commit-record! [s record])
  (append-ledger! [s fact])
  ;; -------------------------------------------------------------------
  ;; 並行運用の証拠 (`payroll.cutover`) — a SIXTH stream, and its own one
  ;; rather than a shape on the ledger.
  ;;
  ;; A cutover cycle is not a disposition: nothing in the graph produces it,
  ;; the governor never sees it, and it is written by an operator recording
  ;; that a parallel month was reconciled and by whom. Putting it on the
  ;; ledger would make `count the committed runs` and `count the cycles`
  ;; the same query over the same log, and the gate that decides whether
  ;; MoneyForward may be switched off would then be counting a thing an
  ;; advisor can cause.
  ;;
  ;; `cutover-cycles` is scoped by employer for `ledger-of`'s reason: one
  ;; employer's parallel-run evidence is their wage bill in a different
  ;; shape.
  (commit-cutover-cycle! [s cycle])
  (cutover-cycles [s client-id])
  ;; -------------------------------------------------------------------
  ;; 特別徴収税額決定通知書 (`payroll.juminzei`) — a SEVENTH stream, and its
  ;; own one for the same reason the sixth is.
  ;;
  ;; A notice is a MUNICIPALITY's decision that an operator transcribed off a
  ;; piece of paper. Nothing in the graph produces one, the governor never
  ;; sees one, no proposal can cause one, and it is not an event in the life
  ;; of a payroll run — it is a fact that was true before any run cited it.
  ;; Putting it on the ledger would make 「登録された通知を数える」 and
  ;; 「run を数える」 one query over one log, and the 住民税 an employee has
  ;; deducted would then be countable from a stream an advisor can write to.
  ;;
  ;; Append-only, and nothing is ever overwritten: a 変更通知書 is a NEW
  ;; entry naming what it replaces (`:notice/replaces`), and what is current
  ;; is DERIVED by `payroll.juminzei/effective-notices`. That is what lets
  ;; the console show what a municipality CORRECTED rather than only what it
  ;; last said.
  ;;
  ;; `juminzei-notices` is scoped by employer for `ledger-of`'s reason: one
  ;; employer's notices are another employer's tax bill.
  (register-juminzei-notice! [s notice])
  (juminzei-notices [s client-id]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (contract-of [_ contract-id] (get-in @a [:contracts contract-id]))
  (timesheets-of [_ worker] (filterv #(= worker (:ts/worker %)) (:timesheets @a)))
  (records-of [_ client-id] (filterv #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-contract! [s contract]
    (swap! a assoc-in [:contracts (:contract/id contract)] contract) s)
  (register-timesheet! [s entry]
    (swap! a update :timesheets (fnil conj []) entry) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s)
  (commit-cutover-cycle! [s cycle]
    (swap! a update :cutover (fnil conj []) cycle) s)
  (cutover-cycles [_ client-id]
    (when (some? client-id)
      (filterv #(= client-id (:cycle/employer %)) (:cutover @a))))
  (register-juminzei-notice! [s notice]
    (swap! a update :juminzei (fnil conj []) notice) s)
  (juminzei-notices [_ client-id]
    (when (some? client-id)
      (filterv #(= client-id (:notice/employer %)) (:juminzei @a)))))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :contracts {}
                                    :timesheets [] :records [] :ledger []
                                    :cutover [] :juminzei []}
                                   seed)))))

;; ---------------------------------------------------------------------------
;; DatomicStore (langchain.db)
;;
;; The same protocol over a Datomic-API-compatible EAV store, so the backend is
;; a swap and not a rewrite (kintai, tehai and keihi are the three siblings on
;; this fleet's accounting plane that already did this). Pure `.cljc`: it runs
;; offline against langchain.db's in-process DataScript, and the SAME record
;; points at a real Datomic or a kotoba-server pod by swapping langchain.db's
;; `:db-api` (langchain.kotoba-db).
;;
;; The EDN-blob codec, the identity schema and the seq-keyed stream read/append
;; come from `kotoba-lang/langchain-store` and are NOT hand-rolled here; that
;; duplication (the same two-line codec in ~190 store.cljc files) is what the
;; library was extracted to end (ADR-2607141600).
;;
;; Why this exists at all: `MemStore` keeps the payroll ledger for exactly as
;; long as the process lives. A payroll actor whose record of what it refused
;; disappears on restart cannot answer the one question anybody asks it later —
;; why was this run not paid. The record stream has the same problem from the
;; other side: a payroll run that was committed and then forgotten is a payroll
;; run that can be committed twice, and the second time it is a second payment.
;;
;; THREE streams are seq-keyed and append-only here, not two. Timesheets are the
;; third, and they are the one the siblings do not have: they are the ONLY
;; admissible basis for an hourly wage, so losing one on restart silently
;; reduces somebody's pay, and the governor would then hold the CORRECT proposal
;; as a `:wage-mismatch`. `:timesheet/seq`, `:record/seq` and `:ledger/seq` are
;; all `:db.unique/identity`, so re-appending at an existing seq UPSERTS rather
;; than forking the log — which is exactly why `next-seq` has to be right, and
;; why the contract test asserts ORDER and not merely count.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (ls/identity-schema [:client/id :contract/id
                       :timesheet/seq :record/seq :ledger/seq :cutover/seq
                       :juminzei/seq]))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (client [_ client-id]
    (ls/blob-lookup conn :client/id :client/edn client-id))
  (contract-of [_ contract-id]
    (ls/blob-lookup conn :contract/id :contract/edn contract-id))
  (timesheets-of [_ worker]
    (filterv #(= worker (:ts/worker %))
             (ls/read-stream conn :timesheet/seq :timesheet/edn)))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %))
             (ls/read-stream conn :record/seq :record/edn)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-client! [s c]
    (ls/put-blob! conn :client/id :client/edn (:client-id c) c) s)
  (register-contract! [s c]
    (ls/put-blob! conn :contract/id :contract/edn (:contract/id c) c) s)
  (register-timesheet! [s entry]
    (ls/append-blob! conn :timesheet/seq :timesheet/edn
                     (next-seq conn :timesheet/seq) entry) s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn
                     (next-seq conn :record/seq) record) s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact
                     (next-seq conn :ledger/seq) fact) s)
  (commit-cutover-cycle! [s cycle]
    (ls/append-blob! conn :cutover/seq :cutover/edn
                     (next-seq conn :cutover/seq) cycle) s)
  (cutover-cycles [_ client-id]
    (when (some? client-id)
      (filterv #(= client-id (:cycle/employer %))
               (ls/read-stream conn :cutover/seq :cutover/edn))))
  (register-juminzei-notice! [s notice]
    (ls/append-blob! conn :juminzei/seq :juminzei/edn
                     (next-seq conn :juminzei/seq) notice) s)
  (juminzei-notices [_ client-id]
    (when (some? client-id)
      (filterv #(= client-id (:notice/employer %))
               (ls/read-stream conn :juminzei/seq :juminzei/edn)))))

(defn datomic-store
  "A DatomicStore over a fresh in-process langchain.db connection.

  In-process is the DEFAULT, not the guarantee. This function hands back a
  store whose durability is whatever langchain.db's `:db-api` is bound to;
  with the default in-process DataScript it survives no longer than MemStore
  does. What it buys unconditionally is that the swap is a swap — the actor,
  the governor and the edge are unchanged, and the contract test proves the
  two backends answer identically."
  []
  (->DatomicStore (d/create-conn schema)))

;; ---------------------------------------------------------------------------
;; Derived reads over the ledger
;;
;; Plain functions over the protocol rather than protocol methods, deliberately:
;; a backend cannot disagree with another about something neither of them
;; implements. They are still exercised against BOTH backends in the contract
;; test, because what they are really asserting is that `ledger` returned the
;; same thing in the same order — filtering an out-of-order ledger produces an
;; out-of-order history and nothing complains.
;; ---------------------------------------------------------------------------

(defn run-history
  "Every ledger entry citing `contract-id`, oldest first — the whole life of
  one contract's payroll runs (held, corrected, committed), not just the
  latest state.

  Returns `[]` for a contract id nobody has heard of. That is NOT the same as
  a contract with no payroll run, and callers must not render it as one: an
  empty history means the actor has no record of this contract at all.

  A nil `contract-id` returns nil rather than every entry that happens to
  cite no contract — and here that set is not empty, because a run held for
  `:no-contract` cites none. Matching nil against nil is how a lookup for
  `nothing` quietly becomes a lookup for `every refused run`."
  [store contract-id]
  (when (some? contract-id)
    (filterv #(= contract-id (:contract-id %)) (ledger store))))

(defn ledger-of
  "Every ledger entry belonging to `client-id`, oldest first. Same nil rule as
  `run-history`, for the same reason — and here the consequence of getting it
  wrong is one employer reading every other employer's payroll."
  [store client-id]
  (when (some? client-id)
    (filterv #(= client-id (:client-id %)) (ledger store))))
