# Maturity — what is true of this repository, column by column

This is the honest state of `cloud-itonami-isco-4313` as a payroll product for
a Japanese company. It exists because "implemented" and "in production" are
five states apart and this repository has, so far, only ever claimed the first.

Measured **2026-08-25**: 489 tests / 2,299 assertions green, lint clean at
error level.

## The columns

| column | what it means |
|---|---|
| **implemented** | the code exists and does the thing |
| **tested** | a suite asserts it, and the suite has been shown able to fail for the reason it names |
| **integrated** | it is reachable from the operator console / the HTTP surface, end to end |
| **deployed** | a process is running it somewhere other than a test |
| **production-verified** | it has produced a figure a real company acted on, and that figure was checked against another system |

**Nothing in this repository is deployed or production-verified. Not one row.**
The `deployed` column exists so that fact has somewhere to be written down.

## The matrix

| capability | implemented | tested | integrated | deployed | production-verified |
|---|:--:|:--:|:--:|:--:|:--:|
| PayrollAdvisor ⊣ PayrollGovernor (langgraph) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 源泉徴収 hold (所得税法 第百八十三条第一項) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 社会保険・労働保険 holds (rules 12–14) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 年末調整 assessment (所得税法 第百九十条) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 賃金の基礎 hold (rule 15, `payroll.chingin`) | ✅ | ✅ | ✅ | ❌ | ❌ |
| Registration admission (`payroll.touroku`) | ✅ | ✅ | ✅ | ❌ | ❌ |
| Operator console (Japanese, keyboard, no script) | ✅ | ✅ | ✅ | ❌ | ❌ |
| HTTP host (`payroll.host.jvm`, fail-closed config) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 給与支払明細書 (payslip, HTML + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 賃金台帳 (wage ledger, CSV + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 控除額集計 (deduction summary, CSV + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 振込データ (bank transfer, non-standard CSV) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 仕訳 handoff to isco-4311 | ✅ | ✅ | ⚠️ | ❌ | ❌ |
| MoneyForward import + reconciliation | ✅ | ✅ | ✅ | ❌ | ❌ |
| Durable storage across a restart | ❌ | ✅ | — | ❌ | ❌ |
| 全銀 総合振込 fixed-width file | ❌ | ✅ | — | ❌ | ❌ |
| 住民税 特別徴収 | ❌ | ✅ | — | ❌ | ❌ |
| 割増賃金 arithmetic (労基法 第三十七条) | ❌ | ✅ | — | ❌ | ❌ |
| 源泉徴収税額 arithmetic (別表第二 / 別表第五) | ❌ | ✅ | — | ❌ | ❌ |
| 健康保険・介護保険・雇用保険 rates | ❌ | ✅ | — | ❌ | ❌ |

### Reading the last six rows

`implemented ❌ / tested ✅` is not a contradiction and is the most important
shape in this table. Each of those is a capability this repository **does not
have and refuses to fake**, and the tests assert the refusal:

- storage does not survive a restart, and `payroll.host.jvm-test/the-durability-claim-matches-what-actually-happens`
  starts a host, commits a run over the socket, stops it, starts another and
  asserts the ledger is **empty**. The claim in
  `payroll.host.config/durability` is checked against a measurement rather
  than being a sentence somebody keeps up to date.
- the 全銀 record layout has not been read, so `payroll.artifact.bank-transfer/zengin`
  returns a refusal for every input and there is no function that returns
  bytes.
- 住民税 has no rule, no payslip line and no account here; the MoneyForward
  boundary maps its column to `:mf/no-counterpart` and **blocks the
  reconciliation** when the column carries a value.
- 割増賃金, the withholding tables and three of the four insurance rates are
  named as unread wherever a figure would have needed them.

### The one ⚠️

`仕訳` produces the `:draft-entry` request `cloud-itonami-isco-4311` accepts,
and the console can export it — but **every run converts to `:no-mapping`**,
because no chart of accounts is registered anywhere in this repository and
this actor does not choose one. The export serves that refusal rather than an
empty array. Making it produce entries needs an account mapping registered by
the employer; that is a cross-repo follow-up, not a gap here.

## Cutover gate — what must be true before MoneyForward is switched off

This is not a checklist of features. It is the evidence required, and none of
it can be produced by this repository alone.

### G1. Parallel operation for **three consecutive pay cycles**

Run MoneyForward and this actor over the same period, and reconcile every
cycle with `POST /console/mf`.

Two or three is the range the industry uses; **three** is the number here, and
the reason is in the code: `payroll.chingin` shows that a monthly contract's
gross is the contracted rate and the timesheets are never read. That figure is
right in an ordinary month and wrong in a month with a mid-month start, a
leaver, or unpaid leave. **Two ordinary months would agree and prove nothing
about the third.** At least one of the three cycles must contain a month that
is not ordinary, or the gate is not met — and if no such month occurs, the
parallel period extends until one does.

Each cycle must reach `:reconcile/reconciled? true`, which requires all six of:

1. the import was not rejected
2. **at least one run was compared** (the evidence floor — a report over zero
   runs has no differences to show)
3. every compared run agrees on every field
4. no row was unmapped
5. no unknown column carried a value
6. no `:mf/no-counterpart` column carried a value — which today means
   **住民税 must be zero, or this gate cannot be met at all**

### G2. Durable storage, actually wired

`payroll.host.config/durability` must stop returning
`:store/survives-process-restart? false`, and
`the-durability-claim-matches-what-actually-happens` must be inverted to
assert survival. Until then a restart loses every record of what was refused,
which is the one question anybody asks a payroll system afterwards.

### G3. The four unread rate sources, read

健康保険 (協会けんぽ 都道府県単位保険料率), 介護保険料率, 雇用保険率 and the
源泉徴収税額表 (別表第二 / 別表第五). Until these are read, every one of those
figures on a payslip is `:declared` — an operator's number that nothing here
checks. A cutover can proceed with that, but only if the operator knows it,
which is why every artifact prints the provenance next to the amount.

### G4. A bank file the bank accepts

Either the 全銀協 総合振込 layout is read and
`payroll.artifact.bank-transfer/zengin` is implemented, or the employer's bank
accepts the non-standard CSV, **verified by an actual test transfer** — not by
the file opening in a spreadsheet.

### G5. 住民税

Either a rule, a payslip line and an account are added here, or the employer
continues to handle 特別徴収 outside this system and that is written down.
G1's sixth condition makes the second option explicit rather than accidental.

### G6. 年末調整 and 法定調書

`:assess-year-end-adjustment` answers **whether** an adjustment is owed and
computes **no amount** — the year's correct tax comes from 別表, which is
unread. Nothing here produces 源泉徴収票, 給与支払報告書, 法定調書合計表 or the
納付書. A December cutover without these is a cutover into a month this system
cannot complete.

## Mutation coverage

`tools/mutations.edn` holds 81 entries. Fourteen were added with this slice
and each was run alone against a green baseline on 2026-08-25:

| invariant | reddened |
|---|---|
| a registered premium fact holds the run | 4 tests |
| every draft verdict reports the wage basis | 4 |
| a numberless provenance may not carry a number | 1 |
| a total refuses rather than under-reporting | 4 |
| an unknown figure is never an empty cell | 4 |
| an empty accessibility scan is not a pass | 1 |
| state is never conveyed by colour alone | 2 |
| a reconciliation over nothing is not a pass | 1 |
| a held figure is never scored as agreement | 1 |
| no MoneyForward column is marked verified | 1 |
| registration does not default a coverage flag | 2 |
| a non-loopback bind needs an acknowledgement | 1 |
| the console refuses a cross-origin POST | 1 |
| the payslip does not claim to be a statutory form | 1 |

**All fourteen kill.** Two pre-existing entries whose `:find` anchors this
change moved were repaired and re-measured, and both kill — a mutation whose
anchor is absent is scored UNMEASURED rather than passing, which is how the
staleness was caught rather than silently tolerated.

As `tools/mutations.edn`'s own header says: a clean run means every invariant
**listed there** is measured, never that this actor is measured.

## What a green test run here does and does not mean

It means every invariant the suite names is measured. It does not mean this
actor is measured — `tools/mutations.edn` says the same thing about the
mutation table, and for the same reason. The list above of things this
repository does not have is the more useful document.
