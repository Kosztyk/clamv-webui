# Build fix: Issue35MetricCardinalityTest register cleanup (Java 17)

## Problem

The Docker build `RUN mvn -B -q verify` stopped during Maven `testCompile`:

`SimpleMeterRegistry` and `PrometheusMeterRegistry` are not `AutoCloseable` in the Micrometer versions selected by the project, so they cannot be declared in Java `try (var registry = ...)` resource headers.

## Correction

Changed exactly four method-level registry declarations in
`src/test/java/info/trizub/clamav/webclient/metrics/Issue35MetricCardinalityTest.java`
to `var registry = new ...; try { ... } finally { registry.close(); }`.

This is the same cleanup pattern already used by `ScanMetricsTest.java`.
It preserves all test assertions, the 10,000-signature cardinality check,
and explicit registry cleanup without requiring `AutoCloseable`.

Production code, application properties, dependency versions, Dockerfiles,
Flyway migrations, Configuration Center settings and all other tests are unchanged.

## Verification

The four try-with-resources declarations were removed and replaced with explicit
`finally` blocks. Java 17 isolated syntax/compilation and existing static
project checks can be run in this environment; Maven and Docker are not installed
here, so a full `mvn verify` must be run on a build machine.

```bash
mvn -B -DskipTests test-compile
mvn -B -q verify
```
