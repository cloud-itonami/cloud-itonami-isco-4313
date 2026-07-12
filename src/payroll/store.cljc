(ns payroll.store
  "SSoT for the ISCO-08 4313 community payroll actor. Store is a
  protocol injected into the `payroll.actor` StateGraph — `MemStore` is
  the default, deterministic, zero-dep backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store; the labor-domain records
  (contracts, timesheets, payroll) use `kotoba.labor`'s record shapes
  verbatim — this actor CONSUMES kotoba-lang/labor, it does not
  reinvent wage arithmetic.

  Domain:

    client     — a registered employer (:client-id, :name)
    contract   — a `kotoba.labor/contract` record, registered under its
                 :contract/id. Every payroll-run draft MUST cite one.
    timesheet  — `kotoba.labor/timesheet` entries registered per worker;
                 the ONLY admissible basis for hourly wage computation
                 (no invented hours).
    record     — a committed operating record (payroll-run draft,
                 timesheet reconciliation, wage disbursement) — written
                 ONLY via commit-record!.
    ledger     — append-only audit trail of every proposal/verdict/
                 disposition, commit or hold."
  )

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
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (contract-of [_ contract-id] (get-in @a [:contracts contract-id]))
  (timesheets-of [_ worker] (filter #(= worker (:ts/worker %)) (:timesheets @a)))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
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
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :contracts {}
                                    :timesheets [] :records [] :ledger []}
                                   seed)))))
