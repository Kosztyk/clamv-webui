# Security issue #16: stored provider secrets could be redirected

This cumulative source edition retains issues #13–#15 and requires explicit credential re-entry when an LDAP destination/bind identity or OIDC issuer/client ID changes. Unchanged destinations still accept a blank secret field. Shared validation covers Configuration Center, setup/recovery, review/apply and the compatibility settings service; rejected drafts make no provider connection and change no saved settings.

Newly saved legacy SQL credentials are cryptographically bound to the provider. Existing credentials and keys remain usable; no schema migration or forced rotation is required.

See [behavior, protected fields and upgrade details](docs/PROVIDER_SECRET_BINDING.md), [validation results](docs/security/provider-secret-binding-validation.json) and [the cumulative changed-file list](CHANGED_FILES.md).
