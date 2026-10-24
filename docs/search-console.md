# Search Console and crawl configuration

The URL-prefix property covers `https://metertrail.com/` only. This does not
verify all subdomains or HTTP variants; a future Domain property needs DNS
verification. The Google-supplied HTML ownership file is kept in frontend/public
and copied unchanged into every frontend image. Do not remove it after verification.

`/sitemap.xml` lists only the homepage, pricing and documentation; hash fragments
and private/auth/payment URLs are not sitemap entries. `/robots.txt` advertises
the sitemap and excludes API/ingestion/webhook paths from crawling. Public policy
pages remain out of the sitemap while their legal content is still a template.

Nginx sends `X-Robots-Tag: noindex, nofollow, nosnippet` on account, authentication,
payment and API paths. The map uses the original `$request_uri`, so SPA fallback
does not remove the exclusion. These headers are separate from authentication;
robots instructions never protect private data. Public pages retain normal
indexing behavior, and existing CSP/security headers remain enabled.

Submit `https://metertrail.com/sitemap.xml` in the verified Search Console
property and link that property to the MeterTrail GA4 Web stream. Search Console
submission does not guarantee indexing or search rankings. GA4 collection still
requires website tagging; the Search Console link does not install it.
