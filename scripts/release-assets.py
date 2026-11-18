#!/usr/bin/env python3
"""Collect a release's JAR, six verified CLI executables and Git source snapshot.

Refuses mismatched CLI version/digest and produces/checks a release-wide SHA256SUMS.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import re
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

TAG = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?\Z')
TARGETS = {(system, arch) for system in ('linux', 'darwin', 'windows') for arch in ('amd64', 'arm64')}


class AssetError(Exception):
    pass


def sha256(path: Path) -> str:
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def write_source_zip(source: Path, tag: str, output: Path) -> None:
    """Archive committed source and hash the exact files included in the ZIP."""
    prefix = f'clamav-webui-{tag}/'
    manifest_name = 'SOURCE_MANIFEST.sha256'
    subprocess.run([
        'git', '-C', str(source), 'archive', '--format=zip', '--prefix=' + prefix,
        '-o', str(output.resolve()), 'HEAD', '--', '.', ':(exclude)' + manifest_name,
    ], check=True)

    with ZipFile(output, 'a') as archive:
        rows = []
        for filename in sorted(archive.namelist()):
            if filename.endswith('/'):
                continue
            with archive.open(filename) as handle:
                digest = hashlib.file_digest(handle, 'sha256').hexdigest()
            rows.append(f'{digest}  {filename.removeprefix(prefix)}\n')

        manifest = ZipInfo(prefix + manifest_name, archive.getinfo(prefix).date_time)
        manifest.create_system = 3
        manifest.external_attr = 0o100644 << 16
        archive.writestr(manifest, ''.join(rows), compress_type=ZIP_DEFLATED)


def make_release(tag: str, jar_dir: Path, cli_dir: Path, out: Path, source: Path, image_dir: Path | None = None, cli_sign_dir: Path | None = None) -> list[str]:
    if not TAG.fullmatch(tag):
        raise AssetError('Invalid release tag')
    version = tag[1:]
    if out.exists() and any(out.iterdir()):
        raise AssetError('Release output directory must be empty')
    out.mkdir(parents=True, exist_ok=True)
    jar = jar_dir / ('clamav-web-client-' + version + '.jar')
    if not jar.is_file() or jar.stat().st_size == 0:
        raise AssetError('Expected Maven release JAR missing: ' + str(jar))
    target = out / jar.name
    shutil.copyfile(jar, target)
    manifest = json.loads((cli_dir / 'manifest.json').read_text())
    if not isinstance(manifest, list) or len(manifest) != 6:
        raise AssetError('Expected six CLI manifest entries')
    seen: set[tuple[str, str]] = set()
    for entry in manifest:
        platform = (entry['os'], entry['arch'])
        if platform not in TARGETS or platform in seen:
            raise AssetError('Unknown or duplicate CLI platform')
        seen.add(platform)
        if entry['version'] != version or entry.get('cgoEnabled') != '0':
            raise AssetError('CLI build version or CGO configuration mismatch')
        suffix = '.exe' if entry['os'] == 'windows' else ''
        relative = f"{entry['os']}_{entry['arch']}/clamctl{suffix}"
        if entry['path'] != relative:
            raise AssetError('CLI file path does not match the supported platform')
        original = cli_dir / relative
        if not original.is_file() or sha256(original) != entry['sha256']:
            raise AssetError('CLI binary missing or checksum mismatch: ' + relative)
        shutil.copyfile(original, out / f"clamctl-{entry['os']}-{entry['arch']}{suffix}")
    if seen != TARGETS:
        raise AssetError('Incomplete CLI target set')
    # Publish a source archive in addition to GitHub's automatic source snapshots.
    source_zip = out / f'clamav-webui-{tag}-full-source.zip'
    write_source_zip(source, tag, source_zip)
    if image_dir is not None:
        # Attach evidence for the actual published digest and both architectures,
        # not independently fabricated metadata from a local candidate image.
        extra = ('image-reference.json', 'oci-index.json', 'sbom-amd64.cdx.json', 'sbom-arm64.cdx.json')
        for name in extra:
            src = image_dir / name
            if not src.is_file() or src.stat().st_size == 0:
                raise AssetError('Required signed image evidence missing: ' + name)
            shutil.copyfile(src, out / name)
        import importlib.util
        spec = importlib.util.spec_from_file_location('image51', Path(__file__).with_name('issue51-image-release.py'))
        image51 = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(image51)
        try:
            ref = json.loads((out/'image-reference.json').read_text())
            expected = image51.evidence(ref['image'], ref['digest'], tag, ref['sourceCommit'])
            if ref != expected:
                raise AssetError('Image reference evidence has inconsistent version, digest or policy')
            image51.read_manifest(out/'oci-index.json')
            for arch in ('amd64', 'arm64'):
                image51.check_sbom(out/f'sbom-{arch}.cdx.json')
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            raise AssetError(f'Invalid image release evidence: {exc}') from exc
    if cli_sign_dir is not None:
        for name in ('clamctl-SHA256SUMS', 'clamctl-SHA256SUMS.sigstore.json'):
            src = cli_sign_dir / name
            if not src.is_file() or src.stat().st_size == 0:
                raise AssetError('Required keyless CLI signature evidence missing: ' + name)
            shutil.copyfile(src, out / name)
    names = sorted(f.name for f in out.iterdir() if f.is_file())
    expected_total = 8 + (4 if image_dir is not None else 0) + (2 if cli_sign_dir is not None else 0)
    if len(names) != expected_total:
        raise AssetError('Incorrect release asset count')
    (out / 'SHA256SUMS').write_text(''.join(f'{sha256(out/name)}  {name}\n' for name in names))
    verify_release(out, require_cli_signature=cli_sign_dir is not None)
    return names + ['SHA256SUMS']


def verify_release(out: Path, require_cli_signature: bool = False) -> None:
    checksum = out / 'SHA256SUMS'
    if not checksum.is_file():
        raise AssetError('SHA256SUMS missing')
    lines = checksum.read_text().splitlines()
    listed = set()
    for line in lines:
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9_.-]+)', line)
        if not match:
            raise AssetError('Invalid checksum row')
        digest, name = match.groups()
        if name in listed or name == 'SHA256SUMS':
            raise AssetError('Duplicate checksum name')
        listed.add(name)
        file = out / name
        if not file.is_file() or sha256(file) != digest:
            raise AssetError('Missing/tampered release asset: ' + name)
    present = {p.name for p in out.iterdir() if p.is_file() and p.name != 'SHA256SUMS'}
    baseline = {name for name in listed if re.fullmatch(r'clamctl-(linux|darwin|windows)-(amd64|arm64)(?:\.exe)?', name)}
    image_extra = {'image-reference.json', 'oci-index.json', 'sbom-amd64.cdx.json', 'sbom-arm64.cdx.json'}
    cli_extra = {'clamctl-SHA256SUMS', 'clamctl-SHA256SUMS.sigstore.json'}
    if (listed & cli_extra) not in (set(), cli_extra):
        raise AssetError('Signed CLI evidence must include both checksum and Sigstore bundle')
    if require_cli_signature and not cli_extra.issubset(listed):
        raise AssetError('Keyless-signed CLI checksums are required for release approval')
    expected_count = 8 + (4 if image_extra.issubset(listed) else 0) + (2 if cli_extra.issubset(listed) else 0)
    if listed != present or len(listed) != expected_count or len(baseline) != 6:
        raise AssetError('Checksum manifest is incomplete or includes extra files')
    if (listed & image_extra) not in (set(), image_extra):
        raise AssetError('Image evidence must include all four files')
    if cli_extra.issubset(listed):
        import importlib.util
        module_path = Path(__file__).with_name('issue54-cli-signing.py')
        signature_spec = importlib.util.spec_from_file_location('issue54_cli_signing', module_path)
        signature_module = importlib.util.module_from_spec(signature_spec)
        signature_spec.loader.exec_module(signature_module)
        try:
            signature_module.verify(out)
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            raise AssetError(f'CLI signed checksum verification failed: {exc}') from exc
    if image_extra.issubset(listed):
        import importlib.util
        spec = importlib.util.spec_from_file_location('image51', Path(__file__).with_name('issue51-image-release.py'))
        image51 = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(image51)
        try:
            ref = json.loads((out/'image-reference.json').read_text())
            tag = ref['sourceTag']
            expected = image51.evidence(ref['image'], ref['digest'], tag, ref['sourceCommit'])
            if ref != expected or ('clamav-web-client-'+tag[1:]+'.jar') not in listed or ('clamav-webui-'+tag+'-full-source.zip') not in listed:
                raise AssetError('Image release evidence does not match the JAR and source version')
            image51.read_manifest(out/'oci-index.json')
            for arch in ('amd64','arm64'):
                image51.check_sbom(out/f'sbom-{arch}.cdx.json')
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            raise AssetError(f'Invalid approved image evidence: {exc}') from exc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', default='')
    parser.add_argument('--jar-dir', type=Path)
    parser.add_argument('--cli-dir', type=Path)
    parser.add_argument('--source', type=Path, default=Path('.'))
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--image-dir', type=Path)
    parser.add_argument('--cli-sign-dir', type=Path)
    parser.add_argument('--require-cli-signature', action='store_true')
    parser.add_argument('--require-image-evidence', action='store_true')
    args = parser.parse_args()
    try:
        if args.verify_only:
            verify_release(args.out, require_cli_signature=args.require_cli_signature)
            if args.require_image_evidence and not (args.out / 'image-reference.json').is_file():
                raise AssetError('Release approval requires signed image evidence')
            print('PASS: release asset checksums verified')
        else:
            if not args.jar_dir or not args.cli_dir:
                raise AssetError('Both --jar-dir and --cli-dir are required')
            if args.require_cli_signature and args.cli_sign_dir is None:
                raise AssetError('Release requires --cli-sign-dir')
            names = make_release(args.tag, args.jar_dir, args.cli_dir, args.out, args.source, args.image_dir, args.cli_sign_dir)
            print('PASS: verified release assets:', ', '.join(names))
        return 0
    except (AssetError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as ex:
        print('RELEASE ASSETS BLOCKED:', ex, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
