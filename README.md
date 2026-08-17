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
33 tests / 135 assertions green.

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

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
