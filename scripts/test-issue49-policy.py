#!/usr/bin/env python3
"""Deliberately mutate release policy in temporary YAML and require rejection."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / '.github/workflows/release.yml').read_text()
validator = ROOT / 'scripts/check-issue49-release.py'


def check(data: str, allowed: bool, label: str) -> None:
    with tempfile.TemporaryDirectory(prefix='issue49-policy-') as scratch:
        path = Path(scratch) / 'release.yml'
        path.write_text(data)
        result = subprocess.run(['python3', str(validator), '--workflow', str(path)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if (result.returncode == 0) != allowed:
            raise AssertionError(f'{label}: unexpected validator outcome\n{result.stdout[-1000:]}')
        print('PASS:', label)


check(source, True, 'Unmodified release workflow passes')
for needle, replacement, label in [
    ("exit-code: '1'", "exit-code: '0'", 'Critical Trivy findings cannot become advisory'),
    ('bash scripts/release-gate.sh --docker-build', 'bash scripts/release-gate.sh --static-only',
     'Release must run actual Maven and Docker builds'),
    ("github.event_name == 'push' }}", "github.event_name == 'workflow_dispatch' }}",
     'Manual dispatch must not create drafts'),
    ('environment: release', 'environment: staging',
     'Publication must use protected release environment'),
    ("vars.RELEASE_PUBLICATION_ENABLED == 'true'", "vars.RELEASE_PUBLICATION_ENABLED != 'false'",
     'Publication must default disabled'),
    ('--remote-ref-only', '--ignore-moved-tag',
     'Draft/publication must recheck tag commit'),
    ('--generate-notes', '--notes empty',
     'Draft must include generated release notes'),
]:
    assert needle in source, label + ': original token absent'
    check(source.replace(needle, replacement), False, label)
