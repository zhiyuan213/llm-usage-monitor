# Explicit date search

The shared date toolbar always renders a primary blue Search / 查询 button. Changing a quick range updates draft dates only; the change event is emitted only by the query button. Custom dates still require explicit submission. An unselected range cannot submit. Existing rolling-window and inclusive-end behavior stays unchanged.

Before commit: frontend npm test passed (39 tests / 14 files); npm run build passed (seven public pages); clean npm audit high threshold reported zero vulnerabilities; generated-example synchronization passed; non-root web-image acceptance including retained assets, cache headers and proxy gzip passed. Updated existing UI contract coverage for button presence/style and two visible date fields in both modes. Desktop/narrow wrapping rules unchanged; live browser interaction/visual acceptance not completed.
