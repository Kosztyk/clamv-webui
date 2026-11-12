# LDAP/OIDC stored-secret protection — issue #16

Provider credentials remain write-only. Knowing an administrator password or holding an administrator session does not authorize redirecting a saved credential to a different destination without supplying that credential again.

## Test and Save behavior

| Draft | Result |
|---|---|
| Unchanged destination and bind/client identity; blank credential | Reuse the current credential for Test or Save |
| Changed protected field; saved credential exists; blank credential | Reject with a message to re-enter the bind password or client secret; no connection test runs and no configuration is saved |
| Changed protected field; credential explicitly re-entered | Validate and test/save using the submitted credential; entering the same credential explicitly also works |
| No existing credential | Allow initial configuration, subject to ordinary provider validation |
| Disabled provider; protected field changed | Apply the same re-entry requirement, preventing later activation with a redirected saved credential |
| Disabled provider; credential explicitly cleared along with its GUI file source | Allow reconfiguration without reusing the old credential; enabling still requires valid settings |

Protected LDAP fields are URL, StartTLS, base DN, bind/manager DN and referral policy. Protected OIDC fields are issuer URL and client ID. Values are compared after existing field normalization. LDAP referrals are included because they affect forwarding of directory credentials.

The check runs in shared configuration review, before a connection test and again before apply. It covers the administration Configuration Center and the separate database-independent setup/recovery API. Stale reviewed configuration must be reloaded. The retired Authentication page redirects to Configuration Center and its old POST routes remain disabled; the legacy settings service also enforces the rule for compatibility callers.

To move a provider, enter its new destination and the known bind password/client secret together, run Test, then Review and Apply. Test never saves; provider changes still require restart. Other existing validation, TLS trust and administrator verification continue to apply. Rejection messages, masked reviews and audit records do not disclose the stored secret.

## Mounted credentials and deployment overrides

A file-backed saved credential receives the same protection. Re-submitting or changing only its file path does not count as credential re-entry. For a GUI-managed credential, enter the known credential explicitly when changing the destination; this switches the saved source to inline encrypted storage under the existing source-selection rules.

If a deployment property or mounted deployment secret controls the credential, its inline editor stays locked. Change the destination and credential together through the deployment configuration, review the actual destination, and recreate the service. The GUI cannot override deployment-controlled credentials. Explicitly clearing a GUI credential also removes its GUI file source; an active provider cannot be saved without its required credential.

## Encryption and upgrade

Central configuration already authenticates each encrypted value with its full property key as AES-GCM associated data, including the provider namespace. Newly written legacy SQL credentials now use `gcm2:` ciphertext with associated data `clamav-auth-provider:ldap` or `clamav-auth-provider:oidc`. Swapping a new ciphertext between provider rows fails authentication.

Existing `gcm1:` SQL credentials remain readable with the existing key. An unchanged successful legacy save upgrades that row to `gcm2:` without requiring secret rotation. Untouched old rows retain their old format; no eager database rewrite or schema migration is performed. Retain `conf/auth-settings.key` (or the configured legacy key), `conf/configuration-center.key`, configuration and database backups. A rollback to code that only understands `gcm1:` needs its matching pre-upgrade database/configuration backup.

## Verification

`ConfigurationProviderSecretTest` covers all seven protected fields, unchanged settings, explicit re-entry, file-backed secrets, explicit clearing, fabricated review rejection and recovery tests with a local socket confirming no outbound connection. `ConfigurationProviderSecretUiTest` covers administration Test/Review/Apply, using a mocked connection tester for permitted drafts. `AuthenticationSettingsSecretBindingTest` covers legacy validation/save, provider ciphertext swaps and old-format upgrade.

Run the full Java checks with `mvn verify`. After building, run `python3 scripts/test-provider-secret-runtime.py --jar target/clamav-web-client-2.1.0.jar` for real HTTP setup, administration and restart checks with synthetic credentials and local connection sentinels. These checks do not claim live LDAP bind or OIDC authorization-code validation against an external provider. Exact delivery results are recorded in [the validation report](security/provider-secret-binding-validation.json).
