# Source-package privacy and integrity — v2.1.0

Documentation edition: 1 November 2026.

This deliverable contains the complete application source, templates, static assets, database migrations, monitoring assets, CLI/MCP source, tests, Compose examples, documentation and publishing script. It contains no Git history, installed deployment state or generated client/server executables.

## Sanitization performed

The complete source was checked against the deployment identifiers and credentials supplied during support. Their addresses, database names, operating-system account/host names, workstation paths and truststore-specific paths were removed from examples, historical documents, comments and test fixtures. Configuration Center's external SQL URL templates now use the generic database name `appdb`. Compose examples use generic service names or reserved documentation domains and placeholders.

The test suite retains deliberately synthetic credentials and representative public/private/loopback addresses where those are necessary to exercise authentication, SSRF restrictions and networking. They are test data, not a user's deployment or production defaults. Public project names, repository URLs, original author credits and required third-party licenses remain intact. Binary documentation artwork was inspected; the existing historical screenshot shows generic loopback/default settings, while icons and font assets contain no deployment configuration.

Excluded runtime files include populated `.env`, data volumes/databases, recovery/bootstrap credentials, encryption keys, saved GUI settings/audits and private TLS/truststore files. Ignore rules keep these out of normal Git additions and the Docker build context. The publisher rejects runtime credential files, unsafe paths, duplicate archive members and symbolic links before authentication or extraction.

The optional REST example also uses a required private daemon-host input, Docker service DNS and a named temporary-upload volume. It contains no operator-specific host bind path. Its README example matches the shipped Compose, and API examples contain placeholders rather than issued credentials.

## File integrity

`SOURCE_MANIFEST.sha256` lists every application-package file except the manifest itself. The publisher verifies ZIP CRCs, manifest paths/file counts and every SHA-256 entry before preparing a repository update. Its `--check` mode is offline and requires no GitHub login:

```bash
bash publish-clamav-webui.sh /path/to/clamv-webui-v2.1.0-configuration-center-full-source.zip --check
```

To inspect an extracted source tree or full source ZIP with the reusable privacy rules:

```bash
python3 tools/audit-source-package.py .
python3 tools/audit-source-package.py /path/to/clamv-webui-v2.1.0-configuration-center-full-source.zip
```

For additional deployment-specific checks, pass `--deny-file /private/identifiers.json`. That private input accepts `literals` and `patterns` string lists; matched values are never printed. Keep that input outside the source tree. Built-in checks identify runtime-state files, common credential forms and workstation paths; no generic scanner can identify every unknown personal value by itself. The supplied-identifier checks and binary inspection were performed separately for this package.

The archive and manifest provide file-integrity evidence; they are not a claim that every arbitrary replacement ZIP is authenticated. Obtain the source and publisher from the intended release/delivery. Publishing updates the repository's current tracked files and retains history; it does not remove credentials that may have been committed in older history.

See [configuration through Compose or the GUI](../README.md#configure-the-app-with-configuration-center), [the publishing guide](PUBLISHING.md), [the 107-field audit](CONFIGURATION_FIELD_AUDIT.md) and [validation results](CONFIGURATION_CENTER_VALIDATION.md).
