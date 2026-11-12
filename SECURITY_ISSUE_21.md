# Issue #21 — token-root job privacy and canonical authorization

This complete source retains fixes #13–#20 and addresses [issue #21](https://github.com/Kosztyk/clamv-webui/issues/21).

- Apply nonempty token roots to PATH/WATCH job lists, details and progress; restrict upload reads to the submitting token.
- Apply the same policy to linked audit events and pagination totals. Use escaped SQL candidate filters plus final Java checks, including under case-insensitive database collations.
- Canonicalize existing directory grants at issue time, including aliases and symlinked global roots. Preserve pinned grants when the original alias is retargeted; reject invalid/unavailable legacy alias grants.
- Pass the canonical authorization result into job creation, and reject redirected queued PATH/WATCH targets before a daemon command.
- Preserve empty-root endpoint-wide history semantics, role checks, DTO redaction and the prior scanner/upload behavior. Document the stricter upload/audit rules and legacy token reissuance.

The full Java suite passed with **751 passed, 5 existing skipped, zero failures/errors** (756 total; 22 new cases). Tests include 1,201 visible jobs, 501 linked audit events, actual case-insensitive H2 overmatches, canonical aliases, ownership and deterministic authorization/queue race cases. The packaged HTTP verification record identifies its synthetic scanner fixture and exact limits.

Application version remains 2.1.0. No schema migration or new Configuration Center field is required. Rebuild/recreate the application and retain the database, conf/data and keys. No GitHub repository write or release publication was performed.

See [complete semantics and upgrade details](docs/TOKEN_PATH_SCOPES.md), [validation](docs/security/token-path-scopes-validation.json) and [the cumulative changed-file list](CHANGED_FILES.md).
