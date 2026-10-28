# Statistics chart container width

The project statistics and analytics SVG viewBoxes now track their actual container width through a shared ResizeObserver. Fixed-height charts no longer center a 600px drawing inside a wider viewport with extra side margins. Large datasets and narrow screens retain internal horizontal scrolling; text and bars are not stretched.

Pre-commit checks:

- `npm --prefix frontend test`: 20 files, 59 tests passed.
- `npm --prefix frontend run build`: successful production bundle and 11 static public pages.
- `node scripts/npm-audit.mjs` in Node 24 Docker: zero vulnerabilities.
- All three generated-source checks passed: `sync-doc-examples.mjs --check`, `sync-scenario-examples.mjs --check`, `write-sdk-id-guides.mjs --check`.
- Browser synthetic administrator fixture: at 1920px, container width 757px, SVG width 757px and viewBox `0 0 757 400`. Token and estimated-cost selector works. At 390px, document client/scroll width both 375px, chart viewport 233px with internal 600px SVG scroll. Desktop preview `.local/chart-width-desktop.png`.
- Backend, exports and SDK wire contracts are unchanged by this layout commit; their full regression passed for parent `deb4132`.

The exact pushed commit must pass backend, frontend-sdk, security and images CI before deployment.
