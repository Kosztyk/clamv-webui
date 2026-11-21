#!/usr/bin/env python3
"""Build six deterministic clamctl binaries twice, compare hashes, verify manifests and version.

SOURCE_DATE_EPOCH is set from the checked-out commit (or an explicit argument).
Both builds use the same Go toolchain, CGO_ENABLED=0, -trimpath and -buildid=.
The output is published ONLY after every target matches byte-for-byte.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {(os_, arch) for os_ in ('linux', 'darwin', 'windows') for arch in ('amd64', 'arm64')}


def version_from_pom() -> str:
    root = ET.parse(ROOT / 'pom.xml').getroot()
    name = root.findtext('{http://maven.apache.org/POM/4.0.0}version') or root.findtext('version')
    if not name or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9_.-]+)?', name):
        raise ValueError('Invalid canonical version in pom.xml')
    return name


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest_files(directory: Path, version: str) -> dict[str, str]:
    manifest = json.loads((directory/'manifest.json').read_text())
    if not isinstance(manifest, list) or len(manifest) != 6:
        raise ValueError('Missing six-platform CLI build manifest')
    expected: dict[str, str] = {}
    platforms = set()
    for row in manifest:
        platform = (row['os'], row['arch'])
        if platform not in TARGETS or platform in platforms:
            raise ValueError('Duplicate/unsupported CLI target: ' + repr(platform))
        platforms.add(platform)
        name = f"{row['os']}_{row['arch']}/clamctl{'.exe' if row['os']=='windows' else ''}"
        if row['path'] != name or row['version'] != version or row['cgoEnabled'] != '0':
            raise ValueError('CLI build manifest has incompatible path, version or CGO settings')
        path = directory/name
        if not path.is_file() or path.is_symlink() or digest(path) != row['sha256']:
            raise ValueError('CLI binary checksum mismatch: ' + name)
        expected[name] = row['sha256']
    lines = (directory/'SHA256SUMS').read_text().splitlines()
    expected_lines = [f'{h}  {name}' for name, h in sorted(expected.items())]
    # Go builder sorts by complete checksum line (hash first), not filename.
    expected_lines.sort()
    if lines != expected_lines:
        raise ValueError('CLI SHA256SUMS missing, incomplete or inconsistent')
    return expected


def verify_version(directory: Path, version: str) -> None:
    if sys.platform.startswith('linux') and os.uname().machine in ('x86_64', 'amd64'):
        exe = directory/'linux_amd64/clamctl'
        exe.chmod(exe.stat().st_mode | 0o111)
        completed = subprocess.run([str(exe), '--json', '--version'], capture_output=True, text=True, timeout=15, check=True)
        data = json.loads(completed.stdout)
        if data.get('version') != version or data.get('os') != 'linux' or data.get('arch') != 'amd64':
            raise ValueError(f'Embedded clamctl version/platform mismatch: {data!r}')
    else:
        print('SKIP: running Linux/amd64 --version smoke requires Linux/amd64')


def source_date_epoch(explicit: str | None) -> str:
    if explicit is not None:
        epoch = explicit
    else:
        epoch = subprocess.check_output(['git', '-C', str(ROOT), 'show', '-s', '--format=%ct', 'HEAD'], text=True).strip()
    if not epoch.isdigit() or int(epoch) < 0:
        raise ValueError('SOURCE_DATE_EPOCH must be a non-negative integer')
    return epoch


def reproducible_build(out: Path, version: str, epoch: str) -> None:
    if out.exists() and any(out.iterdir()):
        raise ValueError('CLI output directory must be empty to prevent mixing builds')
    env = os.environ.copy()
    env.update(SOURCE_DATE_EPOCH=epoch, LC_ALL='C', TZ='UTC', GOTOOLCHAIN='local', CGO_ENABLED='0', GOFLAGS='')
    with tempfile.TemporaryDirectory(prefix='clamctl-repro-') as temp:
        p = Path(temp)
        first, second = p/'first', p/'second'
        for dest in (first, second):
            subprocess.run(['go', 'run', './tools/build.go', '-version', version, '-out', str(dest)],
                           cwd=ROOT/'cli', env=env, check=True)
        a = manifest_files(first, version)
        b = manifest_files(second, version)
        if a != b or (first/'SHA256SUMS').read_bytes() != (second/'SHA256SUMS').read_bytes():
            raise ValueError('Non-reproducible CLI: first and second build checksums differ')
        if (first/'manifest.json').read_bytes() != (second/'manifest.json').read_bytes():
            raise ValueError('Non-reproducible CLI metadata: manifests differ')
        verify_version(first, version)
        shutil.copytree(first, out, dirs_exist_ok=True)
    print(f'PASS: six-platform reproducible Go CLI, SHA256SUMS and embedded version {version}; SOURCE_DATE_EPOCH={epoch}')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT/'cli/dist')
    parser.add_argument('--version')
    parser.add_argument('--source-date-epoch')
    parser.add_argument('--verify-only', action='store_true')
    a = parser.parse_args()
    try:
        version = version_from_pom()
        if a.version is not None and a.version != version:
            raise ValueError(f'Tag/POM CLI mismatch: {a.version!r} != {version!r}')
        if a.verify_only:
            manifest_files(a.out, version)
            verify_version(a.out, version)
            print('PASS: existing six CLI binaries, checksums and --version verified')
        else:
            reproducible_build(a.out, version, source_date_epoch(a.source_date_epoch))
        return 0
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, json.JSONDecodeError) as e:
        print('FAIL: CLI reproducibility/version:', str(e), file=sys.stderr)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
