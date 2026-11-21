# Issue #55 — changed/new files relative to issue #54

- `src/main/resources/openapi/clamav-v1.yaml`: explicit success/error schemas and protected response fields for stable API v1 routes; no route behavior changes.
- `src/test/java/info/trizub/clamav/webclient/api/v1/Issue55OpenApiContractIntegrationTest.java`: new Spring MockMvc mapping/DTO/JSON response contract regression suite.
- `pom.xml`: test-only SnakeYAML parser to read Maven-filtered OpenAPI.
- `.spectral.yaml`: repository Spectral OpenAPI linting ruleset.
- `.github/workflows/ci.yml`: new `api-contract` job; pinned Spectral/oasdiff, policy tests, change selection, reports; depends on existing required gate.
- `.github/workflows/release.yml`: release-time Spectral/oasdiff preflight before draft creation and image publication.
- `scripts/ci-detect-changes.py`: `api` classification and mandatory real Go CLI integration when API v1 controller/spec changes.
- `scripts/ci-evaluate-gate.py`, `scripts/check-ci-workflow.py`: add the new API contract job to the required gate and its structural invariant.
- `scripts/issue55-api-contract.py`, `scripts/issue55-api-breaking.py`, `scripts/check-issue55-api-contract.py`, `scripts/test-issue55-api-contract.py`: fast source validation, fail-closed historical/PR-base compatibility, policy and adversarial tests.
- `scripts/release-gate.sh`: include source contract validation in the static preflight.
- `scripts/test-issue53-java-quality.py`: retain valid regression expectation after addition of the API job to `gate`.
- `docs/api-baselines/issue54-clamav-v1.yaml`: read-only snapshot of the prior full-source candidate for offline review (real CI compares the historical Git tag).
- `README.md`, `docs/ISSUE55_API_CONTRACT_GATE.md`, `docs/RELEASE_NOTES_V2_1_0.md`, `ISSUE55_VALIDATION_RESULTS.md`: new feature and validation documentation.
- `SOURCE_MANIFEST.sha256`: regenerated for the full source archive.

Previous issues #36–#54 and their source files are retained. No production authentication/security semantics, persistence schema, or Compose defaults are changed by issue #55.
