# Public positioning and pricing clarity

- Home hero now explains asynchronous metadata reporting, no prompt content required by MeterTrail and no model proxy.
- Public plan cards add buyer labels; Pro and payment opt-in behavior remain unchanged. Full retention explanation moved into accessible FAQ on public pricing only; console plan explanations remain.
- Scale examples use 1 Trace + N Observations: 2 / 4 / 21 units, or 200,000 / 400,000 / 2,100,000 for 100,000 requests. Additional Scores add units.
- Frontend tests: 24 files, 69 passed. Production build and 11 generated public pages passed. Three generated-example synchronization checks passed. Locked dependency audit: 114 packages, zero advisories. git diff --check passed.
- Local browser fixture: English desktop hero/pricing inspected; Chinese FAQ expanded; 390px pricing cards single column (325px), body375px; Chinese home body375px. Fixed existing overage-table overflow with a local scroll container.
- No backend or payment contract changes. Not deployed.
