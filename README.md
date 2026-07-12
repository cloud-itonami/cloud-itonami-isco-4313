# cloud-itonami-isco-4313

Open Business Blueprint for **ISCO-08 4313**: Payroll Clerks — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:blueprint`** — blueprint only; **no actor implementation
yet**, and none is claimed. The implemented actor will follow the
fleet-standard pattern (advisor-LLM sealed behind the independent
`:payroll-governor` governor, human approval workflow, append-only
audit ledger). Fifth wave-0 cognitive batch (ADR-2607122700 addenda).

Payroll touches wages — the same domain kotoba-lang/labor's contracts/timesheets/wages primitives already model (see cloud-itonami-isic-9700); the implemented actor should consume that library rather than reinvent it.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
