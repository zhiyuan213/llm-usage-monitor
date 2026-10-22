# Project activity pagination and export

- Project management rows provide overview, traces, analytics, API keys, alerts and settings shortcuts. Each shortcut selects its own row's project before navigating.
- Project overview activity uses 20 rows per page. `GET /api/projects/{project}/activity?page=0&size=20` returns items, total, page and size. Size accepts 1–100. Order is descending event time followed by type and object ID for deterministic ties.
- `GET /api/projects/{project}/activity/export?format=xlsx|pdf&lang=en|zh-CN` exports every retained activity row for the selected project, independently of the current page. There is no 100-row export limit. Both formats include the six displayed columns: UTC time, object name or ID, type, model, cost in USD and status/value type.
- Missing costs remain Unknown; non-observation costs remain a dash. Exporting does not alter data, units or retention. Each request uses a repeatable-read transaction; separately requested exports can differ if data changes between requests.
- Excel uses real XLSX with UTF-8 inline text cells to prevent spreadsheet formula injection. Worksheets split at Excel's row limit rather than dropping extra rows. Headers repeat per sheet.
- PDF embeds OFL-licensed Noto Sans SC, wraps cells without truncation, creates additional landscape pages and repeats column headers. Unsupported font characters appear as explicit Unicode code-point notation rather than being silently omitted.
- API requests enforce the same account/project ownership and retention filter as the console. Export responses use attachment headers and `Cache-Control: no-store`.
- Integration tests cover 125 retained objects, an expired object, final-page completeness, all-row XLSX/PDF exports, Chinese text, PDF page breaks, invalid input and cross-account access rejection.
