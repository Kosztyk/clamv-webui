#!/usr/bin/env python3
"""Validate release-version provenance without rewriting source or secret settings.

Canonical source: root pom.xml <project><version>. Maven filters the deliberately
unique @@project.version@@ token into application.properties, OpenAPI and visible
UI resources. Compose uses an intentionally non-versioned local tag; release tags
and CLI builds are derived from the POM. Historical release documentation is not
an active version declaration and is left untouched.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = '{http://maven.apache.org/POM/4.0.0}'
VERSION_RE = re.compile(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?\Z')
TOKEN = '@@project.version@@'
FILTERED_FILES = (
    'application.properties',
    'openapi/clamav-v1.yaml',
    'configuration-center/setup.html',
    'templates/login.html',
    'templates/error.html',
    'templates/fragments/shell.html',
)


def pom_version(root: Path) -> str:
    version = ET.parse(root / 'pom.xml').getroot().findtext(NS + 'version')
    if not version or not VERSION_RE.fullmatch(version):
        raise ValueError(f'pom.xml: invalid project SemVer {version!r} (example 2.1.0 or 2.1.0-beta2)')
    return version


def ref_tag() -> str | None:
    if os.environ.get('GITHUB_REF_TYPE') == 'tag':
        return os.environ.get('GITHUB_REF_NAME') or None
    ref = os.environ.get('GITHUB_REF', '')
    if ref.startswith('refs/tags/'):
        return ref.removeprefix('refs/tags/')
    return None


def inspect(root: Path, *, tag: str | None = None, built: bool = False) -> list[str]:
    errors: list[str] = []
    try:
        version = pom_version(root)
    except (OSError, ValueError, ET.ParseError) as ex:
        return [f'pom.xml: {ex}']
    if tag is not None and tag != 'v' + version:
        errors.append(f'Tag/POM mismatch: tag={tag!r}, pom.xml version={version!r}; expected tag v{version}')

    def expect(path: str, needle: str, context: str, *, target: Path | None = None) -> str:
        source = target or root / path
        try:
            content = source.read_text(encoding='utf-8')
        except OSError as ex:
            errors.append(f'{path}: cannot read: {ex}')
            return ''
        if needle not in content:
            errors.append(f'{path}: {context}; expected {needle!r}')
        return content

    pom = expect('pom.xml', '<filtering>true</filtering>', 'version resources must be filtered')
    expect('pom.xml', '<delimiter>@@</delimiter>', 'safe Maven filtering delimiter missing')
    expect('pom.xml', '<useDefaultDelimiters>false</useDefaultDelimiters>', 'Spring property ${...} placeholders must not be filtered')
    for file in FILTERED_FILES:
        expect('pom.xml', f'<include>{file}</include>', 'missing explicit filtered-resource include')
        expect('pom.xml', f'<exclude>{file}</exclude>', 'unfiltered source must exclude versioned resource')
    props = expect('src/main/resources/application.properties', f'clamav.app.version={TOKEN}', 'application version must be derived from Maven')
    if re.search(r'(?m)^clamav\.app\.version=(?!' + re.escape(TOKEN) + r').+$', props):
        errors.append('application.properties: duplicate or hard-coded clamav.app.version')
    api = expect('src/main/resources/openapi/clamav-v1.yaml', f'  version: "{TOKEN}"', 'OpenAPI info.version must use Maven version')
    if not re.search(r'(?m)^info:\s*\n(?: {2}[^\n]*\n)*?  version: ' + '"' + re.escape(TOKEN) + '"' + '$', api):
        errors.append('openapi/clamav-v1.yaml: info.version is not Maven-filtered')
    for relative in ('configuration-center/setup.html', 'templates/login.html', 'templates/error.html', 'templates/fragments/shell.html'):
        expect('src/main/resources/' + relative, TOKEN, 'visible version must be Maven-filtered or use appVersion')
    shell = expect('src/main/resources/templates/fragments/shell.html', '(v=${appVersion})', 'JS cache busting must use deployed version')
    expect('src/main/resources/templates/error.html', '(v=${appVersion})', 'JS cache busting must use deployed version')
    expect('src/main/java/info/trizub/clamav/webclient/web/UiModelAdvice.java', '${clamav.app.version:development}', 'UI must read property, not release literal')
    expect('cli/cmd/clamctl/main.go', 'var version = "development"', 'CLI must not embed stale release version')
    expect('cli/tools/build.go', 'pomVersion("../pom.xml")', 'CLI build version must be read from root pom.xml')
    expect('cli/tools/build.go', 'CLI/POM mismatch:', 'CLI override must reject mismatches')
    expect('cli/internal/cli/client.go', '"clamctl/"+c.version', 'CLI User-Agent must use executable version')
    expect('cli/README.md', 'root `pom.xml`', 'CLI README must refer to canonical version')
    expect('docker-compose.yml', '${CLAMAV_WEBUI_IMAGE_REF:-clamav-web-client:${CLAMAV_WEBUI_IMAGE_TAG:-local}}', 'Compose must support immutable GHCR image reference and local tag')
    expect('.github/workflows/ci.yml', 'clamav-web-client:local', 'CI local tag must match Compose')
    for compose_file in ('docker-compose.configuration-center.yaml', 'docker-compose.environment.yaml', 'docker-compose.minimal.yaml'):
        expect(compose_file, 'clamav-web-client:${CLAMAV_WEBUI_IMAGE_TAG:-local}', 'standalone Compose must also use version-neutral local image tag')
    expect('README.md', '**Application version:** set only in [`pom.xml`]', 'README must identify canonical release version')
    expect('scripts/release-gate.sh', 'python3 scripts/check-versions.py', 'full release gate must check version inventory')
    expect('.github/workflows/ci.yml', 'python3 scripts/check-versions.py', 'always-reporting CI check must verify version inventory')
    expect('.github/workflows/release.yml', 'python3 scripts/check-versions.py', 'release must check version inventory')
    if '==\'2.1.0-beta2\'' in (root / 'scripts/clamav-release-gate.sh').read_text():
        errors.append('scripts/clamav-release-gate.sh: legacy hard-coded Maven assertion remains')
    if built:
        target = root / 'target/classes'
        for path in FILTERED_FILES:
            f = target / path
            try:
                content = f.read_text()
            except OSError as ex:
                errors.append(f'target/classes/{path}: missing build output ({ex})')
                continue
            if TOKEN in content:
                errors.append(f'target/classes/{path}: Maven did not filter {TOKEN}')
            if path == 'application.properties' and f'clamav.app.version={version}' not in content:
                errors.append(f'target/classes/{path}: expected clamav.app.version={version}')
            if path == 'openapi/clamav-v1.yaml' and f'  version: "{version}"' not in content:
                errors.append(f'target/classes/{path}: expected OpenAPI info.version={version}')
            if path == 'configuration-center/setup.html' and f'ClamAV Web UI v{version}' not in content:
                errors.append(f'target/classes/{path}: expected rendered setup footer v{version}')
    return errors


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=ROOT)
    p.add_argument('--tag', help='Release tag to require, e.g. v2.1.0-beta2; auto-detected on tag-triggered CI')
    p.add_argument('--print-version', action='store_true', help='Print canonical POM version for build scripts')
    p.add_argument('--verify-built', action='store_true', help='Also check Maven-filtered target/classes resources')
    args = p.parse_args()
    try:
        version = pom_version(args.root)
    except (OSError, ValueError, ET.ParseError) as ex:
        print(f'FAIL: {ex}', file=sys.stderr)
        return 1
    if args.print_version:
        print(version)
        return 0
    tag = args.tag if args.tag is not None else ref_tag()
    issues = inspect(args.root, tag=tag, built=args.verify_built)
    if issues:
        print('FAIL: version consistency:', file=sys.stderr)
        for issue in issues:
            print(' - ' + issue, file=sys.stderr)
        return 1
    print(f'PASS: single POM version {version}; Maven resources, OpenAPI, CLI, Compose, README' + (f'; matching tag {tag}' if tag else '') + ('; filtered build output' if args.verify_built else ''))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
