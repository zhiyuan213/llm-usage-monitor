# MeterTrail console UI V2

Implemented from the supplied 1920 × 1080 design viewport and design specification on 2026-10-22.

- Shared Noto Sans SC font, bundled locally as WOFF2 with its OFL license.
- Sidebar 224 px; navigation 14 px with 18 px icons and 42 px rows.
- Page titles 24 px, section titles 16 px, body/controls 14 px, supporting text 12 px.
- Buttons 36 px high, minimum width 112 px; verification buttons at least 144 px. Table operations are text links, with destructive actions red.
- Cards 8 px radius, controls 6 px; primary #3156E8, sidebar #12213D, page #F5F7FB.
- Shared list toolbars, empty states and pagination; account details above two security panels; project details before project deletion; equal-height billing usage/history panels.
- Console-only styles live in frontend/src/console-v2.css. Public pages, backend endpoints, authorization, forms' submit handlers, filtering, pagination sizes and export content remain unchanged.

Validation: screenshots of all ten supplied page layouts at 1920 × 1080, with no horizontal page overflow; default five-row overview, settings and billing fit one screen. 390 px responsive checks cover management, traces, alerts, account and billing. Browser checks cover pagination 10/2 rows, empty filtering, trace details, project shortcuts and analytics dimensions. Actual data and empty states remain dynamic, not copied from the reference screenshots.
