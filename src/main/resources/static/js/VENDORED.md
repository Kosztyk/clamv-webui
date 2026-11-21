# Vendored browser libraries (issue #45)

These files are **self-hosted**; browser operation has no runtime dependency on a third-party CDN. SHA-256 checks protect against unreviewed local modification, not against upstream vulnerabilities.

| Library | Version | Upstream | Asset | SHA-256 | License | Provenance |
|---|---|---|---|---|---|---|
| jQuery | 3.7.1 | https://code.jquery.com/jquery-3.7.1.min.js | `src/main/resources/static/js/jquery-3.7.1.min.js` | `fc9a93dd241f6b045cbff0481cf4e1901becd0e12fb45166a8f17f95823f0b1a` | MIT | Verified official full distribution (not slim build) |
| Bootstrap JS | 5.3.3 | https://github.com/twbs/bootstrap/releases/tag/v5.3.3 | `src/main/resources/static/js/bootstrap.bundle.min.js` | `0833b2e9c3a26c258476c46266e6877fc75218625162e0460be9a3a098a61c6c` | MIT | Previously bundled; exact release artifact not independently re-fetched |
| Bootstrap CSS | 5.3.3 | https://github.com/twbs/bootstrap/releases/tag/v5.3.3 | `src/main/resources/static/css/bootstrap.min.css` | `3c8f27e6009ccfd710a905e6dcf12d0ee3c6f2ac7da05b0572d3e0d12e736fc8` | MIT | Previously bundled; exact release artifact not independently re-fetched |
| xterm JS | unverified legacy build | https://github.com/xtermjs/xterm.js | `src/main/resources/static/js/xterm-vendored.js` | `ca27ce1f70305d3285455c1d98b3774cc556c871397bae4e77dc0ef2566a9850` | MIT | Original version/build provenance not recorded; do not relabel without verification |
| xterm CSS | unverified legacy build | https://github.com/xtermjs/xterm.js | `src/main/resources/static/css/xterm-vendored.css` | `8baa5877506c5bebaeb458dbcce84e415211fad588410df31529d41b7f505eb1` | MIT | Original version/build provenance not recorded; matched to bundled JavaScript only |

## Upgrade and verification process

1. Check upstream release notes and security advisories at least monthly. Dependabot **does not** update manually copied JavaScript/CSS, so maintainers must manage these assets separately.
2. For jQuery, use the **full** production build from `https://code.jquery.com/jquery-<version>.min.js` (not the slim build). Compare its SHA-256 with an independently published release SRI digest. Change the versioned filename and the Thymeleaf header reference together; keep self-hosting.
3. For Bootstrap, update the official **matching** CSS and bundled JS versions as a set. Verify upstream release integrity and test the UI/navigation/modal flows.
4. For xterm, first establish a verifiable upstream source tag/build and compatibility with `native-clamdtop.js` (the legacy bundle uses `new Terminal()`, `term.on('data', ...)`, `term.destroy()` and direct renderer measurement). Until provenance can be established, this version is **unverified**, not falsely described as an official version. Future xterm upgrades require browser/WebSocket PTY smoke tests and review of layout changes.
5. Recalculate the SHA-256 of every changed file (`sha256sum <file>`) and update the table in the **same PR** as the binary change. Preserve copyright/license notices. CI enforces checksums and action SHA pins via `scripts/check-issue45-supply-chain.py`.
6. Run `python3 scripts/check-issue45-supply-chain.py`, `mvn -B verify`, browser smoke tests, and Docker Trivy + CodeQL workflows before merge. Treat newly reported CRITICAL vulnerabilities as blocking; fix or explicitly review exposure, do not silently set `continue-on-error`.

## Reference image digests

Java builder/runtime images and the ClamAV scanner are pinned in `Dockerfile`, `Dockerfile.clamd`, `docs/security/clamd-image-pin.json` and Compose. Build tooling uses a pinned base, but `apt` repositories, Trivy's CVE database and Maven metadata can still evolve: **the build is not bit-for-bit reproducible**. The Trivy database is intentionally updated for current findings. `docker build --pull` in CI has been removed to avoid needlessly updating mutable references; digest pins, not `--pull`, enforce image identity.
