# Product guidance verification

Frontend and documentation only; backend behavior and automatic deletion policies are unchanged.

- `npm --prefix frontend test`: PASS, 24 files / 71 tests, including bilingual real-capability and audit-retention public rendering.
- `npm --prefix frontend run build`: PASS, 11 static public pages generated. Security initial HTML includes the new retention/boundary statements. Existing bundle-size warning remains.
- `node scripts/sync-doc-examples.mjs --check`, `node scripts/sync-scenario-examples.mjs --check`, `node scripts/write-sdk-id-guides.mjs --check`: PASS.
- `npm --prefix frontend audit --audit-level=high --json`: local npm crashes with `Class extends value undefined`. Not recorded as a scan success. Alternative `.local/audit-locked-dependencies.py` queries the official registry advisory bulk API for all 114 exact locked packages: PASS, zero advisories. Exact commit security and frontend-sdk CI still required before deployment.
- `git diff --check`: PASS (line-ending notices only).
- Browser localhost fixture: verified analytics missing labels and expanded customer/user/feature example; at 390px viewport body width375, code scrolls inside its container. Verified alert recipient text, frequency, and scope disclosure; viewport reset.

Checked implementation sources: Worker.alerts sends owner email, evaluates both Trace/Observation, and has one-hour cooldown; analytical retention deletes objects/cost entries without deleting audit_logs. Account deletion retains audits. Public copy makes no fixed audit/backup expiry or permanent-storage promise. Shared PlanFeatures confirms common features without making them Pro-exclusive. Support mailbox setup and formal audit/backup expiry policy remain explicit TODOs.
