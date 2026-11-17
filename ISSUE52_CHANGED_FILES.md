# Issue #52 — Changed files since issue #51 full-source archive

This is an additive patch based on the complete issue #51 source baseline,
including prior application and CI fixes (#36–#51). No application Java code,
database migration or Configuration Center setting was changed.

| File | Action | Purpose |
|---|---|---|
| `.github/workflows/ci.yml` | Modified | Weekly scheduling, Gitleaks current tree and history, generated canary, OSV, Trivy FS and HIGH/CRITICAL image scan, actionlint/hadolint, CodeQL Java/JS/Go matrix, single gate integration |
| `.github/workflows/release.yml` | Modified | Tag release repeats fixable HIGH/CRITICAL image and filesystem scans before GHCR release |
| `.github/dependabot.yml` | Modified | Weekly Go module updates for `/cli`, retaining Maven/Actions/Docker |
| `scripts/ci-detect-changes.py` | Modified | Classify heavyweight security scopes; include Go in CodeQL; full weekly runs |
| `scripts/ci-evaluate-gate.py` | Modified | Fail closed for every new scanner job |
| `scripts/check-ci-workflow.py` | Modified | Assert the complete workflow and 3-language SAST matrix |
| `scripts/check-issue45-supply-chain.py` | Modified | Retain original policy while supporting a stronger 3-language CodeQL matrix |
| `scripts/release-gate.sh` | Modified | Validate security workflow policy and mutation tests during release static gates |
| `scripts/check-issue52-security-gates.py` | Added | Security scanner policy, SHA pin, scope, exception and release coverage guard |
| `scripts/test-issue52-security-gates.py` | Added | Adversarial security job outcomes, scanner removal, severity weakening, mutable Actions, expired exception tests |
| `scripts/issue52-gitleaks-fixture.sh` | Added | Real Gitleaks negative test using a generated nonfunctional GitHub-token pattern |
| `osv-scanner.toml` | Added | Initially empty, reviewed, dated vulnerability suppression policy |
| `docs/ISSUE52_SECURITY_SCANNING_GATES.md` | Added | Usage, security properties, acceptance tests and known validation limits |
| `README.md` | Modified | Security CI and setup entry point |
| `docs/releases/v2.1.0beta2.md` | Modified | Release-note entry |
| `ISSUE52_CHANGED_FILES.md` | Added | This file |
| `ISSUE52_VALIDATION_RESULTS.md` | Added | Exact local validation limits and test commands |
| `SOURCE_MANIFEST.sha256` | Rebuilt | Archive-wide file checksums |

CI remains in **one** workflow (`ci.yml`) for the always-reporting `gate`;
no independent, path-filtered required check was introduced. Docker tag,
Compose defaults, package namespace and persisted application settings are
unchanged. Nothing was pushed to GitHub.
