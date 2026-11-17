# Issue #55 validation report — 13 November 2026

Baseline: complete issue #54 candidate, canonical Maven version `2.1.0-beta2`. GitHub issue: `Kosztyk/clamv-webui#55`.

## Completed source and offline validations

- `python3 scripts/issue55-api-contract.py` — **PASS**: all 16 API paths, 17 method/path operations, Java controller declarations, Go CLI request paths, documented JSON success schemas and protected required fields agree.
- `python3 scripts/check-issue55-api-contract.py` — **PASS**: Spectral, oasdiff, exact historical Git tag, runtime MockMvc suite, CLI live integration and new CI/release job are wired into the single blocking gate.
- `python3 scripts/test-issue55-api-contract.py` — **PASS**: deletion of an endpoint, removal of a required response field, undocumented Go CLI path, tampering with required gate dependencies and mutable comparator version are rejected; a stub oasdiff runner blocks an intentionally removed released endpoint and writes evidence. The stub test **does not** certify the real oasdiff binary.
- `scripts/check-ci-workflow.py`, `scripts/test-ci-gate.py` — **PASS** after updating the stable job inventory from 16 to 17 jobs.
- Issue #36–#54 inherited source, version, security, static-analysis and release policy checks — **PASS** independently (the issue #53 adversarial fixture was updated to account for the inserted `api-contract` job).
- Python source/modified YAML parse — **PASS**.
- JDK 21 `JavacTask.parse()` over the entire `src` tree — **PASS**, 299 Java source files, zero syntax errors. **This is only a syntax check**, not compilation or JUnit verification.
- Historical `V2.0.0` tag and the presence of its OpenAPI YAML — **verified against the GitHub repository**; current source copied from the issue #54 source ZIP.

## Not executed / acceptance pending

This environment has JDK 21 but **no Maven and no Docker**, and Go 1.23 rather than the CI-required Go 1.25 toolchain. The actual Spring MockMvc/DTO tests, Maven `verify`, Spotless formatting, full CLI/Spring integration, a real Spectral lint, real oasdiff `--fail-on WARN` comparison and GitHub required `gate` could **not** run locally. They **must pass in GitHub Actions** before closing issue #55 or publishing a release. A combined `bash scripts/release-gate.sh --static-only` run exceeded the local execution limit; its individual source checks passed, but the combined run is not claimed as green.

The source checker and simulated oasdiff negative test prove CI policy wiring only. A real GitHub PR is required to establish that the current contract has no accidental differences against the historical published `V2.0.0` spec. If the real comparator reports historical breaking changes, investigate and fix or version the route rather than silently disabling the check.

## Required live commands

```bash
python3 -m pip install 'PyYAML==6.0.2'
python3 scripts/issue55-api-contract.py
python3 scripts/check-issue55-api-contract.py
python3 scripts/test-issue55-api-contract.py

# From the GitHub checkout, with Node 22, Go 1.25, Maven and full Git history:
npx --yes --package=@stoplight/spectral-cli@6.17.1 spectral lint --ruleset .spectral.yaml --fail-severity error src/main/resources/openapi/clamav-v1.yaml
go install github.com/oasdiff/oasdiff@v1.11.8
python3 scripts/issue55-api-breaking.py --oasdiff "$(go env GOPATH)/bin/oasdiff"
mvn -B -ntp '-Dtest=Issue55OpenApiContractIntegrationTest,ApiV1ContractIntegrationTest,ApiV1SecurityIntegrationTest' test
mvn -B -ntp clean verify
mvn -B -ntp -Pci-quality spotless:check
```

Acceptance: A GitHub PR removing a documented endpoint or required stable response field must fail `gate`; a breaking OpenAPI change must fail the `api-contract` job, with oasdiff evidence uploaded. The existing `cli-integration` job must execute against the built Spring Boot JAR for changes under API v1.
