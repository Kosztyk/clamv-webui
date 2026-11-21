#!/usr/bin/env python3
"""Cross-issue regression tests discovered by comprehensive source review.

Runs in CI and the full release gate. No GitHub connection or Maven required.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    assert spec and spec.loader
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def check() -> None:
    selector = module('audit_source_change_selector', 'scripts/ci-detect-changes.py')
    prepare = module('audit_baseline_pom', 'scripts/issue53-prepare-baseline.py')
    pom = (ROOT / 'pom.xml').read_text()
    release = (ROOT / '.github/workflows/release.yml').read_text()
    expected_scopes = set(selector.SCOPES)
    for control in ('.github/workflows/ci.yml', '.github/workflows/release.yml',
                    'scripts/ci-detect-changes.py', 'scripts/ci-evaluate-gate.py'):
        selected = selector.classify([control])
        assert all(selected[key] for key in expected_scopes), f'{control}: missing full-gate selection'
        assert selected['security'] and selected['static'] and not selected['docs_only']
    print('PASS: security-sensitive workflow/selector edits require the complete CI matrix')

    for control in ('scripts/issue58-coverage-ratchet.py',
                    'scripts/check-issue58-hardening.py',
                    'scripts/test-issue58-hardening.py'):
        selected = selector.classify([control])
        assert selected['java'] and selected['codeql'] and selected['upgrade'], (
            control + ': Java quality coverage policy change would skip Maven quality gate')
    print('PASS: JaCoCo ratchet and its regression checks require Java-quality CI')

    sample_base = '<project><profiles><profile><id>ci-quality</id><build><plugins><plugin><version>outdated</version></plugin></plugins></build></profile></profiles></project>'
    updated = prepare.inject(pom, sample_base)
    assert updated.count('<id>ci-quality</id>') == 1
    assert '<version>outdated</version>' not in updated
    assert '<version>3.4.0</version>' in updated
    ET.fromstring(updated)
    print('PASS: old ci-quality profile replaced with current scanner configuration')

    assert '-Dspotless.ratchet.ref=HEAD^' in release
    assert '-Dspotless.ratchet.ref=HEAD verify' not in release
    print('PASS: release formatter compares changes to a prior commit (not to itself)')

    root = ET.fromstring(pom)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    scm = root.find('m:scm', ns)
    assert scm is not None
    for field in ('connection', 'developerConnection', 'url'):
        value = scm.findtext('m:' + field, default='', namespaces=ns)
        assert 'Kosztyk/clamv-webui' in value, f'stale/mismatched publishing SCM: {field}'
    print('PASS: Maven publishing metadata points to this maintained repository')

    # Audit finding #8: verify source-package docs never advertise a missing
    # generated deployment JSON as a clickable artifact. Historical change
    # inventories are intentionally not rewritten.
    import re
    for rel in ('docs/CONFIGURATION_FIELD_AUDIT.md', 'docs/FIELD_VALIDATION.md',
                'SERVER_DEPLOYMENT.md'):
        doc = ROOT / rel
        content = doc.read_text()
        assert 'SERVER_PACKAGE_VALIDATION.json' not in content, (
            f'{rel}: historical generated report is not in the ZIP')
        for destination in re.findall(r'\[[^]]+\]\(([^)]+)\)', content):
            if destination.startswith(('http:', 'https:', '#', 'mailto:')):
                continue
            target = destination.split('#', 1)[0]
            if target:
                assert (doc.parent / target).exists(), f'{rel}: broken link {destination}'
    print('PASS: field-audit, deployment and field-validation documents link only to included files')

    scan = (ROOT / 'src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java').read_text()
    assert '@SuppressWarnings("unchecked")' not in scan, (
        'obsolete unchecked suppression returned to ScanExecutionService')
    # The type argument was formerly unused, but now gates the source-quarantine
    # path. Removing it would weaken the security boundary introduced in fix #6.
    assert 'handleResult(jobId, job.getType(),' in scan
    assert '(type == ScanJobType.PATH || type == ScanJobType.WATCH)' in scan
    assert 'quarantineService.attemptSource(' in scan
    print('PASS: no obsolete scan suppression; scan type remains used for quarantine security')

    print('PASS: full-source audit cross-issue regression suite')


if __name__ == '__main__':
    try:
        check()
    except (AssertionError, ValueError, ET.ParseError) as exc:
        sys.exit('FAIL: full-source audit regression: ' + str(exc))
