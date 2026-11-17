#!/usr/bin/env python3
"""Fail-closed static validation of the real workflow and digest-based release path."""
from pathlib import Path
import re
import sys
import yaml

ROOT=Path(__file__).resolve().parents[1]

def validate(path: Path):
    raw=path.read_text()
    workflow=yaml.load(raw,Loader=yaml.BaseLoader)
    jobs=workflow['jobs']
    image=jobs['image-publish'];draft=jobs['draft']
    assert image['if'].strip()=="${{ github.event_name == 'push' }}", 'Manual dispatch must not write to GHCR'
    assert set(image['needs'])=={'verify-tag','release-tests','db-migrations','upgrade-path','cli-test','clamctl','cli-integration','cli-sign'}, 'Image publication must wait for all release gates'
    assert set(draft['needs'])==set(image['needs'])|{'image-publish'}, 'Draft must wait for signed pushed digest and smoke'
    assert image['permissions']=={'contents':'read','packages':'write','id-token':'write'}, 'Registry writing/identity token must be job-scoped'
    assert workflow['permissions']=={'contents':'read'}, 'Root permissions too broad'
    steps=image['steps']
    names={step.get('name',''):idx for idx,step in enumerate(steps)}
    def order(sub):
        vals=[idx for idx,step in enumerate(steps) if sub in step.get('name','')]
        assert len(vals)==1, 'Missing or duplicate stage: '+sub
        return vals[0]
    order_names=['Confirm approved tag','Build and push a staging','Assert pushed OCI index',
                 'Fail closed on unfixed HIGH/CRITICAL','Generate CycloneDX',
                 'Keyless-sign OCI digest','Independently verify signature',
                 'Pull the exact published digest','Launch pulled digest','Promote the SAME verified OCI index']
    idx=[order(s) for s in order_names]
    assert idx==sorted(idx), 'Must scan, sign, verify, digest-smoke BEFORE assigning release tags'
    build=next(s for s in steps if s.get('id')=='build')
    with_args=build['with']
    assert with_args['platforms']=='linux/amd64,linux/arm64' and with_args['push']=='true', 'Both platforms must be pushed'
    assert with_args['sbom']=='true' and with_args['provenance']=='mode=max', 'BuildKit SBOM and SLSA provenance required'
    assert 'steps.meta.outputs.stage' in with_args['tags'], 'Build must push staging ref only before gates'
    require=['trivy image --platform "$platform"', '--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1',
             'for platform in linux/amd64 linux/arm64',
             'cosign sign --yes "$IMAGE@$DIGEST"', 'cosign attest --yes --type cyclonedx',
             'scripts/issue51-verify-signature.sh', 'docker pull --platform linux/amd64',
             'docker compose up -d --no-build --pull never', 'scripts/smoke-non-root.sh',
             'scripts/smoke-eicar.sh', 'docker inspect --format',
             'docker buildx imagetools create', 'actual" = "$DIGEST"',
             'release-signed-image','image-reference.json','sbom-amd64.cdx.json','sbom-arm64.cdx.json']
    for marker in require:
        assert marker in raw, 'Required image-release protection missing: '+marker
    assert 'image-ref: clamav-web-client:release-candidate' in raw, 'Keep prior release-gate local candidate scans'
    assert re.search(r'(?m)^\s*-?\s*uses:\s*sigstore/cosign-installer@[a-f0-9]{40}\b',raw), 'Cosign action must be SHA-pinned'
    assert re.search(r'(?m)^\s*-?\s*uses:\s*docker/build-push-action@[a-f0-9]{40}\b',raw), 'Build action must be SHA-pinned'
    assert 'python3 scripts/release-assets.py --out artifacts/approval --verify-only --require-image-evidence' in raw, 'Approval must verify signed image evidence'
    assert 'bash scripts/issue51-verify-signature.sh "$ref" "$RELEASE_TAG"' in raw, 'Draft and approval need independent cosign verification'
    assert '${CLAMAV_WEBUI_IMAGE_REF:-clamav-web-client:${CLAMAV_WEBUI_IMAGE_TAG:-local}}' in (ROOT/'docker-compose.yml').read_text(), 'Compose override must accept immutable pushed digest'
    assert 'continue-on-error' not in raw, 'Release errors must not be ignored'
    return True

if __name__ == '__main__':
    try:
        validate(Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'.github/workflows/release.yml')
        print('PASS: Issue #51 immutable multi-arch, scan, sign, attestation and digest-smoke release gate')
    except (AssertionError, KeyError, OSError, ValueError) as exc:
        print('FAIL: Issue #51 security gate: '+str(exc), file=sys.stderr)
        raise SystemExit(1)
