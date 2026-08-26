# Maturity — what is true of this repository, column by column

This is the honest state of `cloud-itonami-isco-4313` as a payroll product for
a Japanese company. It exists because "implemented" and "in production" are
five states apart and this repository has, so far, only ever claimed the first.

Measured **2026-08-26**: 574 tests / 34,202 assertions green; `clj-kondo`
reports **0 errors and 1 warning** — a pre-existing unused binding in
`payroll.governor`, unrelated to anything in this slice.

The assertion count jumped an order of magnitude in this slice and that is
not a claim about coverage. The 源泉徴収税額表 tests walk RANGES — every band
edge, the whole amount domain in both columns, dependant counts from 0 to 39 —
because a table lookup that was only ever tried at the amounts somebody typed
is the exact defect the empty table existed to prevent. Assertions are cheap;
what they are worth is decided by what they walk.

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
| 運用の現況 screen (`/console/operations`, + the 住民税 通知 form) | ✅ | ✅ | ✅ | ❌ | ❌ |
| HTTP host (`payroll.host.jvm`, fail-closed config) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 給与支払明細書 (payslip, HTML + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 賃金台帳 (wage ledger, CSV + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 控除額集計 (deduction summary, CSV + JSON) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 振込データ (bank transfer, non-standard CSV) | ✅ | ✅ | ✅ | ❌ | ❌ |
| 仕訳 handoff to isco-4311 | ✅ | ✅ | ⚠️ | ❌ | ❌ |
| MoneyForward import + reconciliation | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Content addressing (SHA-256 / raw CIDv1)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **Durable store (`payroll.store.kotobase`)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **全銀 総合振込 fixed-width + CSV (PayPay Bank)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **住民税 特別徴収 (notice-driven)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **割増賃金 arithmetic (労基法 第三十七条)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **健康保険・介護保険・厚生年金・雇用保険 rates** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **法定調書 artifacts (源泉徴収票 ほか)** | ✅ | ✅ | ⚠️ | ❌ | ❌ |
| **Cutover evidence + gate (`payroll.cutover`)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| **R2 Data Catalog projection** | ✅ | ✅ | ⚠️ | ❌ | ❌ |
| **源泉徴収税額表 月額表 (231 帯, generated + pinned)** | ✅ | ✅ | ✅ | ❌ | ❌ |
| 源泉徴収税額 端数処理 (率が印字される二区間) | ❌ | ✅ | — | ❌ | ❌ |
| 源泉徴収税額表 日額表 / 賞与の算出率の表 | ❌ | ✅ | — | ❌ | ❌ |
| 年末調整 amount (別表第五 + 速算表) | ❌ | ✅ | — | ❌ | ❌ |
| 納付書 | ❌ | ❌ | — | ❌ | ❌ |

### What changed on 2026-08-26, and what did not

Six rows moved from `implemented ❌ / tested ✅` to `implemented ✅`, and one
row (住民税) moved to `integrated ✅`. Each moved because a source was actually
read and the reading is in the code with its URL and its retrieval date. One
bullet — **法定調書 provenance** — is not a row moving: it is a reading landing
under rows that were already `implemented ✅`, and it moves no column:

- **Durable storage.** `payroll.store.kotobase` is a `Store` over an
  injected kotobase transport: seven CAS-guarded chains of immutable
  content-addressed blocks, sealed through an encryption seam that has no
  default. Two independently constructed stores over one transport read back
  the same records in the same order (`payroll.phase2-test/
  a-second-store-reconstructs-what-the-first-wrote`), a lost CAS
  acknowledgement does not write twice, and two writers racing for one head
  both land. **`deployed` is still ❌ and the reason is now different**: this
  repository ships no transport that reaches a network, so a deployment must
  inject one, and `payroll.host.jvm/start!` refuses `PAYROLL_STORE=kotobase`
  without it.
- **全銀 総合振込.** The layout was read from PayPay Bank's published
  specification (`data.pdf`, revised 2025-03-06) and transcribed field by
  field. Every record encodes to exactly 120 bytes in Shift_JIS, measured by
  encoding rather than asserted.
- **住民税 特別徴収.** Read from the 東京都・都内区市町村 手引き (令和８年１月).
  It is notice-driven: the municipality decides the amount and this actor
  registers twelve monthly figures and refuses everything else.
- **割増賃金.** Rates read from two MHLW publications. Six of the seven
  categories are quoted; the seventh (60時間超＋深夜) is marked as derived by
  addition and travels marked.
- **Insurance rates.** 協会けんぽ 東京 2026 (健康 9.85%, 介護 1.62%), 厚年法
  (18.3%), MHLW FY2026 雇用保険 (labour 5/1000, employer 8.5/1000). **東京 only** —
  another prefecture answers `:prefecture-not-transcribed`.
- **法定調書 artifacts + cutover evidence + R2 projection.** Contracts,
  refusals and previews, with the tests below.
- **法定調書 provenance.** The 国税庁「令和8年分 給与所得の源泉徴収票等の法定
  調書の作成と提出の手引」 was read — the index page and 第1章 (pp.1–2) — and
  what it prints is recorded as printed: the 提出期限 **令和9年2月1日（月）**
  (the guide's own printed date; this actor holds no calendar and does not
  re-derive it from 1月31日), the 提出先 split between 所轄税務署 and 各市区町村,
  the **four** 提出方法 (e-Tax / 認定クラウド等 / 光ディスク等 / 書面) with the
  光ディスク等 file extensions, and the **30-枚 basis** for compulsory e-Tax 等
  submission — lowered from 100 for filings made from 令和9年1月, and judged
  **per kind of 法定調書**, so no single count over everything answers it. Four
  令和8年分 amendments are recorded, including the **源泉徴収票のみなし提出の
  特例** (from 令和9年1月1日 a 給与支払報告書 filed with the municipality is
  deemed to be the 税務署's 源泉徴収票, so the 税務署 copy no longer has to be
  produced) — which changes what a *complete* submission is, not what this
  repository can submit. The 手引 URL and the 第1章 PDF's SHA-256 are recorded;
  **the PDF is not ingested**, and everything from 第2章 onward is listed in
  `:source/not-read`.
- **源泉徴収税額表（月額表）.** All 231 bands, the nine printed threshold
  rows, the eleven excess-rate segments and the 7人超 deduction. Not
  transcribed by hand — `tools/import_nta_2026.clj` READ them out of the
  国税庁 workbook, which is pinned by SHA-256, and wrote
  `src/payroll/rates/monthly_2026.cljc`. `payroll.rates/withholding-table`
  reads that file; the two vectors are `identical?` and a test asserts it,
  so a regeneration cannot leave them disagreeing.

  The reason the previous slice held an EMPTY table was that a partial
  transcription answers for the salaries somebody typed and refuses for the
  rest. That objection is answered by there being no subset: the suite walks
  every band edge and asserts the rows are contiguous from 105,000円 to the
  first threshold, and sweeps the whole amount domain in both columns
  asserting that not one input reaches `:band-not-transcribed`.

  **Two segments still refuse, and they refuse by SEGMENT and not by value.**
  Above 740,000円 (甲) and below 105,000円 (乙) the workbook prints a RATE
  instead of an amount, and the 端数処理 for the fraction of a yen it
  produces is printed nowhere in what was read. `withhold` returns the exact
  ratio as `:withhold/exact` and answers `:rounding-not-transcribed`. It
  refuses even at 100,000円, where 3.063% is exactly 3,063円 and no rounding
  is needed — answering the amounts that divide evenly and refusing the rest
  is the same failure as a partial table.

**What did NOT change:**

- **The 年末調整 amount is still refused**, and reading the 手引 strengthened
  the reason rather than weakening it. `year-end-amount` answers
  `:annual-table-not-transcribed` for every input. The 手引's own 目次 carries
  neither 別表第五 nor the 所得税額の速算表, so *having read the guide is not
  having read those tables* — and 第1章 4(2) adds a 生命保険料控除 特例 for
  households with a 扶養親族 under 23, applying to 令和8年分 and 令和9年分,
  which this actor does not encode. Transcribing the two tables would
  therefore still not be enough to compute 課税所得.
- **Nothing is deployed and nothing is production-verified.** Not one row.
- **The three real MoneyForward parallel cycles do not exist.** The evidence
  model and the gate do; no cycle has been recorded from a real export.
- **No live kotobase write and no live R2 write has been made from this code.**

### The ⚠️ rows, and what moved on 2026-08-26

`仕訳` is unchanged: every run converts to `:no-mapping` because no chart of
accounts is registered.

**A 運用の現況 screen now exists** — `/console/operations`, generated from the
same `payroll.operations/report` that `GET /api/operations` serves, and in the
navigation because the nav is generated from the view table. It is read-only
**except for one form** — `POST /console/juminzei-notice`, the 住民税 通知
registration described below; nothing else on it writes.
It renders the resident-tax schedule and registered notices, the 割増賃金
rates and their exclusions, the rate/table versions with the SHA-256 of the
edition, the artifacts with a download link per declared format, the
MoneyForward cycles and the 0/3–3/3 cutover progress with every named blocker,
the store's chains, and the projection's status **and** its preflight. That
moved 割増賃金, the insurance rates, the cutover gate and the 月額表 from
`integrated ❌` to `✅`: they are now reachable from the console and not only
from code and tests.

**全銀 総合振込 is `integrated ✅`, and the earlier claim that no download
existed was wrong.** `GET /console/export?kind=zengin&format=fixed-width`
serves Shift_JIS bytes with the charset on the content type — it has been in
`payroll.edge.console/export` and on the exports screen throughout this
slice, and the 全銀 artifact declares `[:fixed-width :csv :json]`, all three of
which `payroll.edge.console-test` exercises. What is *not* done is the half
that was never this repository's to do: **no test transfer has been made**, so
`deployed` and `production-verified` stay ❌ and the screen keeps saying
`銀行がこのファイルを受理すること: 未実施`.

**住民税 moved to `integrated ✅` on 2026-08-26. 法定調書 and R2 stay ⚠️:**

- **住民税 — registered, persisted, on the console, on the report and
  projected.** The store half landed first:
  `payroll.juminzei/admit-registration` decides whether a transcription is a
  first registration, a retry (`:duplicate`) or a correction,
  `payroll.juminzei/register-notice!` persists it, and `payroll.store/Store`
  grew a notice stream — a **seventh kotobase chain**, which is why
  `六つの chain` became `七つの chain` in `payroll.store.kotobase/health` and
  in `payroll.cutover`'s `:durable-read-back` reason. It inherits
  complete-chain fail-closed reads, the keyed blind-index idempotency tag, the
  bounded CAS retry and employer scoping by construction rather than by
  re-implementation. Nothing is ever overwritten: a correction is a new entry
  naming what it replaces, and what is current is derived by
  `payroll.juminzei/effective-notices`, so the console can show what a
  municipality corrected.
  The edge half landed with it. `POST /console/juminzei-notice` takes a
  transcribed notice off 「運用の現況」 — twelve labelled 月割額 in a
  `<fieldset>`, no defaulted 通知の種類, values kept on a refusal — behind the
  same three gates and the same `Origin` check as every other write. It is
  Post/Redirect/Get: `:ok` and `:duplicate` answer **303** to
  `/console/operations?notice=registered|duplicate` and a refusal does **not**
  redirect, because a redirect would discard twelve figures somebody just read
  off a piece of paper. **Nothing transcribed goes in the URL** — the landing
  page reads the counts and the coverage back out of the store instead.
  `payroll.operations/report` reads the notices off the store rather than
  taking them as an injected option, which is the defect that made this screen
  say 「決定通知書が一件も登録されていない」 to every deployment that had
  registered notices — nothing outside the test suite ever supplied the
  option. **No amount is on that screen**: not a 月割額, not the 年税額, not on
  the confirmation, and `payroll.projection.schema`'s third table
  (`resident_tax_notice_projection`) carries no municipality, no 通知書番号 and
  no amount either.
  **This change cost exactly what the previous edition of this passage said it
  would**: a seventh chain, and the `六つの chain` sentence moving in two other
  namespaces. Both were paid.
  `deployed` and `production-verified` stay ❌ with everything else — no
  notice has been registered anywhere but a test, and registration is
  **console-only**: there is no API route for it, which is a real remaining
  gap and not a rounding of one. The second gap this passage used to name —
  the payslip line reading `:employment/resident-tax-obligation` off a
  contract that could not carry it — is **closed** (2026-08-26). The key is
  in `payroll.touroku/contract-fields`, the contract form has the control,
  and a contract classified `:special-collection` whose notice covers the
  month produces a `:declared` 住民税 line carrying the municipality's name
  and the notice's own 月割額. `integrated ✅` is claimed for registration,
  persistence, the console, the report and the projection — which is what
  those five words mean, and not more.
- **法定調書 — previews are reachable through the report's artifact section,
  the amounts are still refused** (`:annual-table-not-transcribed`). The
  国税庁 令和8年分 手引 was read on 2026-08-26 — the index page and 第1章
  (pp.1–2), URL and SHA-256 recorded, PDF not ingested — and reading it made
  `:artifact/statutory-form? false` **more** true, not less: 第1章 4(4) says
  the 様式 changed with the 令和8年9月 国税システム renewal, so what was 「読んで
  いない様式」 is now 「読んでいない、しかも新しい様式」. Everything from 第2章
  onward, including every 様式 and the 納付書, is listed in `:source/not-read`.

- **R2 — the console shows the preflight, which makes no request.** The
  projection itself has never been built: `:projection-health` is nil in every
  deployment this repository ships, because no catalog driver is constructed,
  and the screen renders `未設定` rather than a pass.

## Cutover gate — what must be true before MoneyForward is switched off

`payroll.cutover/evaluate` now computes this rather than a reader checking
it. **Six** conditions (`payroll.cutover/gates`), `0/3` to `3/3` progress, and
a named reason for every one that holds. The seventh kotobase chain did not
add a seventh condition — chains and gate conditions are different things, and
counting one as the other is the error this slice most invites.

### G1. Parallel operation for three consecutive pay cycles — MODELLED, NOT MET

`payroll.cutover/admit-cycle` takes a real `payroll.mf.reconcile` report and
an approval and refuses a report that compared nothing. `:cycle/reconciled?`
is copied from the report and is never an argument. A month classified
`:exceptional` needs a reason. A gap in the periods resets the run.

**Zero cycles have been recorded from a real export.** The gate reads `0/3`.

The sixth condition — *no `:mf/no-counterpart` column carried a value* —
used to make this gate **unmeetable by construction**, because 住民税 had no
counterpart and every real export carries it. It has one now.

### G2. Durable storage — MET IN CODE, NOT DEPLOYED

`payroll.host.config/durability` no longer has only false branches. For
`:kotobase` it reports the **transport's** own `:transport/durable?` and
refuses to answer on its behalf: an absent declaration reads as false. The
suite measures both directions — the two ephemeral modes still lose the
ledger across a restart, and the kotobase store reconstructs.

### G3. The rate sources — FOUR OF FOUR READ, WITH TWO SEGMENTS REFUSED

健康保険 (東京), 介護保険, 雇用保険 and now the 源泉徴収税額表（月額表）—
the last one imported from a SHA-256-pinned 国税庁 workbook rather than
typed. `payroll.rates/withhold` answers 105,000〜740,000円 in both columns,
answers 0 below the floor in 甲, and answers the printed threshold rows —
all nine in 甲, and the two the workbook prints in 乙 (740,000円 and
1,710,000円; the other seven print nothing there). Everything above the
last printed row, and 乙 below 105,000円, refuses
`:rounding-not-transcribed`.

**Every 所得税 figure on a payslip is still `:declared`.** Nothing on the
payslip path was rewired to call `withhold`: `payroll.meisai` still carries
the operator's own figure with its provenance, and this slice deliberately
did not change that. Having a table and computing a payslip from it are two
decisions, and only the first was made here.

日額表 and 賞与に対する源泉徴収税額の算出率の表 remain unread and are named
in `:table/not-transcribed` rather than approximated from the 月額表.

### G4. A bank file the bank accepts — HALF DONE

The layout is read and the bytes are produced. **No test transfer has been
made**, which is the other half and is not this repository's to do.

### G5. 住民税 — REGISTERED, KEPT AND REPORTED; COMPUTED BY NOBODY HERE

A notice registers through a form on 「運用の現況」, is admitted by
`payroll.juminzei`, persists on the seventh chain, is read back onto the
report, and is projected (without any amount) as
`resident_tax_notice_projection`. There is a payslip line —
`payroll.meisai`'s sixth deduction — fed by `payroll.juminzei/assess`, though
see the last gap below for why its figure is not yet reachable. The
MoneyForward column has a counterpart, so a reconciliation no longer has an
unclearable blocker. **What this actor still does not do is compute a 住民税
figure** — the amount is the municipality's, and `deduction-figure` marks it
`:declared`, never `:derived`.

There is deliberately **no governor rule**: a run whose 住民税 was never
assessed carries an `:unknown` line and `payroll.meisai/payable?` refuses it,
so no bank file can be built for it — while an employer who handles 特別徴収
outside this system is not held on every run. The refusal is at the payment
boundary, where being wrong about an employer who never wanted the line costs
nothing.

#### Corrections, because a municipality reissues

Nothing is ever overwritten and nothing can be deleted. A 変更通知書, a
corrected 決定通知書 or a re-issued paper is registered as a **new entry** that
raises 改訂番号 and names the notice it replaces (`:notice/replaces`, by
`notice-id`). `admit-registration` refuses a revision that names nothing
(`:revision-without-replacement`), one that names a notice this employer has
not registered (`:replacement-not-registered`), and a **second** notice
replacing an already-replaced one (`:replacement-already-replaced`) — that
last is what stops a correction history from forking silently.

Re-submitting an identical transcription answers `:duplicate`, writes nothing,
and is told apart from `:ok` on the screen; the same `notice-id` with
different content is `:conflicting-content` and is refused. A registration
against a history that cannot be walked to its end is `:history-unreadable`
and writes nothing — an unreadable history is not an empty one.

What is *current* is derived (`effective-notices`: every notice no other
notice replaces), which both `assess` and `coverage` read through, so the
payslip and the operations screen cannot disagree about the same month. A
mid-year 変更通知書 that replaces a full-year 決定通知書 while carrying only
10月 onward leaves 6月–9月 **uncovered**, and `coverage` reports exactly that:
the replaced paper is not in force and no figure is carried forward off it.

#### What is still not done

- **地方税法 itself is unread.** The reading is the 東京都・都内区市町村
  事務手引き (令和８年１月). Article numbers are the ones that guide cites; the
  statute text has not been retrieved from e-Gov the way
  `payroll.shakai-hoken`'s four were, and `:source/limit` says so.
- **Only 都内区市町村 are covered by the guide that was read.** A notice from
  anywhere else registers identically — the shape is national — but the due
  dates and the 納期の特例 recorded here are that guide's.
- **No 納入書.** The 様式 has not been read. `municipality-payable` groups what
  is payable by 区市町村 and states the 納期限 rule, and produces no document.
- **No eLTAX and no 給与支払報告書 filing.** Nothing here submits anything.
- **No 一括徴収 handling.** The two cases (6/1–12/31 on request, and
  翌1/1–4/30 under 地方税法 第321条の5第2項 even without one) are recorded as
  facts read from the guide and are not implemented.
- **No 異動届.** The 翌月10日 deadline is recorded; nothing produces the form.
- **No API route.** Registration is console-only.
- **Registering the classification does not classify anybody by itself.**
  `:employment/resident-tax-obligation` **became registerable on 2026-08-26**
  and the seam it used to block is joined: `payroll.edge.console/run-of`
  reads it off the contract as `assess`'s `:obligation`, the key is one of
  `payroll.touroku/contract-fields`, the contract form carries a select whose
  FIRST option is 未登録, and a classified contract with a covering notice
  produces a `:declared` payslip line whose amount is the notice's 月割額 and
  whose source is the municipality. What remains is that **somebody has to
  classify each employee**: an unregistered contract is neither 対象外 nor a
  default to 特別徴収, `assess` answers `:municipality-not-declared`, and the
  line stays `:held`. `payroll.touroku/registration-gaps` surfaces that as a
  named gap so it is a work queue rather than a silent hold. Nothing here
  computes a 住民税, and `payroll.meisai/payable?` still refuses a run whose
  line was never assessed.
- **No date is ever resolved.** 「土・日曜日、又は祝日の場合は、その次の平日」
  is a rule about a calendar this actor does not have, so a 納期限 is reported
  as the 10th with that rule attached.
- **Nothing verifies that a notice is genuine.** A registered notice is an
  operator's transcription of a piece of paper.

### G6. 年末調整 and 法定調書 — CONTRACTS, NOT AMOUNTS

三つの artifact (源泉徴収票 / 給与支払報告書 / 法定調書合計表) have data
contracts, completeness checks and previews. The **amount is still refused**.
納付書 does not exist.

**The 月額表 landing did not change this, and that is a decision and not an
oversight.** `payroll.artifact.gensen/year-end-amount` used to fall through
to `payroll.rates/withhold`, which was safe only because the table was
empty — the moment the 231 bands landed it would have started answering. It
does not consult the monthly table at all now. The year's tax needs 別表第五
（年末調整等のための給与所得控除後の給与等の金額の表）and the 所得税額の
速算表, both listed unread in `gensen/annual-tables`, and the refusal is
`:annual-table-not-transcribed`. Feeding a year's gross into a table of
monthly withholding amounts produces a plausible number that is not the
year's tax, and it would land on a 源泉徴収票 next to a 過不足額 the employer
actually pays or recovers.

## Mutation coverage

`tools/mutations.edn` holds **104 entries** — measured by parsing the file as
EDN rather than by grepping it, and 104 distinct `:id`s. The count in this
document was `81` while the file already held `94`, because it had been copied
from the previous slice and never re-derived; it is re-derived here, which is
the only way this number is worth anything.

The thirteen added earlier on 2026-08-26 cover the durable store, the
projection and the 全銀 bytes:

`:a-block-is-verified-against-the-address-it-was-asked-for` ·
`:an-append-against-an-unwalkable-chain-does-not-happen` ·
`:an-unreadable-chain-refuses-rather-than-returning-what-it-got` ·
`:an-unstable-blind-index-is-refused-at-construction` ·
`:the-blind-index-key-is-not-the-envelope-key` ·
`:the-idempotency-tag-comes-from-a-keyed-provider` ·
`:the-cutover-gate-needs-a-store-that-can-testify-to-its-durability` ·
`:the-cutover-read-back-needs-every-chain-walked-to-its-end` ·
`:a-projection-over-nothing-is-not-a-successful-projection` ·
`:a-driver-that-cannot-answer-existing-keys-is-not-written-to` ·
`:a-projected-row-refuses-rather-than-dropping-an-identifier` ·
`:the-host-writes-zengin-bytes-without-re-encoding-them` ·
`:a-character-with-no-single-byte-encoding-is-refused`

The **ten added with the 住民税 slice** cover the notice stream, its
projection and two claims this document had listed as uncovered:

`:a-retried-registration-is-one-registration` ·
`:a-correction-must-name-what-it-replaces` ·
`:the-same-notice-id-with-different-content-is-refused` ·
`:a-notice-is-not-appended-against-an-unreadable-history` ·
`:a-decision-notices-twelve-months-must-sum-to-its-annual-total` ·
`:the-notice-chain-is-employer-scoped` ·
`:a-projected-row-refuses-an-undeclared-column` ·
`:a-notice-projection-over-nothing-does-not-reach-the-ok-path` ·
`:redaction-counts-what-it-dropped` ·
`:an-untranscribed-prefecture-fails-closed`

Nothing was removed. What is **still uncovered** is stated rather than
implied: the console screen that renders the operations report,
`payroll.warimashi`, `payroll.rates` and the generated 月額表 have **no
mutation entry**. The resident-tax registration claim and the report's
redaction now do — which narrows the list rather than clearing it, and the
table's own header sentence still applies with full force: a clean run means
every invariant **listed there** is measured, never that this actor is
measured.

The invariants most worth adding next, in order:

| invariant | namespace |
|---|---|
| an unassessed 住民税 is `:unknown`, never zero | `payroll.meisai` |
| a superseded notice does not decide a month | `payroll.juminzei` |
| `:cycle/reconciled?` is copied from the report, never an argument | `payroll.cutover` |
| a report handed no store health says `not-reported`, never `readable` | `payroll.operations` |
| an unreadable notice history counts nothing rather than counting zero | `payroll.operations` |
| a band the 月額表 does not transcribe refuses rather than interpolating | `payroll.rates` |

## What a green test run here does and does not mean

It means every invariant the suite names is measured. It does not mean this
actor is measured — `tools/mutations.edn` says the same thing about the
mutation table, and for the same reason. The list above of things this
repository does not have is the more useful document.
