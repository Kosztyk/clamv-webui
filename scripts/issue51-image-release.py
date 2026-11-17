#!/usr/bin/env python3
"""Fail-closed OCI release metadata, index, SBOM, and digest evidence checks.

Usage in GitHub Actions: derive GHCR stage ref, validate BuildKit's multi-arch
index and Trivy CycloneDX output, promote only the approved digest, and emit
checksummed evidence for GitHub Release assets.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

TAG_RE = re.compile(r'^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$')
DIGEST_RE = re.compile(r'^sha256:[a-f0-9]{64}$')
COMMIT_RE = re.compile(r'^[a-f0-9]{40}$')
IMAGE_RE = re.compile(r'^ghcr\.io/[a-z0-9][a-z0-9_.-]*/clamav-web-client$')
ARCHES = {'amd64', 'arm64'}


def require(ok: bool, why: str) -> None:
    if not ok:
        raise ValueError(why)


def release_tags(tag: str) -> list[str]:
    match = TAG_RE.fullmatch(tag)
    require(match is not None, 'tag must be lower-case vMAJOR.MINOR.PATCH[-prerelease]')
    major, minor, patch, prerelease = match.groups()
    version = f'{major}.{minor}.{patch}'
    if prerelease:
        # A beta must never replace the stable series pointer or latest.
        return [version + '-' + prerelease]
    return [version, f'{major}.{minor}', 'latest']


def read_manifest(path: Path) -> dict:
    payload = json.loads(path.read_text())
    require(payload.get('schemaVersion') == 2, 'OCI index schemaVersion must be 2')
    require(payload.get('mediaType') in {
        'application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json'
    }, 'image must be a multi-arch OCI index')
    descriptors = payload.get('manifests', [])
    require(isinstance(descriptors, list), 'image index manifests missing')
    regular = [x for x in descriptors if x.get('platform', {}).get('os') == 'linux' and x.get('platform', {}).get('architecture') in ARCHES]
    found = {x['platform']['architecture'] for x in regular}
    require(found == ARCHES, f'Expected linux/amd64 and linux/arm64, got {found}')
    for item in regular:
        require(DIGEST_RE.fullmatch(item.get('digest', '')) is not None, 'malformed platform manifest digest')
    attested = {x.get('annotations', {}).get('vnd.docker.reference.digest') for x in descriptors
                if x.get('annotations', {}).get('vnd.docker.reference.type') == 'attestation-manifest'}
    require({x['digest'] for x in regular}.issubset(attested), 'missing BuildKit SBOM/provenance attestation for an architecture')
    return payload


def check_sbom(path: Path) -> None:
    data = json.loads(path.read_text())
    require(data.get('bomFormat') == 'CycloneDX', 'expected CycloneDX SBOM')
    require(str(data.get('specVersion', '')).startswith('1.'), 'unsupported CycloneDX spec')
    require(isinstance(data.get('components'), list) and data['components'], 'SBOM components missing')


def evidence(image: str, digest: str, tag: str, commit: str) -> dict:
    require(IMAGE_RE.fullmatch(image) is not None, 'image must be lowercase ghcr.io/<owner>/clamav-web-client')
    require(DIGEST_RE.fullmatch(digest) is not None, 'image digest must be sha256:<64 lowercase hex>')
    require(COMMIT_RE.fullmatch(commit) is not None, 'source commit must be a full SHA')
    tags = release_tags(tag)
    return {
        'schemaVersion': 1, 'sourceTag': tag, 'sourceCommit': commit,
        'image': image, 'digest': digest, 'immutableReference': image + '@' + digest,
        'tags': [image + ':' + t for t in tags], 'platforms': ['linux/amd64', 'linux/arm64'],
        'scanPolicy': {'severities': ['HIGH', 'CRITICAL'], 'unfixedOnly': True,
                       'packages': ['os', 'library'], 'eachArchitecture': True},
        'signature': 'cosign-keyless-oidc',
        'attestations': ['BuildKit SLSA provenance', 'BuildKit SPDX SBOM', 'CycloneDX linux/amd64', 'CycloneDX linux/arm64'],
        'smokeImageReference': image + '@' + digest,
    }


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='command', required=True)
    def with_tag(name: str):
        s = sub.add_parser(name)
        s.add_argument('--tag', required=True)
        return s
    m = with_tag('metadata'); m.add_argument('--commit', required=True); m.add_argument('--github-output', type=Path, required=True)
    m = sub.add_parser('manifest'); m.add_argument('--input', type=Path, required=True)
    m = sub.add_parser('sbom'); m.add_argument('--input', type=Path, required=True)
    m = with_tag('tags'); m.add_argument('--image', required=True); m.add_argument('--digest', required=True); m.add_argument('--github-output', type=Path, required=True)
    m = with_tag('record'); m.add_argument('--image', required=True); m.add_argument('--digest', required=True); m.add_argument('--commit', required=True); m.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    try:
        if args.command == 'manifest':
            read_manifest(args.input)
        elif args.command == 'sbom':
            check_sbom(args.input)
        elif args.command == 'metadata':
            repo = os.environ.get('GITHUB_REPOSITORY', '').lower()
            require(bool(re.fullmatch(r'[a-z0-9_.-]+/[a-z0-9_.-]+', repo)), 'invalid GITHUB_REPOSITORY')
            require(COMMIT_RE.fullmatch(args.commit) is not None, 'invalid Git commit SHA')
            release_tags(args.tag)
            owner = repo.split('/')[0]
            image = f'ghcr.io/{owner}/clamav-web-client'
            run_id = os.environ.get('GITHUB_RUN_ID', '')
            attempt = os.environ.get('GITHUB_RUN_ATTEMPT', '1')
            require(run_id.isdigit() and attempt.isdigit(), 'expected numeric GitHub run ID / attempt')
            stage = f'{image}:staging-{args.commit[:12]}-{run_id}-{attempt}'
            with args.github_output.open('a') as output:
                output.write(f'image={image}\nstage={stage}\n')
        elif args.command == 'tags':
            evidence(args.image, args.digest, args.tag, '0'*40)
            args.github_output.write_text(''.join(t + '\n' for t in release_tags(args.tag)))
        elif args.command == 'record':
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(evidence(args.image, args.digest, args.tag, args.commit), indent=2, sort_keys=True) + '\n')
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
        print('ISSUE51 IMAGE RELEASE BLOCKED:', exc, file=sys.stderr)
        raise SystemExit(1)
    print('PASS: issue #51', args.command)


if __name__ == '__main__':
    main()
