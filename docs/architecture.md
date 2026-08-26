# Architecture

```text
                    ┌─────────────────────────────────────────┐
  browser ─────────▶│ payroll.host.jvm      (.clj, JDK only)  │
  (no JavaScript)   │  · reads env → payroll.host.config      │
                    │  · refuses to start on a bad deployment │
                    │  · owns the store's lifetime            │
                    │  · reads the DID from a trusted header  │
                    └───────────┬──────────────┬──────────────┘
                                │              │
                 ┌──────────────▼───┐   ┌──────▼────────────────┐
                 │ payroll.edge.    │   │ payroll.edge.         │
                 │   console        │   │   endpoints           │
                 │ (HTML, forms)    │   │ (EDN, 6 API routes)   │
                 └───┬────┬─────┬───┘   └──────────┬────────────┘
                     │    │     │                  │
        ┌────────────▼─┐  │  ┌──▼───────────┐      │
        │ payroll.ui.* │  │  │ payroll.mf.* │      │
        │ views/state/ │  │  │ import/      │      │
        │ a11y/render  │  │  │ reconcile    │      │
        └──────┬───────┘  │  └──────┬───────┘      │
               │          │         │              │
               │   ┌──────▼──────┐  │              │
               │   │ payroll.    │  │              │
               │   │  touroku    │  │              │
               │   │ (登録の admission) │           │
               │   └──────┬──────┘  │              │
               │          │         │              │
        ┌──────▼──────────▼─────────▼──────────────▼───────────┐
        │ payroll.meisai — one run as itemised FIGURES         │
        │   every line is a payroll.provenance value           │
        └───┬─────────────────────────────┬────────────────────┘
            │                             │
   ┌────────▼─────────┐        ┌──────────▼──────────────────┐
   │ payroll.artifact │        │ payroll.actor (langgraph)   │
   │  payslip         │        │  intake→advise→govern→      │
   │  chingin-daicho  │        │  decide→commit/hold         │
   │  deduction-      │        └──────────┬──────────────────┘
   │    summary       │                   │
   │  bank-transfer   │        ┌──────────▼──────────────────┐
   │  text (CSV/JSON) │        │ payroll.governor — 15 HARD  │
   └──────────────────┘        │  rules, 2 escalations       │
                               └───┬──────┬──────┬───────┬───┘
                                   │      │      │       │
                         ┌─────────▼┐ ┌───▼───┐ ┌▼─────┐ ▼
                         │ shakai-  │ │nenmatsu│ │chingin│ kotoba.taxlaw
                         │  hoken   │ │        │ │       │ kotoba.labor
                         └──────────┘ └────────┘ └───────┘ kotoba-lang/governor
                                   │
                         ┌─────────▼──────────────────────────┐
                         │ payroll.store  (protocol)          │
                         │  MemStore / DatomicStore           │
                         │  KotobaseStore ── Durable ─────┐   │
                         └────────────────────────────────┼───┘
                                                          │
   ┌──────────────────────────────────────────────────────▼──────────────┐
   │ 並行運用の証拠 — the only path that switches MoneyForward off        │
   │                                                                     │
   │   payroll.mf.reconcile ──report──▶ payroll.cutover/record-cycle!    │
   │                                            │                        │
   │                      (:cycle/reconciled? copied, never an argument) │
   │                                            ▼                        │
   │                            payroll.store.kotobase                   │
   │                     7 CAS chains · payload sealed · node readable   │
   │                                            │                        │
   │                                            ├──▶ payroll.projection. │
   │                                            │      schema → catalog  │
   │                                            │      → r2 (Iceberg)    │
   │                                            ▼                        │
   │                            payroll.cutover/evaluate                 │
   │             6 conditions, all MEASURED — no argument makes it true  │
   └─────────────────────────────────────────────────────────────────────┘
```

## The layering rule

**Only one namespace knows about a design system, and only one knows about a
socket.** `payroll.ui.render` requires `jp-go-dds`; `payroll.host.jvm` is the
only `.clj` in `src/`. Everything between them is portable `.cljc` taking data
and returning data.

That is what makes the accessibility invariants, the printable payslip, the
exports and the reconciliation testable without a browser, a server or a
stylesheet — and it is why `payroll.ui.a11y` checks a hiccup tree rather than
rendered markup.

## What is new in this slice, and why each exists

| namespace | the question it answers |
|---|---|
| `payroll.provenance` | where did this number come from, and may it be printed |
| `payroll.chingin` | what does `gross` account for, and what does it provably ignore |
| `payroll.meisai` | one run as itemised lines, built ONCE and read by four renderers |
| `payroll.touroku` | may this registration be written, without defaulting anything |
| `payroll.artifact.text` | deterministic CSV/JSON, where an unknown is never a blank |
| `payroll.artifact.*` | the four output documents, none claiming to be statutory |
| `payroll.mf.*` | reading another system's export without pretending to have read one |
| `payroll.ui.*` | an operator screen where unknown, held and confirmed look different |
| `payroll.host.config` | may this deployment listen on a socket at all |
| `payroll.host.jvm` | the socket |
| `payroll.rates` | which rate or 税額表 row applied in this month, and which are not transcribed |
| `payroll.rates.monthly-2026` | **generated** — the 月額表 as data, pinned by SHA-256 (`tools/import_nta_2026.clj`) |
| `payroll.store.kotobase` | can this ledger survive the process that wrote it |
| `payroll.kotobase.*` | envelope, blind index, transport contract — the payload is never written in the clear |
| `payroll.projection.*` | the analysis-side copy, and the permission this deployment does not have |
| `payroll.cutover` | may MoneyForward be switched off, and which of the six conditions is not met |
| `payroll.juminzei` | what a municipality decided this month, and where a transcribed 決定通知書 is kept |
| `payroll.operations` | the whole current state as one report an operator works down |

## The seven streams

`payroll.store/Store` is one protocol over seven streams, and
`payroll.store.kotobase` gives each one **its own CAS-guarded chain** — a CAS
lane is a serialisation point, and a single ref would make an operator
registering a timesheet and a run committing fight over a head neither of them
read.

| stream | written by | scoped by | deduplicated by |
|---|---|---|---|
| `clients` | `register-client!` | `:client-id` | `:client-id` |
| `contracts` | `register-contract!` | `:contract/employer` | `:contract/id` |
| `timesheets` | `register-timesheet!` | — (a timesheet names a worker) | — (a log) |
| `records` | `commit-record!` | `:client-id` | — (a log) |
| `ledger` | `append-ledger!` | `:client-id` | — (a log) |
| `cutover` | `commit-cutover-cycle!` | `:cycle/employer` | `:cycle/id` |
| `juminzei` | `register-juminzei-notice!` | `:notice/employer` | `:notice/id` |

The last two are their own streams rather than shapes on the ledger, and for
the same reason. A cutover cycle and a 住民税 notice are **not dispositions**:
nothing in the graph produces one, the governor never sees one, and no
proposal can cause one. Putting them on the ledger would make *count the
committed runs*, *count the parallel cycles* and *count the registered
notices* one query over one log — and the gate that decides whether
MoneyForward may be switched off would then be counting something an advisor
can cause.

**The 住民税 persistence seam is `payroll.juminzei/admit-registration` →
`register-notice!`.** The rule is written above the store, not inside it:
`admit-registration` is pure and decides between a first registration, a
retried transcription (`:duplicate`) and a correction; `register-notice!`
reads the employer's history, refuses when the chain cannot be walked to its
end, and persists **only** on `:ok`. Nothing is ever overwritten — a
correction is a new entry naming what it replaces, and what is current is
derived by `effective-notices`.

The storage layer gains no dependency on 住民税 for this. `:stream/key-fn` is
the keyword `:notice/id`, not a function that rebuilds an id out of a notice:
the id is computed exactly once, at admission, and travels with the record. A
key-fn that recomputed it here would be a second copy of that rule living in
the storage layer, and the copy that drifts is the one a retried registration
is deduplicated against.

## Three decisions worth stating

### The console is server-rendered and ships no script

CLAUDE.md's single-page rule has an exception (ADR-2608231200) for a page that
sits next to live credentials, and a payroll operator console is that page. The
invariants the rule protects are all kept — one shell, one stylesheet, views
generated from a table, no second app shell that can miss a design-system
migration — and what is given up is the mount. What is bought is that
`Content-Security-Policy: default-src 'none'` is truthful rather than
aspirational.

### `payroll.meisai` exists so four renderers cannot disagree

The payslip, the 賃金台帳 row, the deduction summary and the review screen all
read the same value. Four renderers each deciding for themselves what to do
about an unobserved 健康保険料 is four chances for one of them to print a zero.

### `payroll.touroku` is not a second governor

The governor decides about RUNS; `touroku` decides about REGISTRATIONS. Every
one of the governor's holds turns on a fact an operator registered, so an
admission layer allowed to invent one could dissolve a hold without anybody
having observed anything. It therefore defaults nothing, coerces nothing, and
refuses a key it does not read.

## Where the statutes live, and where they do not

Nothing in `src/payroll/` reads a law from a network. The readings live in:

- `kotoba-lang/taxlaw` — 所得税法 第百八十三条第一項, 第百九十条
- `payroll.shakai-hoken` — 健康保険法, 厚生年金保険法, 介護保険法,
  労働保険徴収法, 端数計算法 (quoted verbatim, retrieved 2026-08-18)
- `payroll.chingin` — **names** 労働基準法 第三十七条 and five others as
  **unread**, and prices nothing
- `payroll.warimashi` — 割増賃金 rates, read from two MHLW publications
- `payroll.juminzei` — 住民税 特別徴収, read from the 東京都 手引き. It is a
  **municipal guide and not the statute**: the 地方税法 articles it cites are
  named, and `:source/limit` says the text has not been retrieved from e-Gov
- `payroll.rates` — 保険料率 as dated rows, plus the 源泉徴収税額表（月額表）

The distinction in the third row is load-bearing. Naming an article as unread
is not enforcing a rule from it; no multiplier, rate or threshold appears
anywhere in `payroll.chingin`.

The last row carries a second distinction. A published rate table is not a
statute: 協会けんぽ's 料率 and the 国税庁's 税額表 are administrative
publications that are replaced in place, which is why every row there has an
effective window and the 税額表 is identified by the **SHA-256 of the workbook
it was read out of** rather than by the URL it was fetched from. Only
厚生年金's 18.3% comes from a statute (厚年法 第八十一条第四項), and that row
cites the same e-Gov law id `payroll.shakai-hoken` retrieved the article text
from.

## Cross-repo follow-ups

These belong in other repositories and were **not** done here.

| # | repository | what |
|---|---|---|
| 1 | `kotoba-lang/labor` | `wages-for` is four lines: hourly = rate × Σ hours, monthly = rate. It cannot express 割増賃金, allowances, 欠勤控除 or a mid-month start. `payroll.chingin` HOLDS on each of those rather than working around it, and `payroll.chingin-test/wages-for-really-does-ignore-what-this-namespace-says-it-ignores` measures the claim against the dependency so a change there fails here. |
| 2 | `kotoba-lang/worklaw` | already holds 労基法 32/34/35/36 thresholds and states that it does **not** price overtime. It is the natural home for 第三十七条 if the premium rates are ever read. Not adopted here because it needs `:worked/*` spans with timestamps and a calendar, and this actor has neither. |
| 3 | `kotoba-lang/taxlaw` | 別表第二 / 別表第五 are recorded as unread **there**, and remain so. This repository now holds the 月額表 (`payroll.rates.monthly-2026`, imported and SHA-256-pinned), but nothing on the payslip path was rewired to it — every 所得税 figure here is still `:declared`. 年末調整's annual amount needs 別表第五 and the 所得税額の速算表, which `payroll.artifact.gensen/annual-tables` lists as unread. |
| 4 | `cloud-itonami-isco-4311` | a chart of accounts must be registered before `payroll.shiwake` can produce anything but `:no-mapping`. The console's journal export serves that refusal today. |
| 5 | a new reading, or a vendor | the 全銀協 総合振込 record layout. `payroll.artifact.bank-transfer/zengin` refuses for every input and lists exactly what is missing. |
| 6 | this repository, next phase | 住民税 特別徴収 — the notice, the payslip line, the MoneyForward counterpart, the console form and the seventh chain all landed. What remains is outside this repository: 地方税法 itself is unread (only the 東京都 手引き was read, and only for 都内区市町村), no 納入書 様式 has been read, there is no eLTAX path and no 一括徴収 or 異動届 handling. |
| 7 | this repository, next phase | 源泉徴収票 / 給与支払報告書 / 法定調書合計表 / 納付書 — none exist. |
| 8 | `kotoba-lang/langchain-store` or a pod | `payroll.store/datomic-store` is a real protocol swap over an in-process DataScript, so it does not survive a restart. Pointing `langchain.db`'s `:db-api` at a real backend is the work; `payroll.host.config/durability` is where the claim changes, and a test that restarts a host is what will check it. |
