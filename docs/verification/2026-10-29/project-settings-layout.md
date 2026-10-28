# Project settings layout and key page cleanup

Local checks, 2026-10-29:
- Project configuration moved before public pricing; heading renamed. Public pricing uses a plain text catalogue link.
- Custom configuration and price list form equal-width, equal-height desktop cards (observed 864px each / 469.6px height). Updated bilingual headings. At a 390px requested viewport they stack, with single-column form controls and internal table scrolling.
- Time-band toggle still displays its inputs. Removed BatchHistory mount/import from the key page; its batch-list request is no longer triggered there. No stored batches or API endpoints deleted.
- Vitest: 21 files / 61 tests pass. vue-tsc, Vite production build and 11-page generation pass (.local/custom-pricing-layout-{tests,build}.log).
- Official npm bulk advisory check: 114 locked package names, zero advisories. Three generated example synchronization checks passed.
- Browser fixture verified headings/order, plain link style and return path, desktop equal-height cards, narrow layout, time-band fields and absence of recent batches on key page.
- git diff --check passed. Not pushed or deployed.

Additional pre-commit checks for toast and desktop tester:
- node node_modules/vitest/vitest.mjs run: 21 files / 61 tests pass.
- node node_modules/vue-tsc/bin/vue-tsc.js -b: pass.
- node node_modules/vite/bin/vite.js build; node scripts/build-public-pages.mjs: pass, 11 public pages.
- node scripts/sync-doc-examples.mjs --check; node scripts/sync-scenario-examples.mjs --check; node scripts/write-sdk-id-guides.mjs --check: pass.
- .local/audit-locked-dependencies.py: 114 package names, zero advisories.
- Python unittest discover -s tools/model-tester -v: 4 tests pass (provider usage mapping, unknown values, association, retry identity retention).
- Python Tk window construction/update/destroy: pass. No real model credentials or paid model calls used. Desktop tester is source distribution requiring Python with Tk.
- Toast CSS uses fixed top 88px and horizontal centering, max-width calc(100vw - 32px); 1-second lifecycle unchanged. Production UI positioning not yet verified.
