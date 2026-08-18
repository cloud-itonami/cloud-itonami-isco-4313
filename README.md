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

**年末調整 (所得税法 第百九十条) is read and catalogued upstream, and this
actor does not check it** — it has no year-end op, and a payroll-run draft
asserts nothing about the year's final payment. Every draft verdict records
`[:tax :year-end-adjustment :taxlaw/coverage] :not-evaluated` with a reason,
because a rule that is silently never called looks exactly like a rule that
was called and passed.

Eight mutations measured; all eight redden, each in the tests that name the
thing broken.

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

## The HTTP surface — four routes

`src/payroll/edge/endpoints.cljc`, portable `.cljc`, `{:status n :body {...}}`
in and out, no host effects and no framework.

```
POST /api/payroll-run               draft a payroll run
GET  /api/payroll-run/:contract-id  the whole life of one contract's runs
GET  /api/ledger                    the caller's own slice of the ledger
POST /api/handoff                   what the ledger actor answered about
                                    runs this employer submitted
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

Twelve mutations measured across the store, the actor and the edge; all twelve
redden, each in the tests that name the thing broken.

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
