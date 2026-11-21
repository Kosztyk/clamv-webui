# Issue #53: Java quality gates

This change extends the **single required GitHub Actions `gate`** from issue #48. It does not introduce a separately required check or change application, database, or Configuration Center behavior.

## What runs

| Where | Enforcement | Generated evidence |
|---|---|---|
| `java-tests` (Temurin 21 and 25) | `mvn clean verify`, tests, JaCoCo `check` | Surefire, Failsafe, `target/site/jacoco/`, `target/jacoco.exec` |
| `java-quality` (Temurin 21) | Maven Enforcer convergence, SpotBugs with FindSecBugs, PMD, Spotless `check` | `target/spotbugs.xml`, `target/pmd.xml` |
| Release `release-tests` | Maven full gate then CI quality profile, Spotless | release test and static-analysis artifacts |
| `gate` | Reject failed, cancelled or unexpectedly skipped `java-quality` and `java-tests` | always-reporting required status |

The Maven Enforcer plugin runs basic requirements in **ordinary** `mvn verify`: Maven 3.9+, Java 21+, no duplicate dependency declarations, and no SNAPSHOT dependency in non-SNAPSHOT releases. The `ci-quality` Maven profile adds dependency convergence; a nonconverging dependency tree fails CI.

Java 17 is **not** a compatible build target in this branch: `pom.xml` sets `java.version` to 21 and production uses Java 21 APIs. A 17/21 test matrix would fail by design. The CI matrix instead validates the production JDK **21** and forward-compatibility JDK **25**; the Docker runtime remains JDK 21. JaCoCo 0.8.16 supports both.

## JaCoCo coverage policy

JaCoCo enforces an 82% application line-coverage floor. The rebuilt source measured 83.37% on Java 21. CI also compares the actual coverage report with the PR or push base using the same JaCoCo configuration. The Mockito Java agent remains enabled in test forks.

Some narrowly scoped DB migration profile runs intentionally set `-Djacoco.skip=true` because they execute only Failsafe IT subsets. Full `java-tests` and release `mvn clean verify` retain mandatory coverage enforcement; the quality report-only invocation skips unit tests and coverage to avoid misleading results.

## Existing static-analysis debt and formatting

The cumulative revision applies Google Java Format to all Java source and test files. Spotless checks subsequent changes against the exact PR or push base and the release job checks the tagged commit against its first parent. Run `mvn -B -Pci-quality -Dspotless.ratchet.ref=origin/main spotless:apply` from a Git checkout to format your changes.

The Maven `ci-quality` profile generates SpotBugs 4.10.5.0 with FindSecBugs 1.14.0 and PMD 3.28.0 reports. The `java-quality` job also analyzes the exact PR or push base with the same rules. `scripts/issue53-compare-quality.py` rejects findings above the existing counts in the base or the reviewed `config/java-quality-baseline.json`. The comparison keys use rules, classes and members for SpotBugs and rules and file paths for PMD, ignoring changes in line numbers. Missing or incomplete reports and scanner failures block CI. FindSecBugs disables `OverlyPermissiveFilePermissionDetector` because the detector crashes in the analyzer. Integration tests exercise the file permissions.

## Running the gates

Within a full Git checkout (not the source ZIP alone):

```bash
# Full JDK 21 test and coverage build.
mvn -B -ntp clean verify

# Static report generation, convergence and changed-file format enforcement.
git fetch origin main
mvn -B -ntp -Pci-quality -DskipTests -Djacoco.skip=true verify spotless:check

# Deterministic offline wiring, negative tests and baseline comparator checks.
python3 scripts/check-issue53-java-quality.py
python3 scripts/test-issue53-java-quality.py
```

To compare the new SpotBugs/PMD findings against a historical commit, `java-quality` does the following automatically:

1. Determines the immutable PR base or previous main commit SHA, then creates a detached temporary Git worktree.
2. Injects the **candidate** `ci-quality` Maven profile into the baseline POM to keep scanner versions and rules identical, without changing the historical commit.
3. Builds each revision with `-Pci-quality -DskipTests -Djacoco.skip=true`; baseline enforcement is skipped only for dependency-convergence checks, which are applied strictly to the candidate.
4. Requires valid `spotbugs.xml` and `pmd.xml` reports from both revisions; rejects any new findings. Uploads candidate reports even on failure.

## CI acceptance checks and limitations

- A formatting violation in a changed Java file must fail `java-quality` and thus `gate`.
- New SpotBugs, FindSecBugs, or PMD findings must fail compared with the base, even if the total number of findings drops elsewhere.
- Dropping below the JaCoCo threshold must fail the JDK 21/25 test jobs and thus `gate`.
- Dependency convergence violations and non-release dependencies in a release are build errors.
- A documentation-only PR can skip Java jobs while retaining the same `gate` check.

The comparison consumes native SpotBugs XML and PMD XML. It blocks unreadable or incomplete reports and findings above the audited counts in `config/java-quality-baseline.json` or the same-rule base checkout. FindSecBugs 1.14.0 crashes while analyzing the POSIX permission helper with its `OverlyPermissiveFilePermissionDetector`. The profile omits that detector and relies on upload-mode and isolation tests for this behavior.
