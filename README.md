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
14 tests / 36 assertions green.

The payroll-specific HARD invariant: **the governor recomputes wages
deterministically via `kotoba.labor/wages-for` from the REGISTERED
contract and timesheets, and holds any proposal whose gross/net
disagrees — fair pay is arithmetic, not opinion.** An advisor (human
or LLM) cannot get an invented amount approved, at any confidence.
Also HARD: unregistered employer, missing/foreign/invalid contract
(no invented employment), `:effect` other than `:propose`.
Escalations (always human sign-off): `:disburse-wages` (real fund
movement), low confidence (< 0.6).

Workspace note: `kotoba-lang/labor` is referenced by sibling path —
forks need the kotoba-lang workspace layout (labor's own deps use
sibling paths and are not git-dep consumable).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
