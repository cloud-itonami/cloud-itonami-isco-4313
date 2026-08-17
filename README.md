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
18 tests / 74 assertions green.

The payroll-specific HARD invariant: **the governor recomputes wages
deterministically via `kotoba.labor/wages-for` from the REGISTERED
contract and timesheets, and holds any proposal whose gross/net
disagrees — fair pay is arithmetic, not opinion.** An advisor (human
or LLM) cannot get an invented amount approved, at any confidence.
Also HARD: unregistered employer, missing/foreign/invalid contract
(no invented employment), `:effect` other than `:propose`.
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

Workspace note: `kotoba-lang/labor` is referenced by sibling path —
forks need the kotoba-lang workspace layout (labor's own deps use
sibling paths and are not git-dep consumable).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
