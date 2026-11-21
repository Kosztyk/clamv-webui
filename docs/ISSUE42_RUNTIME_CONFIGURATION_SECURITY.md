# Issue #42 — tracked runtime configuration and webhook URL migration

## Impact and change

`conf/clamav-web-client.properties` is a **runtime file** rewritten by
`SettingsService`. Git must never track it: webhook destinations can embed bearer
credentials and webhook URLs. The only distributed file under `conf/` is now
`clamav-web-client.properties.example`, a credential-free template.

`.gitignore` ignores all contents of `conf/` except that template, including
`clamav-web-client.properties`, `integrations.properties`, Configuration Center
storage, keys, audit records and recovery credentials. Docker Compose continues
to mount `./conf:/app/conf` and the Java process creates the runtime file on
startup. No deployment configuration or database migration is required.

## Encryption and migration

The webhook URL field is already marked `secret` in the Configuration Center
catalog and the ConfigurationStore uses authenticated AES-GCM encryption with a
local `configuration-center.key`. This change hardens the legacy path:

1. On startup, a non-empty `app.webhook.url` in the legacy properties is copied
   to the encrypted store **only when no saved value exists**.
2. Migration must succeed before the plaintext URL is removed. If encrypted
   storage is unavailable, startup fails closed without silently erasing the
   legacy credential or continuing plaintext persistence.
3. After encrypted save succeeds, the URL is scrubbed from the legacy
   properties and the file is rewritten atomically. A saved empty value is
   considered an explicit clear and must not be overwritten by older plaintext.
4. The legacy Settings page routes secret field edits through encrypted
   Configuration Center. It rejects secret writes if that store is unavailable.
5. Preserve and back up both `configuration-center.properties` and
   `configuration-center.key`: losing the key makes the saved secrets unreadable.

The legacy file can still hold **nonsecret** settings such as scan roots and
upload limits. The encrypted Configuration Center remains the proper UI for
webhook URL and signing secret.

## Upgrade and Git procedure

On an existing clone, after backing up the `conf` volume, remove the file from
Git **index only** (not from disk) and commit the removal along with the example
and new `.gitignore`:

```bash
git rm --cached --ignore-unmatch conf/clamav-web-client.properties
git add .gitignore conf/clamav-web-client.properties.example
git commit -m "fix: untrack runtime webhook settings (#42)"
```

Do not run `git rm` without `--cached` against an active deployment, because
that deletes your local configuration. Source-only archive publication via the
included `publish-clamav-webui.sh` also deletes any previously tracked runtime
file from the repository index. It never includes the local runtime file.

If a webhook URL was previously committed to any branch, rotate it. Removing
it from the current branch does **not** erase the secret from Git history.

## Acceptance tests

```bash
python3 scripts/check-issue42-runtime-config.py
mvn -B -Dtest=Issue42LegacyWebhookMigrationTest test
# after launching the application from a fresh clone:
git status --porcelain
# must be empty (assuming no other local edits)
git ls-files -- conf
# should show only conf/clamav-web-client.properties.example
```

The offline test creates a temporary Git repository with the actual ignore
rules and simulates startup-generated runtime files. It does not substitute for
starting the real application; the real startup/git status check remains a
manual or integration validation step.
