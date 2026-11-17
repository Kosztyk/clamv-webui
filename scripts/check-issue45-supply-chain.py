#!/usr/bin/env python3
"""Issue #45 offline gate: pins, CI block policies and vendored integrity.

No internet/network, Docker, or third-party modules required. This is NOT a
vulnerability scan; .github/workflows/ci.yml does that in CI.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_JQUERY_SHA = "fc9a93dd241f6b045cbff0481cf4e1901becd0e12fb45166a8f17f95823f0b1a"


def ensure(ok: bool, message: str) -> None:
    if not ok:
        raise AssertionError(message)
    print('PASS:', message)


def check_deps():
    body = (ROOT / '.github/dependabot.yml').read_text()
    ensure(re.search(r'(?m)^version:\s*2\s*$', body) is not None, 'Dependabot v2 schema')
    for eco in ('maven', 'docker', 'github-actions'):
        pattern = rf'(?m)^\s*- package-ecosystem:\s*["\']?{re.escape(eco)}["\']?\s*$'
        ensure(len(re.findall(pattern, body)) == 1, f'Weekly Dependabot: {eco}')
    ensure(len(re.findall(r'(?m)^\s*interval:\s*["\']?weekly["\']?', body)) >= 3, 'All 3 ecosystems scheduled weekly')
    ensure('Vendored browser assets' in body, 'Manual vendor update process identified')


def check_actions():
    files = sorted((ROOT / '.github/workflows').glob('*.y*ml'))
    ensure({ 'ci.yml', 'release.yml' } <= {f.name for f in files}, 'Unified PR gate and tag-only release workflows present')
    count = 0
    for file in files:
        source = file.read_text()
        uses = re.findall(r'(?m)^\s*-?\s*uses:\s*([^\s#]+)', source)
        for ref in uses:
            ensure(bool(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?@[0-9a-f]{40}', ref)),
                   f'Immutable action SHA in {file.name}: {ref}')
            count += 1
        ensure('docker build --pull' not in source, f'No mutable docker --pull build in {file.name}')
    ensure(count >= 16, 'All GitHub Actions are SHA-pinned')


def check_vendors():
    manifest = (ROOT / 'src/main/resources/static/js/VENDORED.md').read_text()
    matched = []
    for line in manifest.splitlines():
        if not line.startswith('|') or line.startswith('|---'):
            continue
        cols = [p.strip() for p in line.strip('|').split('|')]
        if len(cols) != 7 or not re.fullmatch(r'`[0-9a-f]{64}`', cols[4]):
            continue
        rel = cols[3].strip('`')
        check = cols[4].strip('`')
        asset = ROOT / rel
        ensure(asset.is_file() and asset.resolve().is_relative_to(ROOT.resolve()), f'Vendored file present: {rel}')
        ensure(hashlib.sha256(asset.read_bytes()).hexdigest() == check, f'Vendored SHA-256 matches: {rel}')
        matched.append((cols[0],cols[1],rel,check))
    ensure(len(matched) == 5, 'Five vendored JS/CSS assets inventoried')
    jquery = ROOT / 'src/main/resources/static/js/jquery-3.7.1.min.js'
    ensure(hashlib.sha256(jquery.read_bytes()).hexdigest() == EXPECTED_JQUERY_SHA, 'jQuery official 3.7.1 full-distribution SHA-256')
    ensure(b'jQuery v3.7.1' in jquery.read_bytes()[:150] and b'-ajax,' not in jquery.read_bytes()[:150], 'jQuery is full, not slim')
    ensure(not (ROOT / 'src/main/resources/static/js/jquery-3.6.0.min.js').exists(), 'Obsolete jQuery 3.6.0 removed')
    header = (ROOT / 'src/main/resources/templates/fragments/header.html').read_text()
    ensure('jquery-3.7.1.min.js' in header and 'jquery-3.6.0.min.js' not in header, 'Thymeleaf references updated jQuery')
    ensure('unverified legacy build' in manifest and 'provenance not recorded' in manifest, 'xterm unidentified version not fabricated')


def check_images():
    content = (ROOT / 'Dockerfile').read_text()
    bases = re.findall(r'(?m)^FROM\s+([^\s]+)', content)
    ensure(len(bases) == 3 and all(re.fullmatch(r'[A-Za-z0-9_.\-/]+:[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}', base) for base in bases), 'Main Dockerfile base images pinned by digests')
    pin = json.loads((ROOT / 'docs/security/clamd-image-pin.json').read_text())['image']
    ensure(bool(re.fullmatch(r'clamav/clamav:[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}', pin)), 'Scanner image has version and digest')
    ensure('FROM ' + pin in (ROOT / 'Dockerfile.clamd').read_text(), 'Scanner UID-aligned Dockerfile uses the verified digest')
    compose = (ROOT / 'docker-compose.yml').read_text()
    ensure('${CLAMAV_BASE_IMAGE:-'+pin+'}' in compose, 'Compose fresh clone defaults to immutable scanner image')
    ensure('CLAMAV_BASE_IMAGE='+pin in (ROOT / '.env.example').read_text(), 'Example .env pins scanner image')


def check_gates():
    source = (ROOT / '.github/workflows/ci.yml').read_text()
    ensure('pull_request:' in source and 'push:' in source and 'gate:' in source, 'Unified gates run on PRs and main pushes')
    ensure(re.search(r'uses:\s*aquasecurity/trivy-action@[0-9a-f]{40}',source) is not None, 'Trivy action pinned by SHA')
    ensure(re.search(r'(?m)^\s*severity:\s*CRITICAL\s*$',source) is not None and
           re.search(r"(?m)^\s*exit-code:\s*['\"]?1['\"]?\s*$",source) is not None,
           'Known critical vulnerabilities fail CI')
    ensure('vuln-type: os,library' in source and "ignore-unfixed: 'false'" in source, 'OS and library CVEs, including unfixed, checked')
    ensure('continue-on-error' not in source, 'Critical CVE gate cannot be ignored')
    ensure(re.search(r'uses:\s*github/codeql-action/init@[0-9a-f]{40}',source) is not None and
           re.search(r'uses:\s*github/codeql-action/analyze@[0-9a-f]{40}',source) is not None,
           'CodeQL Java SAST initializes and analyzes')
    ensure('security-events: write' in source and 'language: java-kotlin' in source and
           'languages: ${{ matrix.language }}' in source,
           'CodeQL has least-privileged analysis permissions and Java language')
    release = (ROOT / '.github/workflows/release.yml').read_text()
    ensure('uses: aquasecurity/trivy-action@' in release and
           'severity: CRITICAL' in release and "exit-code: '1'" in release,
           'Tag-gated GitHub prerelease also blocks CRITICAL CVEs')
    ensure(release.index('Block release on known CRITICAL') < release.index('Create DRAFT GitHub release'),
           'Tag-gated release is scanned before publication')
    ensure('docker build --progress plain --tag clamav-web-client:issue45-ci .' in source and
           'image-ref: clamav-web-client:issue45-ci' in source, 'Trivy scans the built candidate, not another image')


def main():
    check_deps(); check_actions(); check_vendors(); check_images(); check_gates()
    print('Issue #45 offline checks completed successfully (does NOT replace Trivy and Maven runtime checks)')


if __name__ == '__main__': main()
