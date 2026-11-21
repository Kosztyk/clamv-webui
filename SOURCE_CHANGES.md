# Direct source changes (not a patch)

- `src/main/java/info/trizub/clamav/webclient/service/SettingsService.java`: safe new-install defaults (INSTREAM, fallback true), preserve environment and saved settings precedence.
- `src/main/resources/configuration-center/catalog.json`: matching defaults and deployment prerequisite descriptions.
- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java`: clarify native path-access diagnostic (behavior unchanged).
- `docker-compose.yml`: fresh-install secure template; no public 3310 or full data mount; no deployment secrets or hardcoded host IPs. Do not overwrite an existing deployment's Compose.
- `docker-compose.native-path.yml`, `Dockerfile.clamd-uid`, `prepare-upload-dir.sh`, `verify-native.sh`: optional native filename mode, requiring runtime validation.
- `docs/PRODUCTION_SCANNING.md`: upgrade and validation instructions.
- The original archive's private `conf/` (except example), `data/`, compiled `target/`, and CLI release binaries are intentionally omitted.

No edits to `SecurityConfig.java`, `PrivateFiles.java`, `UploadStorageService.java`, `QuarantineService.java`, identity, API token, audit, DB migration or session/security filters.
