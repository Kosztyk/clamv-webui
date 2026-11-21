#!/usr/bin/env python3
"""Mutation test: release workflow must reject security bypasses."""
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
raw=(ROOT/'.github/workflows/release.yml').read_text()
checker=ROOT/'scripts/check-issue51-container-release.py'

def run(text: str, accepted: bool, description: str) -> None:
    with tempfile.TemporaryDirectory() as temp:
        path=Path(temp)/'release.yml';path.write_text(text)
        result=subprocess.run(['python3',str(checker),str(path)],capture_output=True,text=True)
        if (result.returncode==0)!=accepted:
            raise AssertionError(f'{description}: expected success={accepted}; got {result.stdout} {result.stderr}')
    print('PASS:',description)

run(raw,True,'Intact release workflow passes')
for before,after,description in [
    ('linux/amd64,linux/arm64','linux/amd64','Single-architecture images blocked'),
    ('provenance: mode=max','provenance: false','Provenance disable blocked'),
    ('sbom: true','sbom: false','BuildKit SBOM disable blocked'),
    ('--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1','--severity CRITICAL --ignore-unfixed --exit-code 0','Advisory/critical-only scan blocked'),
    ('docker compose up -d --no-build --pull never','docker compose up -d --build','Local image substitution blocked'),
    ('bash scripts/smoke-eicar.sh','echo smoke skipped','EICAR removal blocked'),
    ('bash scripts/smoke-non-root.sh','echo non-root skipped','Non-root smoke removal blocked'),
    ('cosign sign --yes "$IMAGE@$DIGEST"','echo unsigned','Unsigned image blocked'),
    ('cosign attest --yes --type cyclonedx','echo unattested','Missing CycloneDX attestation blocked'),
    ('release-signed-image','release-none','Release evidence omission blocked'),
    ("github.event_name == 'push' }}", "github.event_name == 'workflow_dispatch' }}",'Dry-run image publication blocked'),
]:
    assert before in raw, description+' token not found'
    run(raw.replace(before,after),False,description)
