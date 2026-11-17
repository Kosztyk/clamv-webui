# Issue #26 — Admin form and settings validation (v2.1.0beta2)

This update is based on the full v2.1.0beta2 issue #25 local source archive. It does **not** change the audit writer, login/LDAP/OIDC security, scanning, quarantine, upload file modes, trusted-proxy settings, session revocation, or Docker isolation. It is a local candidate only.

## Users

- New *local* account names are lowercased using `Locale.ROOT` and accepted only if they match `[a-z0-9._-]{3,64}`. Names with spaces, control characters, punctuation such as `:` or `@`, too short/long names, and `ldap:` / `oidc:` (case-insensitively) are rejected.
- Duplicate detection is case-insensitive, including collisions with older mixed-case local user records. Two concurrent attempts for the same normalized name are subject to the database uniqueness constraint; insert conflicts are returned as form errors.
- Local create, reset and bootstrap passwords require at least 12 Java characters and **at most 72 bytes encoded as UTF-8** to avoid BCrypt truncation. Multibyte passwords can reach 72 bytes before reaching 72 characters. Existing stored password hashes and existing usernames are **not modified**.
- Invalid create actions redirect to Admin → Users with a flash validation message, rather than an HTTP 500. Unknown roles are rejected, not silently downgraded to VIEWER.
- The UI offers matching HTML limits as a convenience. All security checks are repeated server-side.

## Additional TCP ClamD endpoints

- Additional engines require a 1–64 character name, a hostname or IPv4/IPv6 literal within 255 characters, port `1..65535` and supported platform `UNIX` or `WINDOWS`.
- The host field is **not** a URL, a `host:port` combination, or a filesystem path; absolute Unix sockets and embedded credentials are rejected. `host` and `port` remain separate fields.
- Names are checked for case-insensitive duplicates. Malformed ports and platforms, missing inputs and duplicate names render validation feedback through the existing error banner.
- **Compatibility:** The *default* engine remains owned by Configuration Center; its existing absolute Unix-socket capability and zero port are intentionally preserved. The strict TCP-only form rules apply to *additional* engines.

## Configuration Center and legacy settings

- The legacy `/admin/settings` POST **does not write settings**; it redirects to Configuration Center (already part of the v2.1 architecture).
- Configuration Center already validates numeric settings in `ConfigurationCatalog.validate` before persisting a review. Updated bounds: `app.concurrentScans` **1..64**, `app.upload.maxBytes` **1..2147483648**, and `app.watch.pollSeconds` **5..3600**. Non-numeric, negative and out-of-range values return explicit field error messages and are not saved.
- Any custom `app.storage.uploadDir` or `app.storage.quarantineDir` must be an **absolute, normalized, existing, writable directory inside the Web UI container** (no final-component symlink). Pre-create and grant write access to your non-root application UID (`10001`) before saving; do not give broad permissions.
- The shipped relative defaults `./data/uploads` and `./data/quarantine` remain valid to avoid breaking existing installations. Their existing `0700/0600` owner-only permissions and secure upload lifecycle remain unchanged.
- Upload and quarantine storage paths must still be non-overlapping, as enforced by existing combination validation.
- `SettingsService` also bounds the values it reads from legacy property files or deployment overrides; invalid values are **logged** and replaced with safe defaults, never used to allocate excessive scan workers.

## Acceptance tests and limitations

Added Java regression tests under `src/test/java/info/trizub/clamav/webclient/`:

- `service/Issue26UserValidationTest.java`: username format and length, reserved namespace, duplicate case, password ASCII/UTF-8 byte boundaries.
- `service/Issue26EndpointValidationTest.java`: name/host/port/platform validation and service-layer checks.
- `configuration/Issue26SettingsValidationTest.java`: numeric limits and filesystem path checks.
- `web/Issue26AdminFormsTest.java`: error-banner/redirect behavior for invalid input.

Existing `ConfigurationAllFieldsTest` was updated to create its alternative storage directory before review, consistent with the new existence requirement.

**Build status:** Isolated `javac` checks exercise the actual new endpoint validator and changed `UserService` (using test doubles for framework classes). The complete Spring Boot Maven suite and HTTP/database integration tests were **not** run in this environment; run `mvn -B verify` in a JDK 21 Maven environment before deploying.

No database migration is required. This candidate is not an image published to Docker Hub and has not been pushed to GitHub.
