# Issue #55 — API v1 contract, response validation and breaking-change gate

**Status:** source implemented using the complete issue #54 candidate (`2.1.0-beta2`). The GitHub repository has **not** been modified by the archive creation. The actual CI acceptance tests must run on GitHub.

## Why this is necessary

The published `src/main/resources/openapi/clamav-v1.yaml`, Spring REST controllers and the Go `clamctl` client were maintained separately. A removed path, narrowed response or renamed field could break existing automation while individual Java and Go tests passed.

## Required CI checks

The existing path classifier exposes `changes.outputs.api`. OpenAPI spec, `ApiV1Controller`/`ApiTokenAdminController` or other v1 Java handlers, Go CLI source, CI/release workflow and API contract policy changes activate **`api-contract`**. All API/controller edits also activate `cli-test`, `cli-build`, `cli-integration`, and Java tests, so the actual Go binary is exercised against an actual built Spring JAR.

`api-contract` executes the following checks; all are blocking:

1. **`scripts/issue55-api-contract.py`**: parses the OpenAPI YAML with PyYAML, checks every controller `@*Mapping` pair, verifies that `clamctl` calls documented endpoints, validates success response schemas and protected required JSON keys. This is a fast source inventory, **not** a substitute for the Spring runtime test.
2. **Spectral** (`@stoplight/spectral-cli@6.17.1`, Node 22): lints the OpenAPI 3.0 document under `.spectral.yaml` (`extends: spectral:oas`, required OAS schema validation), failing at severity ERROR or higher. Historically omitted operation IDs and descriptions do not become release blockers solely due to formatting policy.
3. **oasdiff v1.11.8** (Go 1.25): `oasdiff breaking --fail-on WARN` compares the candidate contract against **both** the OpenAPI at the existing historical **`V2.0.0`** Git release tag and (for PR/push runs) the exact base commit SHA from the GitHub event. No mutable `latest` comparator, no missing-reference fallthrough. The previous release tag really contains `src/main/resources/openapi/clamav-v1.yaml` and was verified through GitHub.
4. **Spring MockMvc/JUnit**: `Issue55OpenApiContractIntegrationTest` runs in the Java JDK 21/25 tests, introspects Spring `RequestMappingHandlerMapping` to compare actual route/method registrations, compares Java DTO record fields with OpenAPI schemas, and validates actual JSON against the resolved OpenAPI schema for GET success and HTTP 201/400/404 responses. Required fields, types, arrays, enums, `$ref`, nullable fields and timestamps are checked.
5. **Go CLI integration**: the existing `scripts/test-clamctl-integration.py` runs an actual CLI artifact against a real isolated Spring server with fake ClamD. The CLI job is required by the same always-reporting `gate` on any API/controller change.

The final GitHub branch protection check remains **only `gate`**. A docs-only PR still runs the static validators and receives an unconditional `gate`, without the heavy API/Java/CLI jobs. The existing supply-chain security scans, Java quality gates, signed releases and GHCR digest verification remain intact.

## Breaking-change policy

The `V2.0.0` tag uses the old uppercase tag convention. This is **only a historical input baseline**: all **new** releases continue to use lowercase `vMAJOR.MINOR.PATCH[-prerelease]` tags and canonical version data from the root `pom.xml`. The offline `docs/api-baselines/issue54-clamav-v1.yaml` is the pre-change snapshot for developer review, not a replacement for the actual `V2.0.0` release input.

`oasdiff` treats removal of a path or method, deleting an expected response field, incompatible parameter/schema changes or response code removals as incompatibilities. ERR and WARN findings **fail the job and block a release**. The policy intentionally requires fixing the incompatibility or publishing a new versioned API route; simply increasing the artifact version does **not** silently exempt a breaking change to the stable `/api/v1` contract. If a future major version intentionally breaks clients, it must be introduced under a new versioned prefix with its own explicit contract and baseline.

`artifacts/api-contract/previous-release.txt` and `change-base.txt` contain oasdiff evidence and are uploaded as a CI artifact where available. An inaccessible release tag, invalid SHA, missing comparator, unparsable contract or failed command is a **hard failure**, not an automatic pass.

## Source and runtime changes

- Expanded the OpenAPI response schemas for database readiness, failed ClamD health, job progress, upload/path acceptance, audit pagination and token issuing/listing; explicit `required` keys for stable DTOs and errors.
- Added a test-only SnakeYAML dependency for reading **the actual Maven-filtered OpenAPI resource** in MockMvc tests. Production dependencies and routes remain unchanged.
- No new API endpoint, Configuration Center setting, authentication bypass, Flyway migration, Docker environment variable or runtime configuration is introduced.

## Local validation

```bash
python3 -m pip install 'PyYAML==6.0.2'
python3 scripts/issue55-api-contract.py
python3 scripts/check-issue55-api-contract.py
python3 scripts/test-issue55-api-contract.py

# On a workstation with Node 22, Go 1.25 and Maven installed:
npx --yes --package=@stoplight/spectral-cli@6.17.1 spectral lint \
  --ruleset .spectral.yaml --fail-severity error \
  src/main/resources/openapi/clamav-v1.yaml
go install github.com/oasdiff/oasdiff@v1.11.8
python3 scripts/issue55-api-breaking.py --oasdiff "$(go env GOPATH)/bin/oasdiff"
mvn -B -ntp '-Dtest=Issue55OpenApiContractIntegrationTest,ApiV1ContractIntegrationTest,ApiV1SecurityIntegrationTest' test
mvn -B -ntp clean verify
```

For the historical release check, run commands inside a real Git checkout with access to the full history and tags. The supplied source ZIP alone does not contain `.git` history and cannot perform tag-based `oasdiff` comparison without a repository clone. A `v2.1.0-beta2` release must be based on the exact green CI commit; `v2.1.0` requires changing the Maven version to `2.1.0` first, consistent with issue #50.

## Acceptance testing

- Removing a route from OpenAPI, a Java controller, or the Go client fails CI (static or runtime contract).
- Removing a protected response property from the schema fails the fast inventory, and dropping/renaming one of the Java DTO properties fails the MockMvc/JUnit contract check.
- A real incompatible change relative to `V2.0.0` or the PR base produces a blocking `oasdiff breaking --fail-on WARN` status.
- The actual `cli-integration` test is selected and must succeed on controller/spec changes.
- The release workflow repeats Spectral and oasdiff before JAR, signed CLI or GHCR release publication.

**Validation limit:** Maven, the real Spectral binary, live oasdiff, and the GitHub runner were not executed while preparing this source candidate. See `ISSUE55_VALIDATION_RESULTS.md` for what was and was not verified.
