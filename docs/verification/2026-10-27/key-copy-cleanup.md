# Key name and helper copy cleanup — 2026-10-27

- Removed the default Production key value; creation displays an empty name field with a localized placeholder. Existing empty-name send validation remains in effect.
- Removed the rotation explanation below the key list in both languages. No API, verification or key lifecycle changes.
- Before commit: frontend tests passed (40/14), production build passed (10 public pages), dependency audit passed (0 vulnerabilities), generated-example synchronization check passed.
- Live browser desktop/narrow interaction not performed. Required exact-commit CI must pass before deployment.
