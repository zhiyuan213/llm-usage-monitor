# Trace detail and request list layout verification

- `npm --prefix frontend test`: 22 files, 63 tests passed.
- `npm --prefix frontend run build`: TypeScript, Vite and 11 public pages passed.
- `python .local/audit-locked-dependencies.py`: 114 locked packages, zero advisories.
- Generated example checks: sync-doc-examples, sync-scenario-examples and write-sdk-id-guides with --check all passed.
- `git diff --check`: passed.
- Browser fixture: trace table starts with object ID then name, with no model column. Detail shows one Trace and two Observations within waterfall; separate detail cards removed. Full IDs, model, cost, usage, time/status/revision and expandable fields retained. At 390px no page horizontal overflow.
- Overview fixture: cards show only request name, timestamp and status; 62px row height; page size 10. Request duration and usage appear in right detail.

These are frontend checks; no backend or export contract changes. Exact commit CI remains a deployment gate.
