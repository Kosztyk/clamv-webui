# Issue #46 validation results — offline source package

Baseline: `clamav-webui-v2.1.0beta2-issue45-supply-chain-ci-full-source.zip`.

Executed successfully in the packaging environment:

- `python3 scripts/check-issue46-service-layer.py`: **25/25 source contracts passed**.
- Previous issue #36–#45 source scripts: **11/11 scripts passed** (issue #38 has two scripts).
- `python3 scripts/test-issue45-security-gate.py`: **5/5 positive/negative scenarios passed**.
- Java 21 compiler parser: **298 source files parsed; 0 syntax errors**. This checks syntax, not dependency/type resolution.
- XML and YAML validation: `pom.xml`, all GitHub Actions workflow YAMLs, Dependabot configuration: **passed**.
- Publisher shell syntax: **passed**.
- Full-source ZIP structure, manifest, and publishing preflight: performed after packaging.

Not executed:

- Maven compilation and Spring/JUnit/real H2 rollback assertions, because Maven and required dependency artifacts are unavailable in this environment.
- Docker image build and end-to-end HTTP submission tests, because Docker is unavailable in this environment.

The new H2 acceptance tests are present in `UploadBatchPersistenceTest` and wired to `.github/workflows/issue46-service-layer.yml` for actual execution on GitHub Actions. The second-row constraint creates a database failure after the batch has already copied both uploaded files, allowing verification of atomic row rollback, file cleanup and no early scan enqueue.

Run on a Maven-enabled host:

```bash
python3 scripts/check-issue46-service-layer.py
mvn -B -ntp '-Dtest=UploadBatchPersistenceTest,DaemonPolicyScanBoundaryTest,DisabledEngineScanTest,ConfigurationConsumerWiringTest,RateLimitSecurityIntegrationTest' test
mvn -B -ntp verify
```

No environment variables, Configuration Center catalog changes, database migrations, or API response changes are required.
