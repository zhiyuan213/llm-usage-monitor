# Project activity pagination and export

- Project management rows provide overview, traces, analytics, API keys, alerts and settings shortcuts. Each shortcut selects its own row's project before navigating.
- Project overview activity uses 5 rows per page. `GET /api/projects/{project}/activity?page=0&size=5` returns items, total, page and size. The API size accepts 1–100. Order is descending event time followed by type and object ID for deterministic ties.
- `GET /api/projects/{project}/activity/export?format=xlsx|pdf&lang=en|zh-CN` exports every retained activity row for the selected project, independently of the current page. There is no 100-row export limit. Both formats include the six displayed columns: UTC time, object name or ID, type, model, cost in USD and status/value type.
- Missing costs remain Unknown; non-observation costs remain a dash. Exporting does not alter data, units or retention. Each request uses a repeatable-read transaction; separately requested exports can differ if data changes between requests.
- Excel uses real XLSX with UTF-8 inline text cells to prevent spreadsheet formula injection. Worksheets split at Excel's row limit rather than dropping extra rows. Headers repeat per sheet.
- PDF embeds OFL-licensed Noto Sans SC, wraps cells without truncation, creates additional landscape pages and repeats column headers. Unsupported font characters appear as explicit Unicode code-point notation rather than being silently omitted.
- API requests enforce the same account/project ownership and retention filter as the console. Export responses use attachment headers and `Cache-Control: no-store`.
- Integration tests cover 125 retained objects, an expired object, final-page completeness, all-row XLSX/PDF exports, Chinese text, PDF page breaks, invalid input and cross-account access rejection.

## Consistent query tables

- Traces, all three analytics dimensions and audit logs use `GET /api/reports/{traces|analytics|audit}` with a zero-based page and fixed page size 5. Responses contain items, total, page and size.
- Project queries require `project`; analytics accepts `dimension=customerId|userId|feature`; traces accepts `search` and `status`. Account audit queries always apply the authenticated account's ownership filter.
- The same path plus `/export?format=xlsx|pdf` exports every matching row, including rows beyond the former 100-row limits, with the same project, filters, dimension and authorization as the table query. Export results do not depend on the current page.
- Traces export seven data columns: start UTC, name, object ID, duration ms, user, customer, status. Name/ID and user/customer are separate export columns to retain all displayed values. Analytics exports all five columns; audit exports all six columns. View/detail buttons are navigation controls, not data.
- Shared controls show export buttons, total count, page count and previous/next buttons even on a single page. Project or dimension changes reset pagination.
- Regression coverage validates 125-row exports for each report, five-row pagination including the final page, all column counts, PDF page breaks, trace-filtered export, three dimensions and cross-account project/audit isolation.
