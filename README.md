# cloud-itonami-isco-4313

**Community Payroll Service** — the ISCO-08 4313 (Payroll Clerks)
actor, an ISCO **Wave 0 (cognitive substrate)** occupation per
ADR-2607121000: pure-cognitive work, the LLM-first wave, no robotics
gate.

**Maturity: `:implemented`** — PayrollAdvisor ⊣ PayrollGovernor as a
langgraph StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt), modeled on
cloud-itonami-isco-4311's bookkeeping actor. **Consumes
`kotoba-lang/labor`** (contracts / timesheets / wages / payroll) per
the fleet's capability-library-wrapping convention (same as
cloud-itonami-isic-9700) — wage arithmetic is never reinvented here.
93 tests / 443 assertions green.

The payroll-specific HARD invariant: **the governor recomputes wages
deterministically via `kotoba.labor/wages-for` from the REGISTERED
contract and timesheets, and holds any proposal whose gross/net
disagrees — fair pay is arithmetic, not opinion.** An advisor (human
or LLM) cannot get an invented amount approved, at any confidence.
Also HARD: unregistered employer, missing/foreign/invalid contract
(no invented employment), `:effect` other than `:propose`, an
uncatalogued declared jurisdiction, and a run that does not account for
withheld income tax where the law requires it (see 源泉徴収 below).
Escalations (always human sign-off): `:disburse-wages` (real fund
movement), low confidence (< 0.6).

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

## The HTTP surface — five routes

`src/payroll/edge/endpoints.cljc`, portable `.cljc`, `{:status n :body {...}}`
in and out, no host effects and no framework.

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
