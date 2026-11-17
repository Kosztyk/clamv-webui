# Publisher fixture repair validation — 2026-11-14

## Source-level and local integration validation

- Baseline audit previously reproduced **10 failing of 13** publisher regression tests, caused by stale source ZIP roots.
- **Full corrected suite: 14/14 tests passed in one run (63.385 seconds).** This includes all 13 original tests and one new legacy-root rejection test. The legacy root is rejected without invoking `gh`.
- The tests run the actual `publish-clamav-webui.sh` against fresh local bare Git remotes and a synthetic offline `gh` executable. No real GitHub authentication, push, or PR is performed.
- Coverage includes offline `--check`, direct push, dry run, idempotent update, branch protection/PR fallback, explicit PR mode, rejected branch push, default branch discovery, SHA-256 tampering, duplicate ZIP members, private-address privacy failure, runtime/secrets/path traversal, symbolic links and legacy archive-root rejection.
- The fixture now includes actual `tools/audit-source-package.py`, so its privacy tests exercise that production code path.

## Packaging checks

- Real publisher offline validation: `bash publish-clamav-webui.sh <archive.zip> --check` — **PASS**, 1,386 source files validated, no GitHub authentication or network access used.
- ZIP CRC and SHA-256 source manifest regenerated/validated.
- Changed file scope compared with the previous fixed archive checked.
- Full Maven/JUnit/ClamAV runtime tests are not required for this Python test-only change and were not run here. Previous limitations still apply for the overall beta2 release.

## Reproduce

```bash
python3 scripts/test-publish-clamav-webui.py
bash publish-clamav-webui.sh /path/to/full-source.zip --check
```

**This is an offline local source candidate, not an actual GitHub publication or new release.**
