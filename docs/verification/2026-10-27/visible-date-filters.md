# Visible dates and overview cleanup

- Shared date filter always displays both date fields, including rolling presets. Custom defaults to UTC calendar-month bounds; custom end is inclusive and future time is capped at now. Editing dates switches to custom; preset changes still query immediately.
- Overview/traces bind their applied window back into the date control, including restored query state. Overview removes repeated range/project/environment/retention captions and the View traces shortcut. Traces removes the redundant range/calculation text. Export query parameters and endpoints are unchanged.
- Date tests exercise leap February, December month end, exact rolling windows, exclusive API end conversion, current-month future clamping and invalid/reversed inputs.

Verification before commit: frontend tests (39 / 14 files), production build (seven public pages), dependency audit (zero vulnerabilities), generated-example synchronization and non-root web image checks passed. Final rerun includes the applied-window synchronization adjustment. No backend/report endpoint or export schema change.

Desktop/narrow layouts retain the existing wrapping toolbar and full-width mobile labels. Live browser visual/interaction acceptance is not completed; no visual pass is claimed. Exact-commit four-job CI remains required before deployment.
