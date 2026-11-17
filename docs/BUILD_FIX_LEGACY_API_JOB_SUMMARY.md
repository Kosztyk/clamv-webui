# Build fix: Legacy `/api/jobs` summary mapper (November 12, 2026)

## Symptom

`mvn verify` stopped during `maven-compiler-plugin:compile` with:

```text
ApiController.java:[60,55] incompatible types: invalid method reference
required: ScanJobSummary, String, ScanJobListDiagnostics
found:    ScanJobSummary
```

## Root cause

The cumulative API v1 diagnostics compatibility update replaced the
single-argument `ApiV1Dtos.Job.fromSummary(ScanJobSummary)` with a
three-argument method but did not update `ApiController.listJobs()`.

## Correction

Restored the original one-argument overload for the legacy `/api/jobs` route.
Its behavior is deliberately unchanged: a bounded summary preview without
full TEXT diagnostic columns, and details available at `/api/jobs/{id}`.
The three-argument overload for `/api/v1/jobs` remains unchanged, including
page-scoped signatures, quarantine outcome and sanitized error codes.

## Regression coverage

`ApiV1DtoTest.legacyApiJobsMethodReferenceKeepsOriginalSummaryContract`
compiles and exercises the exact `ApiV1Dtos.Job::fromSummary` method
reference used by the legacy controller, and checks that the three-argument
API v1 overload is still present. The test also checks that internal PATH
file locations are not included in the list response.

### Build and test

Run from the extracted project root (containing `pom.xml`):

```bash
mvn -B -Dtest=ApiV1DtoTest test
mvn -B verify
# Or rebuild the image from this source checkout:
docker build --progress=plain -t clamav-web-client:beta2-compilefix .
```

Validation performed while preparing this ZIP: actual Java 17 compilation
of `ApiController.java` and `ApiV1Dtos.java` against isolated dependency stubs,
legacy API method-reference smoke test, existing offline Java checks and
source/security static tests. These are not substitutes for a complete
Maven build or live Spring Boot integration tests.

No Flyway migrations, application settings, web templates, or Dockerfiles were
changed. All earlier cumulative issue fixes are retained.
