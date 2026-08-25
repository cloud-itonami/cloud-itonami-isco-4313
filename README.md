# cloud-itonami-isco-4313

**Community Payroll Service** — the ISCO-08 4313 (Payroll Clerks)
actor, an ISCO **Wave 0 (cognitive substrate)** occupation per
ADR-2607121000: pure-cognitive work, the LLM-first wave, no robotics
gate.

**Maturity: `:implemented`, and nothing in it is deployed or
production-verified.** The full column-by-column matrix, and the cutover gate
that would have to be met before MoneyForward is switched off, are in
[`docs/maturity.md`](docs/maturity.md) — read that before reading anything
below as a claim about production. **489 tests / 2,299 assertions green,
lint clean** (2026-08-25).

PayrollAdvisor ⊣ PayrollGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
**Consumes `kotoba-lang/labor`** (contracts / timesheets / wages / payroll)
per the fleet's capability-library-wrapping convention (same as
cloud-itonami-isic-9700) — wage arithmetic is never reinvented here.

Around it, as of 2026-08-25: a Japanese **operator console** that ships no
JavaScript, a **real HTTP host** that refuses to start on a bad deployment,
four **output artifacts** none of which claims to be a statutory form, and a
**MoneyForward import boundary** that has never seen a real MoneyForward
export and says so on every screen it appears on.

- [`docs/maturity.md`](docs/maturity.md) — the matrix and the cutover gate
- [`docs/architecture.md`](docs/architecture.md) — the layering, and the
  cross-repo follow-ups this slice deliberately did not do
- [`docs/operator-ja.md`](docs/operator-ja.md) — 運用コンソールの手引き

The payroll-specific HARD invariant: **the governor recomputes wages
deterministically via `kotoba.labor/wages-for` from the REGISTERED
contract and timesheets, and holds any proposal whose gross/net
disagrees — fair pay is arithmetic, not opinion.** An advisor (human
or LLM) cannot get an invented amount approved, at any confidence.
Also HARD: unregistered employer, missing/foreign/invalid contract
(no invented employment), `:effect` other than `:propose`, an
uncatalogued declared jurisdiction, a run that does not account for
withheld income tax where the law requires it (see 源泉徴収 below), a run
that says nothing about the four 社会保険 contributions (see 社会保険 below),
and — since 2026-08-25 — **a run whose gross provably ignores a fact an
operator registered** (see 賃金の基礎 below). Escalations (always human
sign-off): `:disburse-wages` (real fund movement), low confidence (< 0.6).

**A second governed op, `:assess-year-end-adjustment`**, reads 所得税法
第百九十条 over registered facts and answers whether a 年末調整 is owed —
**without computing the over/under, which nothing this repo has read can
compute**. Four of its nine answers are the absence of an answer and every one
of them is HARD. See 年末調整 below.

## 仕訳 — an approved payroll run becoming a journal entry

Deciding is not bookkeeping. **A payroll run that was approved and never
became an entry is wages nobody's books show, and withholding nobody's books
owe.** `payroll.shiwake/entry-request` produces the `:draft-entry` request
[`cloud-itonami-isco-4311`](https://github.com/cloud-itonami/cloud-itonami-isco-4311)
accepts.

### Three lines, not two

An expense claim is one debit and one credit. Payroll is not:

```text
借方  給料手当     gross
貸方  預り金       income tax withheld   ← 所得税法 第百八十三条第一項
貸方  未払金       net
```

第百八十三条第一項 obliges the payer to 徴収し … 国に納付しなければならない.
Withheld tax is therefore **not a reduction of the expense** — it is a
liability owed to the state until remitted. Netting it into one credit line
would make that liability vanish from the balance sheet while the obligation
continued to exist. Measured: collapsing it to two lines reddens
`withheld-tax-becomes-a-liability-not-a-smaller-expense`.

A **zero** withholding line is omitted rather than posted: 預り金 0 asserts a
liability of nothing, which is a different claim from having none.

### The withheld amount is carried, never computed

`kotoba-lang/taxlaw` records `:taxlaw/amount-checked? false` — 別表第二 and
別表第五 were not read, so nothing in this fleet certifies how much should
have been withheld. This namespace inherits that exactly: it moves the
figure the run declared and computes none of it. A test scans the source to
keep it that way.

It also checks `gross = withheld + net` here rather than leaving it to the
ledger. 4311 would refuse the unbalanced result, but the message would be
about currency arithmetic instead of *this run's figures disagree*.

### It produces a value; it makes no call

No HTTP, no client, no reference to 4311 — asserted by a test scanning the
namespace's own source. A call would make the accounts this actor's business
when they are the client's chart, and `kotoba-lang/shohyo` refuses to guess
what an account is because a statement that guessed still balances.

Measured, all seven mutations red: net the withholding away (2), emit for an
unapproved run (8), skip the gross = withheld + net check (3+1), treat a
missing figure as zero (**3 errors — reddens by NPE, not by assertion, which
is weaker evidence and recorded as such**), accept a half-filled mapping (2),
post a zero 預り金 line (3), have the batch discard its skips (1).

## 受領記録 — what the ledger actor did with the entry

**Converted is not posted.** 4311 can post the entry, find it already there,
hold it against a rule, park it for a human signature, or refuse the request
outright — and until `payroll.handoff` existed all five looked identical
from this side, because nothing recorded any of them. That is this plane's
recurring defect: a step that cannot fail visibly. A handoff whose only
trace is the request implies success by having no other trace.

`payroll.handoff/handoff-fact` turns **one** 4311 response into a ledger
fact; `handoff-facts` turns a whole `POST /api/entries` `207` into one fact
per submission. The caller appends them with the store's existing
`append-ledger!` — the namespace itself makes no call and touches no store,
asserted by a source scan like `shiwake`'s.

### The route points the other way

Being pure was right and it was also the problem: a namespace that calls
nothing, and that nothing called, is reachable from nowhere, and the
reconciliation it computed never reached the audit trail. Posting into 4311's
ledger is actuation this repo does not do, so the arrow is reversed instead —
**`POST /api/handoff` lets the carrier that already holds both halves bring
the answer here.**

Each entry in the body names its own submission, so the misattribution the
batch form has to detect by comparing counts and source documents cannot
arise: there is no position to get wrong. `:client-id` is stamped from the
allow-list and overwrites whatever the body carried — `ledger-of` slices by
exactly that key, so a body-chosen employer would write one client's
reconciliation into another client's books. Every outcome is written, not only
the refusals, and the response names the ones that are neither `:posted` nor
`:duplicate` so the carrier learns in the same round-trip what a person still
has to look at. An unreadable body appends **nothing**: half a batch is less
trustworthy than none.

Thirteen mutations cover the route (`nbb tools/mutate.cljs`); the table's
header states that it covers the route and not the actor.

| 4311 answered | fact records |
|---|---|
| `200` `:duplicate? false` | `:posted` + the posting id |
| `200` `:duplicate? true` | `:duplicate` |
| `202` | `:awaiting-approval` + the reason |
| `409` | `:held` + the violations |
| `400` / `403` / `503` | `:rejected` + the error |
| anything else | `:unknown-response` + a bounded excerpt |

**Every outcome is a fact, including the good one.** A ledger recording only
refusals could answer *what went wrong* but not *was this run posted?*,
which is the question the loop exists to close.

**`:duplicate` is not `:posted`.** One wrote; one confirmed something already
there. Two `:posted` facts for one run is a double payment; `:posted` then
`:duplicate` is a safe retry. A `200` carrying no boolean `:duplicate?` is
`:unknown-response` rather than either — without that flag the two cannot be
told apart, and guessing fabricates the distinction instead of recording it.

**An unrecognised shape is never a success.** Defaulting the unknown to the
good outcome is how a ledger fills with postings that were never made.

**The fact carries the same `:client-id` / `:contract-id` / `:period` stamp
`payroll.actor/identify` writes**, so `store/run-history` and `ledger-of`
return it; a reconciliation record that cannot be joined back to the thing
it reconciles is not one. Its `:disposition` is `:handoff` and never one of
the actor's own three, so a reader counting commits does not count handoffs.

**The batch refuses rather than zips.** Results come back in submission
order, so position is the only thing joining an outcome to its run — if the
counts differ, or a result cites a different `:source-doc` than the
submission at its position, `handoff-facts` returns
`:length-mismatch` / `:source-doc-mismatch` and **no facts at all**. A fact
attributed to the wrong run is worse than an absent one, because it is wrong
in a way that reads as settled. A batch refused whole (`400`/`403`/`503`)
still produces a fact per submission, marked `:handoff/scope :batch` so that
*it refused this entry* and *it never saw this entry* stay distinguishable.

Measured, all twelve mutations red (115 tests / 599 assertions green
unmutated): fold `:duplicate` into `:posted` (4), default an unrecognised
response to `:posted` (5), accept a `200` that cannot say whether it wrote
(8), record only refusals (16), zip a length mismatch (4), ignore a
wrong-`:source-doc` answer (3), drop the identity stamp (14), believe a
batch result's outcome unchecked (9+**1 error**), record nothing when the
whole batch was refused (6), let the namespace `slurp` (1), let the excerpt
grow unbounded (2), stamp a handoff `:commit` (2).

## The shared governor layer

`:no-client`, `:no-actuation`, `:unknown-contract` and
`:contract-wrong-employer` are not payroll rules — every actor in this fleet
has them, and they were hand-copied into 376 governors, one of which
silently drifted into reporting a HARD violation as escalatable. They now
come from [`kotoba-lang/governor`](https://github.com/kotoba-lang/governor),
along with the verdict assembly.

`:contract-wrong-employer` is why that library grew a `:scope-key`: a
`kotoba.labor` contract carries ownership as `:contract/employer` while the
request carries `:client-id`, and the shared rule previously read one key
off both sides. Generalising the library was the fix; copying the rule a
377th time was not.

`test/payroll/conformance_test.clj` pins every disposition against
`gov/conformance-failures`. **Measured: re-injecting that drift leaves all
14 pre-existing tests green and reddens only the conformance suite.**

## 源泉徴収 — the withholding hold

**A payroll run for an employer in a jurisdiction requiring withholding,
whose proposal does not account for withheld income tax, is HELD.** The law
is not this actor's: it lives in
[`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw) with the
インボイス rules 4311 and `tehai` use, and it was **read before it was
enforced** — 所得税法 第百八十三条第一項, retrieved from the e-Gov law API on
2026-08-17 and quoted verbatim upstream:

```
第百八十三条  居住者に対し国内において第二十八条第一項（給与所得）に規定する
             給与等（…）の支払をする者は、その支払の際、その給与等について
             所得税を徴収し、その徴収の日の属する月の翌月十日までに、これを
             国に納付しなければならない。
```

A missing withheld amount is not a zero-tax payroll run; it is an unanswered
question, and no confidence answers it.

### It fires only on an asserted condition

Both new HARD rules require the **employer record** to declare a
`:jurisdiction`. An employer that declares none is not held — nobody asserted
where these wages are paid — exactly as 4311 scopes its tax rules to
proposals claiming `:tax-treatment :input-tax-credit`. **Measured: removing
that scoping and defaulting to `[:jp]` reddens four pre-existing tests**,
including the clean-run fixtures, which is what the scoping exists to
prevent.

The jurisdiction is read off the employer, never the proposal — an advisor
that could pick its own could pick one whose rules it satisfies. Residency and
place of payment come off the **registered contract**
(`:employment/recipient-residency`, `:employment/paid-in`), because they are
facts an operator registers, not facts a model proposes. **Measured: letting
the proposal supply the jurisdiction reddens
`the-proposal-cannot-choose-its-own-jurisdiction`.**

### Where it deliberately does not hold, the verdict says so

`nobody looked` and `we looked and it was fine` must not print the same
thing, so the verdict's `:extra` carries `:tax` — the same device as 4311's
`:tax` and kintai's `:unevaluated`. Three passes, three different reasons,
all legible:

| `[:tax :withholding :taxlaw/coverage]` | means |
|---|---|
| `:not-declared` | the employer declares no jurisdiction; **no withholding law was consulted** |
| `:out-of-scope` | declared outside the read article (non-resident recipient, paid abroad). **Not a finding that no obligation exists** — the provisions governing those were never read, and `:taxlaw/read-provision` names the one that was |
| `:checked` | consulted, and answered |

Silence is not the article's exclusion: a contract that says nothing about
residency stays **in** scope, because absence of a declaration is the
unchecked case.

**The amount is never certified.** taxlaw read 第百八十三条第一項 but not
別表第二 / 別表第五, so every result carries `:taxlaw/amount-checked? false`
and 1 yen of withholding on 28,000 of wages passes this gate. What is gated is
that the run accounts for withholding at all.

**年末調整 (所得税法 第百九十条) is still not checked on a payroll-run DRAFT**,
which asserts nothing about the year's final payment. Every draft verdict
records `[:tax :year-end-adjustment :taxlaw/coverage] :not-evaluated` with a
reason, because a rule that is silently never called looks exactly like a rule
that was called and passed. What changed on 2026-08-18 is that there is now a
separate op that DOES evaluate it — see 年末調整 below. The draft path was left
alone; adding the op widened no pass.

Five mutations cover this hold's explanation and all five redden. A sixth — a
rollback of the `taxlaw` pin — was **removed** on 2026-08-18 and the reason is
recorded in `tools/mutations.edn`: 年末調整 made the newer pin load-bearing for
compilation (`taxlaw/facet-of` does not exist at the old sha), so the rollback
now breaks the reader rather than an invariant, and this harness scores that as
UNMEASURED rather than as a kill. A stale pin is still caught — by the build,
which is the louder signal — and the pin's content is asserted directly against
the dependency.

## 年末調整 — the op that refuses to invent a figure

**`kotoba-lang/taxlaw` had read 所得税法 第百九十条 from source and no actor in
this workspace called any of the three functions it exposes.** The law was
catalogued and nothing acted on it — a capability that existed only as a
citation. `:assess-year-end-adjustment` is the op that acts on it, and
`payroll.nenmatsu` is the reading.

```
第百九十条  給与所得者の扶養控除等申告書を提出した居住者で、…その年中に支払う
           べきことが確定した給与等の金額が二千万円以下であるものに対し、その
           提出の際に経由した給与等の支払者がその年最後に給与等の支払をする場合
           …において、…過不足があるときは、その超過額は、その年最後に給与等の
           支払をする際徴収すべき所得税に充当し、その不足額は、…徴収して…
```

### The over/under is not computable, and no number is produced

The article applies the excess against, and collects the shortfall with, the
year's final payment. **The year's correct tax comes from 別表 (税額表), which
taxlaw explicitly records as unread** (`:rule/amount-source-not-read`) — the
same limit the withholding rule already lives inside, where every result
carries `:taxlaw/amount-checked? false`.

So `:nenmatsu/amount` reports `:not-computable` for both the year's tax and the
over/under, names the unread table (**read off taxlaw, never typed here**), and
reports only figures this actor actually holds: the wages and the withheld
income tax **it itself committed** for that year, with the count of runs that
recorded no withheld amount. A figure invented here would be the most dangerous
value in the repository — it would arrive stamped with an article of the Income
Tax Act and nothing downstream could check it.

### Nine answers, four of which are the absence of an answer

| answer | commits? |
|---|---|
| `:owed` / `:settled` | yes — the three conditions hold |
| `:year-not-finished` | yes — **come back after the last payslip**, an instruction, not a finding |
| `:declaration-not-filed` | yes — 第百九十条 does not reach this employee; nothing is said about 確定申告 |
| `:above-ceiling` | yes — outside 二千万円 |
| `:jurisdiction-not-declared` | **HELD** — no law was consulted |
| `:not-catalogued` | **HELD** — the facet was not read. Not read is not absent |
| `:declaration-not-observed` | **HELD** — software cannot see a piece of paper |
| `:final-payment-not-declared` | **HELD** — this actor has no clock |

`kotoba.taxlaw` returns ONE `:out-of-scope` for three different facts, two
permanent and one that resolves itself in December. An operator told `out of
scope` cannot tell `this employee never qualifies` from `come back after the
last payslip`, and only the second is an instruction — so the three are
separated here, and taxlaw's own verdict is carried verbatim in
`:nenmatsu/taxlaw` so the split is auditable rather than merely asserted.

**Terminal answers are decided before transient ones**, so a permanent gap
cannot hide behind a `come back later`. That makes one answer diverge from
taxlaw's on purpose, and a test names the case.

### The ceiling is checked, in exactly one direction

二千万円**以下** is inclusive: at exactly 20,000,000 the employee is inside, and
the boundary is pinned at 20,000,000 / 20,000,001. The ceiling itself is read
from taxlaw's `:rule/income-ceiling-yen`, not typed here.

The figure it is tested against is the wages **this actor committed** for that
year, which is not 「その年中に支払うべきことが確定した給与等の金額」: wages paid
before this actor was deployed, or through another system, cannot be seen from
here. Unseen wages only add, so

* recorded **>** 二千万円 ⇒ definitely above, and
* recorded **≤** 二千万円 ⇒ **does not establish** the employee is inside.

`:establishes-inside?` is therefore `false` even when `:inside?` is `true`. With
no recorded runs, `:inside?` is nil and `:no-runs-recorded?` is true — zero
recorded wages is not a fact about wages.

### The declaration is a fact software cannot observe

給与所得者の扶養控除等申告書 is a piece of paper. It is registered on the
**contract** (`:employment/year-end-declaration-filed?`) by an operator, exactly
as `:employment/recipient-residency` is, and **unregistered is its own answer
and a HOLD** — the discipline `:yuryo-chobo-declared?` keeps in the sibling
bookkeeping actor. `"true"` is not a declaration either: `payroll.nenmatsu`
normalises anything non-boolean to nil, which holds, and the edge returns 400 so
the caller is told. Both halves exist because a caller who bypasses the surface
must still not get a pass.

### The year may not be over

「その年最後に給与等の支払をする場合」 is a condition about a payment that may not
have happened. This actor has no clock and cannot see whether another payment is
coming, so an undeclared final payment is **HELD** and a declared `false` is
`:year-not-finished` — which commits, because *not yet* is not *never*.

### Non-JP must not widen a pass

`requires-year-end-adjustment?` is nil for `[:eu]` and `[:us]`; both are
catalogued with that facet `:out-of-scope`. Both are **held**, with the catalog's
own reason forwarded — the United States has no year-end adjustment because the
annual return performs that function, **and IRC §6012 was not read**. A
jurisdiction nobody catalogued at all is held too, and says so differently.
`payroll.conformance-test/every-year-end-answer-is-either-answered-or-held`
pins the equivalence in both directions: answerable ⇔ not held.

### Where it is read from is not negotiable

The jurisdiction comes off the **employer**, the 申告書 off the **registered
contract**, the final-payment and settled declarations off the **request** — and
the contract being assessed is named by the **request**, not by the proposal. An
assessment has no arithmetic for the governor to recompute, so nothing the
advisor writes is load-bearing; an advisor that could name the contract would be
the thing deciding whose 年末調整 gets looked at. `payroll.governor-test/an-advisor-cannot-move-the-year-end-answer`
feeds an advisor emitting every one of those keys and asserts the assessment is
identical.

### Measured

`payroll.nenmatsu`, the governor's five assessment rules, the route and the two
places assessment records had to become legible in the ledger carry **35
mutations** (entries 19-53 of the table), every one of which reddens in the
tests that name the thing broken. Several were written before the tests they
turned out to measure; none survived, and the one entry that could not be
measured at all was removed with its reason recorded rather than left green.

### It is legible in the ledger

An assessment record has no gross and no period. Rendered like a payroll run it
would read as a payment of nothing — the worst lie this ledger could tell, told
about the op whose whole point is not to invent figures. So the ledger stamp
carries `:year`, and both read routes carry `:op`.

## 賃金の基礎 — what rule 4 does not check

Rule 4 recomputes `:gross` with `kotoba.labor/wages-for` and holds any
disagreement. It is exact, and it is about **agreement**: it proves the
advisor did not invent a number. It cannot prove the number is the wage,
because both sides compute it the same way, and the way is four lines:

```text
hourly   :contract/rate × Σ :ts/hours
monthly  :contract/rate                  ← the timesheets are IGNORED
```

For a company with one monthly salaried employee — the case this repository
is pointed at — that is right almost every month and silently wrong in the
ones that are not: a mid-month start, a leaver, unpaid leave, 欠勤. The figure
does not become uncertain in those months. **It stays confident and becomes
wrong**, and rule 4 agrees with it.

`payroll.chingin` names six facts an operator can register that the formula
**provably does not read**, and a run in which any of them is registered is
HELD. The list of ignored keys is derived by subtraction from what the formula
declares it reads, not typed — so a future `labor` that started reading one is
picked up by editing one place rather than by remembering to delete a line.

**Nothing here prices anything.** Each fact names the provision that would
have to be read — 労働基準法 第三十七条第一項 / 第四項, 同 第三十七条第五項 and
施行規則 第二十一条, 所得税法 第九条第一項第五号 — and records it as
**unread**. No multiplier, rate or threshold appears in the namespace.
Naming an unread article is not enforcing a rule from it.

Three things are deliberate:

- **A registered `0` does not hold.** An operator who answered *no overtime
  this month* has answered the question, and holding an answered question
  trains them to stop answering it. A malformed value (a string, a negative)
  does hold — that is not the same as no premium fact.
- **It does not require a declared jurisdiction**, unlike rules 5, 6 and
  12–14. Ignoring a registered hour is arithmetic, not law, so scoping it to a
  declared jurisdiction would make declaring nothing the cheapest way to skip
  it.
- **Every draft verdict carries a `:wage-basis` report**, held or not, saying
  whether the timesheets were read at all. A monthly run that ignores them is
  not held — that is normal for a salaried employee — but *the hours were read
  and agreed* and *the hours were never read* must not print the same, and
  until now they did.

The claim about the dependency is **measured, not asserted**:
`payroll.chingin-test/wages-for-really-does-ignore-what-this-namespace-says-it-ignores`
feeds `kotoba.labor/wages-for` contracts differing only in each ignored key
and asserts the output does not move.

## Where a figure came from, and whether it may be printed

An operator screen, a payslip and a CSV are all places where a number and the
absence of a number have to be told apart by a person — and the mechanism this
repository uses everywhere else (a keyword answer next to the value) stops
working the moment somebody renders it, because `nil` renders as the empty
string and the empty string renders as zero in the reader's head.

`payroll.provenance` makes a figure a value with six possible provenances:

| provenance | may carry an amount | shown as |
|---|:--:|---|
| `:derived` — THIS repository computed it from a rule it READ | ✅ | ● 確定 |
| `:declared` — the operator supplied it; nothing here certifies it | ✅ | ◆ 申告値 |
| `:imported` — from another system's unverified export | ✅ | ◇ 取込値 |
| `:not-applicable` — a registered fact says the line does not arise | ❌ | — 該当なし |
| `:unknown` — nobody supplied it and nothing computed it | ❌ | ? 未確定 |
| `:held` — the governor refused the run over it | ❌ | ▲ 保留 |

The constructor **throws** on the last three carrying a number. That is the
one place in this repository that throws rather than returning a refusal
value, and the reason is that a refusal value would be *rendered, in the slot
where the number goes*, by the view this namespace exists to protect.

Two consequences worth stating:

- **A total refuses rather than under-reports.** One `:unknown` line and the
  total is `未確定`, with the blocking lines named. This is
  `shakai-hoken/withheld-total`'s rule generalised, and it matters most in the
  deduction summary — a number somebody types into a payment.
- **A total takes the WEAKEST provenance of its parts.** A 控除合計 built from
  one derived and one declared figure is declared, so a certified-looking
  total cannot be produced from an uncertified part.
- **`:not-applicable` is never zero.** A total of nothing but non-arising
  lines is `該当なし` and carries no amount. 預り金 0 asserts a liability of
  nothing, which `payroll.shiwake` already refuses to post; this is the same
  refusal where a person reads it.

## The operator console — Japanese, keyboard-operable, and scriptless

`GET /console` and five more screens, server-rendered on
`kotoba-lang/jp-go-digital-design-system` (デジタル庁デザインシステム, this
workspace's base design system).

```text
/console            現況 — the deployment, its durability, the legend
/console/employees  従業員・契約 — registration, coverage, what is missing and what it costs
/console/run        給与計算 — period input, then the calculation review
/console/exports    出力物 — the four artifacts, and the 全銀 refusal
/console/mf         MoneyForward 突合
/console/ledger     監査台帳
```

**It ships no JavaScript at all**, which is the exception ADR-2608231200
records for a page sitting next to live credentials. Everything the
single-page rule protects is kept — one shell, one stylesheet, views
generated from a table so a screen cannot be added to the dispatch and
forgotten in the nav — and what is bought is that
`Content-Security-Policy: default-src 'none'` is truthful rather than
aspirational.

### State is never conveyed by colour

Every state chip carries a **text mark** (`●` `◆` `◇` `—` `?` `▲` — different
shapes, not one shape in six colours), a **Japanese word**, and an
**`aria-label` with the reason**. Colour is the fourth thing and the only one
that can be removed without the state disappearing. A payslip printed in black
and white still distinguishes 確定 from 未確定.

`payroll.ui.a11y` enforces this as a **hiccup-tree** check, not a regex over
markup: an element carrying a `prov-*` / `state-*` / `disposition-*` /
`verdict-*` class and saying nothing is a finding. Eleven other structural
rules ride along (labels, `scope`, `caption`, heading order, duplicate ids,
positive `tabindex`, nameless links and buttons).

**Every rule is tested in BOTH directions**, and the checker reports what it
scanned:

```text
要素 98・部品 13・表 1・見出し 4 を検査。指摘なし
要素 0・部品 0・表 0・見出し 0 を検査。検査対象が無い（これは合格ではない）
```

`clean?` is **false** for a zero-element scan. A checker handed a nil finds no
violations, and no violations is exactly what a passing view looks like —
which is CLAUDE.md's most-named defect, at the point where it would be
invisible. What the checker does **not** decide (contrast, focus visibility,
whether a label's text means anything) is emitted in its own result rather
than left implied.

### Registration goes through a rule written first

`payroll.edge.endpoints` records why `:reconcile-timesheets` has no route:
*opening a port to a write the safety layer has no rule about is a decision to
make by writing the rule first.* The console needs to register contracts, so
the rule now exists — `payroll.touroku` — and it is **not a second governor**:

- **nothing is defaulted.** An absent coverage flag stays absent, never
  `false`. A layer allowed to fill it in would convert *nobody looked* into
  *not a 被保険者*.
- **nothing is coerced.** `"true"` is refused at the door as well as
  normalised to nil downstream, so the operator is told.
- **an unknown key is refused**, so a typo cannot become a fact nothing reads
  while the form reports success.
- **ownership is stamped from the verified DID**, and a body naming an
  employer is refused rather than dropped.

## A real host, and a durability claim that is measured

`payroll.host.jvm` — `com.sun.net.httpserver`, **zero new dependencies** in a
repository whose dependency set is a hard property.

It **refuses to start** unless the deployment says what to store, who may
call, how a caller is identified, and where to listen. There is no
`PAYROLL_AUTH=none`; there is no default port; and binding beyond loopback
requires `PAYROLL_TRUST_FORWARDED=yes` as a **separate** acknowledgement,
because the failure being prevented is somebody widening the bind address for
a legitimate reason without noticing they also removed the only thing making
the trusted header trustworthy.

It verifies no signature. `kotoba-lang/org-chainagnostic-cacao` does that
(ADR-2607268000) and this repository does not reimplement it — the host reads
an already-verified DID from a header a proxy set, and the configuration is
what makes saying so safe.

### **Storage does not survive a restart, in either mode, and that is tested**

```clojure
{:store/mode :datomic
 :store/survives-process-restart? false
 :store/why "protocol の差し替えは済んでいて、契約テストが両 backend の
             同一性を証明している。しかし既定の :db-api は in-process の
             DataScript なので、耐久性は MemStore と変わらない …"}
```

`payroll.host.jvm-test/the-durability-claim-matches-what-actually-happens`
starts a host, commits a run **over the socket**, stops it, starts a second
host the way a restart would, and asserts the ledger is **empty** — for both
store modes. The claim is checked against a measurement rather than being a
sentence somebody keeps up to date, and `GET /api/health` serves the same
answer to anyone who asks.

## Four artifacts, none of them a statutory form

| artifact | formats | what it refuses to claim |
|---|---|---|
| 給与支払明細書 | JSON, printable HTML | 所得税法 第二百三十一条第一項 and the rule prescribing the contents are **unread** |
| 賃金台帳 | CSV, JSON | 労働基準法 第百八条 and 施行規則 第五十四条 are **unread** — and **no list of required items appears anywhere**, because a list written from memory invites an operator to tick it off |
| 控除額集計 | CSV, JSON | no 納付書 form; the totals refuse rather than under-report |
| 振込データ | CSV, JSON | **not** the 全銀協 format — `:format/standard :none` in the file |

Every one is deterministic: the same input produces the same **bytes**, with
column order fixed by a vector and never by a map, so a diff between this
month's file and last month's shows what changed in the payroll.

An unknown figure is **never a blank cell**. It is `未確定`, which every
spreadsheet refuses to read as a number — and failing there is the correct
outcome, because the figure genuinely is not known.

Held runs are **rows in the wage ledger**. A ledger of only what committed can
answer *what was paid* and cannot answer *why was this month not paid*, which
is the question anybody opens it for.

### 全銀 総合振込 is refused for every input, always

There is no argument that makes `payroll.artifact.bank-transfer/zengin` return
bytes and there is no function that does. The record layout has not been read,
and a file assembled from memory would be rejected by the bank or — far worse
— **misread by it**, on the day wages were due.

The refusal is not a dead end: it lists what an operator would still have to
register per contract, and what would remain missing after they had (the
layout, the 委託者コード, the numeric 預金種目 codes).

What **is** checked is that a registered 受取人名 is halfwidth — a fact about
Unicode, decidable without the banking spec — and the artifact carries what
that check **does not** establish. **Nothing is transliterated**: the reading
of a Japanese surname is not derivable from its characters, and a name that
does not match the account is a payment that bounces.

## MoneyForward — a boundary that has never seen the real thing

**No MoneyForward payroll export has been given to this repository.** Every
column name in `payroll.mf.schema` is a conjecture; `:mf/verified?` is `false`
on all of them; there is no code path that sets it true; and a test asserts
that the set of verified columns is **empty**.

What is real is the boundary around them:

- **an unknown column is reported, never dropped** — a deduction silently
  discarded during an import is one that vanishes between two systems
- **a malformed amount rejects its row and quotes the raw text back**. There
  is no `(or … 0)` anywhere in the importer
- **every imported figure carries `:imported` provenance** — never
  `:declared`, never `:derived`
- **an employee is matched by a registered number, never by name.** Matching
  on a name joins two people who share a surname, and one person's wages get
  reconciled against another's
- **a parse writes nothing.** Not the file, not a row, not a run

### 住民税 is in the vocabulary and has nowhere to go

MoneyForward withholds it. **This actor has no rule, no payslip line and no
account for it**, and 地方税法 第三百二十一条の五 is unread. It is mapped to
`:mf/no-counterpart` rather than dropped, and a file where it carries a
non-zero value **cannot reconcile** — because that is not a discrepancy in a
figure, it is a deduction one system makes and the other cannot.

### Five verdicts, and only one of them is a pass

`:agree` / `:differ` / `:only-in-mf` / `:only-here` / `:not-comparable`.

**`:not-comparable` is deliberately not a pass.** It is the state every run
this actor holds lands in, and a reconciliation that scored those as agreeing
would report a clean parallel run for a month in which this actor computed
nothing at all — this repository's recurring defect, at the exact point where
it would be most expensive: the report that says it is safe to switch.

A file-level `:reconciled?` requires six things, and the second is the
evidence floor: **at least one run was compared.** A report over an empty
import has no differences to show and would otherwise print exactly like a
perfect month.

## Two backends, and a contract test that cannot degrade to one

`MemStore` keeps the payroll ledger for exactly as long as the process lives.
A payroll actor whose record of what it refused disappears on restart cannot
answer the one question anybody asks it later — why was this run not paid — and
a committed run that is then forgotten is a run that can be committed twice,
which the second time is a second payment. So there is a **`DatomicStore` over
`langchain.db`**, built on
[`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store)
(EDN-blob codec, identity schema, seq-keyed streams — not re-hand-rolled here),
and `test/payroll/store_contract_test.clj` runs **every** assertion against both
out of one `backends` map, with an evidence floor asserting that map still holds
two distinct types. A contract test that silently degrades to one backend passes
forever.

Three streams are seq-keyed and append-only, not two. **Timesheets are the
third**, and they are the one the siblings do not have: they are the only
admissible basis for an hourly wage, so a backend that lost one would not raise
anything — it would compute a different lawful-looking wage, and the governor
would then hold the *honest* proposal for `:wage-mismatch`.

**Measured 2026-08-18.** Three mutations break the durable backend ALONE:

| broken | reddens |
|---|---|
| `timesheets-of` stops scoping to the worker | 3 tests (2 in the contract test) |
| the contract loses `:employment/paid-in` on write | 2 tests, **both in the contract test** |
| `next-seq` always returns 0, so the logs upsert onto one entry | 11 tests |

In each case the 33 pre-existing tests stay green, which is exactly the state
the contract test exists to end.

## The HTTP surface — five API routes, plus the console

`src/payroll/edge/endpoints.cljc`, portable `.cljc`, `{:status n :body {...}}`
in and out, no host effects and no framework. `payroll.host.jvm` mounts it,
and `src/payroll/edge/console.cljc` adds the operator screens on the same
three gates — a console with its own validation path would be a second place
for the rules to live, and the second place is the one that drifts.

```
POST /api/payroll-run               draft a payroll run
GET  /api/payroll-run/:contract-id  the whole life of one contract's runs
GET  /api/ledger                    the caller's own slice of the ledger
POST /api/handoff                   what the ledger actor answered about
                                    runs this employer submitted
POST /api/year-end-adjustment       is a 年末調整 owed for one employee and
                                    one year, and what can be computed
```

**`:disburse-wages` has no HTTP representation and will not get one.** It is in
`escalating-ops`, so it always escalates and can only complete when a person
resumes the thread — putting it behind a socket would mean the only thing
between a stolen credential and a payment run is that the thief must also wait
for a human to click. **`:reconcile-timesheets` is withheld for a different
reason**: the governor recomputes *nothing* for it (the contract basis and both
arithmetic identities are gated behind `draft?`), and opening a port to a write
the safety layer has no rule about is a decision to make by writing the rule
first.

The disciplines the surface carries, each of which is a way a surface undoes the
actor behind it:

- **An absent allow-list serves 503, never an open endpoint** — `nobody is
  allowed` and `nothing was configured` are different deployment states.
- **An unconfigured store serves 503, never an empty in-process one** — an empty
  store fails the governor's provenance check on every request, so the caller is
  told `:no-client` and blamed for a deployment fault.
- **The employer comes from the verified DID, and a body naming one is
  REFUSED** rather than ignored. This is stricter than the siblings, deliberately:
  silently dropping it lets a caller believe they filed a payroll run against an
  employer they did not.
- **An unknown contract is 404, never an empty 200**, and another employer's
  contract is a byte-identical 404 — otherwise a contract id is something a
  competitor can probe for.
- **202 for an escalation.** `awaiting a human signature` is neither done nor
  refused. It is unreachable under the default mock advisor (whose lowest
  confidence is 0.7, above the 0.6 floor), so the branch is exercised with a
  swapped-in low-confidence advisor rather than left untested.
- **Every run response carries `:withholding`.** The coverage is four-valued
  where the article was reachable — `:checked` / `:out-of-scope` / `:not-declared`
  / `:none` — and `:amount` is reported separately and is never `:checked`,
  because 別表第二 / 別表第五 were not read.
- **A 200 on `/api/year-end-adjustment` is not an approval.** It means the
  actor could answer, and the answer is in the body — three of the five
  answers that commit are the article NOT reaching this employee. The status
  carries the DISPOSITION and the body carries the ANSWER, and neither is the
  other. There is no `:ok` on it.
- **A ledger entry names its `:op`.** An assessment record has no gross and no
  period; rendered like a payroll run it would read as a payment of nothing.

Since 2026-08-25 these functions **are** mounted: `payroll.host.jvm` binds
them to a socket, and `payroll.edge.endpoints`' own docstring line about
mounting being "a host binding this repo does not yet carry" is superseded by
that namespace. What has not changed is what the host does NOT do — it
verifies no signature, and `payroll.host.config` refuses to start a
deployment where that would be unsafe.

Mutation coverage for this surface lives in `tools/mutations.edn`, whose header
states what the table does and does **not** cover — a clean run means "every
invariant listed there is measured", never "this actor is measured".

## Zero `:local/root`

Every dependency is a git coordinate. Verified 2026-08-18 from a fresh
`git clone` into `/tmp` with `GITLIBS` pointed at an empty directory and no
sibling checkout of langgraph, labor, governor, taxlaw or langchain-store in
existence — which is the only check that proves it, since a *transitive*
`:local/root` is just as fatal and does not appear in this repo's `deps.edn`.
`langchain-store` was checked by **parsing** its `deps.edn` as EDN (4 distinct
coordinates, 0 `:local/root`), not by grepping it: that file's comments contain
the literal `:local/root` while its coordinates contain none.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
