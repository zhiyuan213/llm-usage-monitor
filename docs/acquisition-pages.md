# Public acquisition pages and measurement

Implemented 2026-10-24. Public entry points: `/`, `/demo`, `/tutorials`, and
`/tutorials/nodejs`, `/tutorials/python`, `/tutorials/rest`. Navigation, homepage
guide cards and related-guide links connect the pages. New routes are in sitemap.xml.

## Measurement scope

- GA4 property MeterTrail, stream MeterTrail Web, measurement ID `G-W26TJWRR17`.
- Basic opt-in: no Google script or measurement before consent, or after decline.
  Preferences remain available on public pages. Withdrawal denies consent, removes
  this property's GA cookies and reloads to unload the Google code.
- Only enumerated public paths are measured; URL queries, hashes, document referrer,
  account identifiers, form values and content are not passed. Explicit page titles
  are fixed public strings. Advertising personalization and Google signals disabled.
- A full document navigation unloads the tag before leaving public pages for
  login, payment, console or any unknown path. Direct private-page loads never start it.
- Keep GA4 enhanced measurement **off**, including automatic history/page views,
  outbound links, forms, site search and downloads. This was disabled on the stream
  during setup. `send_page_view: false` alone does not disable enhanced measurement.
- Events: `page_view`, `start_free`, `open_demo`, `open_tutorial`, `copy_example`.
  Actions describe public-site clicks, not successful registration, ingestion or revenue.
  Browser consent/blockers mean these are not exhaustive counts.
- Localhost/preview hosts never send real GA events, even after opting in.
- Optional UTM labels are enum-validated. Allowed sources: github, reddit, producthunt,
  hackernews, devto, medium, x, google, newsletter; media: social, referral, email,
  organic; campaigns: launch, node-tutorial, python-tutorial, rest-tutorial.
  Unknown values are discarded, never forwarded as arbitrary strings.
- Example campaign URL: `https://metertrail.com/tutorials/nodejs?utm_source=github&utm_medium=referral&utm_campaign=node-tutorial`.
- Open the GA4 Realtime report after an explicitly consenting production visit to
  verify arrival. Reports may take time; code tests do not prove Google received data.

Official references:
- https://developers.google.com/analytics/devguides/collection/ga4/views
- https://developers.google.com/tag-platform/security/guides/consent
- https://developers.google.com/tag-platform/security/guides/csp

## Demo and guides

Demo fixtures are entirely fictional and local. Metrics derive from the same six
request fixtures; filters and selected request details work without a user account.
Unknown model cost stays Unknown. Selecting or filtering does not write data or
consume monitoring units. No claim of actual provider pricing or live traffic.

Three bilingual guides cover server-side Node.js, Python and REST/cURL. They include
account/project/key preparation, environment variables, executable code, receipt,
optional batch query, console verification, usage caveats and troubleshooting.
Node/Python examples reuse the generated REST examples; never edit examples.ts directly.
REST code generates fresh IDs/time and explains preserving the batch on a retry.

Build-time output includes homepage, demo, guide index and all three full guide
articles in initial HTML. This is shared-content static output, not a separate bot
experience. Vue supplies interactivity after loading. Nginx serves clean URLs from
the generated `.html` files. Generic SPA fallback remains an empty app shell, so
private routes do not initially show homepage marketing content.

## Verification

- Frontend tests cover the measurement allowlist, opt-in/decline boundary,
  sanitized labels, duplicate view prevention, and advertising settings.
- `node scripts/check-tutorials.mjs` from frontend runs all three published examples
  against a local HTTP capture server with a synthetic key, checking IDs, timestamps,
  schema and one HTTP 200/pending receipt per guide. No production units consumed.
- CI runs the tutorial execution after setup-python; generated-example sync and
  dependency audit remain required. The image check serves each new public route
  as the default non-root user and checks readable initial guide HTML.
- Desktop/narrow-screen browser checks cover filters, selection, tutorial navigation,
  language, clipboard, consent choices and no horizontal overflow.

This does not complete the operator's legal policy templates or establish a
production capacity guarantee. No registration/revenue conversion is fabricated.
