# Public language position

The root App language control inherited fixed positioning from the legacy shared stylesheet. Public pages use a scrolling header, so the control remained over content after the header left the viewport. Override positioning to absolute in the public-language scope; retain public coordinates and existing responsive rules and leave console positioning unchanged.

Verification commands: frontend npm test, npm run build, clean npm CLI audit (high threshold), root node scripts/sync-doc-examples.mjs --check, python scripts/check-web-image.py. Results recorded after execution below. No interface or application contract changed.

Desktop/narrow layout rules retain existing offsets and select sizing. Live browser scroll and visual acceptance are not completed; no visual pass is claimed.

Results: all commands passed; 36 tests / 13 files, seven public pages built, audit zero vulnerabilities, generated examples synchronized, UID 101 image checks passed including retained assets and gzip. CI required before deployment.
