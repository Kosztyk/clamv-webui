# Issue #56 — Validation and disclosure limits

## Completed offline checks

- `python3 scripts/check-issue56-release-docs.py`: 12/12 checks passed.
- `python3 scripts/test-issue56-release-docs.py`: 8/8 tests passed.
- `python3 -m unittest scripts/test-publish-clamav-webui.py -k embedded_private`: synthetic unsafe ZIP refused before authentication.
- `python3 tools/audit-source-package.py .`: every extracted source file and static asset was inspected, no unapproved RFC1918 literal; results include explicit synthetic private-address test fixtures and false-positive SVG drawing-coordinates handling.
- Initial baseline inventory: 26 literal RFC1918 matches across 17 files. Real homelab addresses and incidental Docker-network example addresses were replaced with reserved documentation values; private-address security regression fixtures remain intact.
- All previous issue #36–#46 source-policy check scripts passed.
- Prior unified CI, versioning, GHCR, release, image signing and issue #52–#55 source-policy and adversarial tests passed individually.
- Release docs, `SECURITY.md`, PR template, `CODEOWNERS`, release-note category rules and CI audit integration verified.
- Publisher preflight, ZIP integrity, source privacy audit and SHA-256 manifest were verified against the final archive before delivery.

## Not tested or not possible through this connection

- Real Maven/JUnit, Docker, Go, Trivy, Sigstore, Testcontainers, browser and protected GitHub release execution require runtime CI runners.
- GitHub private vulnerability reporting **cannot be enabled by committing a file**, and the connected GitHub tool does not expose a repository-settings write action for it. An administrator must enable and verify it in GitHub Settings → Code security. This acceptance criterion remains pending.
- Code-owner review is enforceable only with a branch ruleset and an eligible independent reviewer; `CODEOWNERS` alone is informational.
- Local full-source archive inspection is authoritative for the delivered snapshot. Live `main` targeted GitHub code search does not prove every unindexed historical file, Git commit, fork, deleted asset, workflow artifact or prior GitHub release is free of old identifiers. A Git history scan/remediation requires a Git clone or GitHub administration when there is evidence of historical leakage.

The audit never echoes sensitive IP values into public logs or documentation. All recorded private-network test addresses are intentional synthetic fixtures.
