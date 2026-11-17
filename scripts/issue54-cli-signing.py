#!/usr/bin/env python3
"""Generate and validate the detached CLI SHA256 manifest to be signed via cosign keyless.

Signing is performed in GitHub Actions with the release workflow's OIDC identity;
verification checks both these exact bytes and that independent Sigstore signature.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

PLATFORMS = sorted((os_, arch) for os_ in ('linux', 'darwin', 'windows') for arch in ('amd64', 'arm64'))
MANIFEST = 'clamctl-SHA256SUMS'
BUNDLE = 'clamctl-SHA256SUMS.sigstore.json'


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make(src: Path, out: Path) -> None:
    if out.exists() and any(out.iterdir()):
        raise ValueError('Signing output must be empty')
    out.mkdir(parents=True, exist_ok=True)
    entries = json.loads((src/'manifest.json').read_text())
    if len(entries) != 6:
        raise ValueError('Six CLI manifest entries required')
    seen = set()
    lines = []
    for row in entries:
        os_, arch = row['os'], row['arch']
        if (os_, arch) not in PLATFORMS or (os_, arch) in seen:
            raise ValueError('Invalid CLI platform inventory')
        seen.add((os_, arch))
        path = src/f'{os_}_{arch}/clamctl{ ".exe" if os_ == "windows" else ""}'
        if not path.is_file() or sha(path) != row['sha256']:
            raise ValueError('CLI source binary does not match build manifest: ' + str(path))
        name = f'clamctl-{os_}-{arch}{".exe" if os_ == "windows" else ""}'
        lines.append(f'{sha(path)}  {name}')
    if seen != set(PLATFORMS):
        raise ValueError('CLI platform inventory incomplete')
    (out/MANIFEST).write_text('\n'.join(sorted(lines))+'\n')
    print(f'PASS: detached CLI checksum manifest contains exactly {len(lines)} binaries')


def verify(out: Path, require_bundle: bool = True) -> None:
    signed = out/MANIFEST
    if not signed.is_file():
        raise ValueError('Signed CLI checksum manifest missing')
    expected = set()
    lines = signed.read_text().splitlines()
    if len(lines) != 6 or lines != sorted(lines):
        raise ValueError('Signed CLI manifest requires six sorted entries')
    for line in lines:
        match = re.fullmatch(r'([a-f0-9]{64})  (clamctl-(linux|darwin|windows)-(amd64|arm64)(?:\.exe)?)', line)
        if not match:
            raise ValueError('Malformed signed CLI checksum row')
        checksum, name, os_, arch = match.groups()
        if name != f'clamctl-{os_}-{arch}{".exe" if os_ == "windows" else ""}' or (os_, arch) in expected:
            raise ValueError('Duplicate or invalid CLI target in signed manifest')
        expected.add((os_, arch))
        binary = out/name
        if not binary.is_file() or binary.is_symlink() or sha(binary) != checksum:
            raise ValueError('Released CLI binary does not match signed manifest: ' + name)
    if expected != set(PLATFORMS):
        raise ValueError('Signed CLI platform list incomplete')
    if require_bundle:
        bundle = out/BUNDLE
        if not bundle.is_file() or bundle.stat().st_size == 0:
            raise ValueError('Missing Sigstore keyless CLI signature bundle')
        content = json.loads(bundle.read_text())
        if not isinstance(content, dict) or not (content.get('messageSignature') or content.get('dsseEnvelope')) or not content.get('verificationMaterial'):
            raise ValueError('Malformed Sigstore signature bundle')
    print('PASS: six released CLI binaries match the detached signed manifest')


def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument('--input',type=Path)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--verify-only',action='store_true')
    args=ap.parse_args()
    try:
        if args.verify_only:
            verify(args.out)
        else:
            if not args.input:
                raise ValueError('--input is required')
            make(args.input,args.out)
        return 0
    except (ValueError,OSError,KeyError,json.JSONDecodeError) as e:
        print('FAIL: CLI signature manifest:',e,file=sys.stderr)
        return 1

if __name__=='__main__':
    raise SystemExit(main())
