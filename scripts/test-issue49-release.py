#!/usr/bin/env python3
"""Offline behavior tests for tag/gate proof and cross-platform release checksums."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

verify = load('release_verify_tag', ROOT / 'scripts/release-verify-tag.py')
assets = load('release_assets', ROOT / 'scripts/release-assets.py')
SHA = 'a' * 40


class FakeApi:
    def __init__(self, *, branch='ahead', run='success', gate='success', moving=False, no_run=False, missing_gate=False, older_good=False, annotated=False):
        self.branch, self.run, self.gate = branch, run, gate
        self.moving, self.no_run, self.missing_gate = moving, no_run, missing_gate
        self.older_good, self.annotated = older_good, annotated

    def get(self, route, params=None):
        if route.startswith('git/ref/tags/'):
            if self.annotated:
                return {'object': {'sha': 'b'*40, 'type':'tag'}}
            return {'object': {'sha': 'c'*40 if self.moving else SHA, 'type':'commit'}}
        if route.startswith('git/tags/'):
            return {'object': {'sha': SHA, 'type': 'commit'}}
        if route.startswith('compare/'):
            return {'status': self.branch}
        if route.endswith('/ci.yml/runs'):
            newer = [] if self.no_run else [dict(id=22, head_sha=SHA, head_branch='main', event='push',
                          status='completed', conclusion=self.run, created_at='2026-11-12T00:01:00Z', run_number=22)]
            older = [dict(id=21, head_sha=SHA, head_branch='main', event='push',
                          status='completed', conclusion='success', created_at='2026-11-11T00:01:00Z', run_number=21)] if self.older_good else []
            return {'workflow_runs': newer + older}
        if '/jobs' in route:
            return {'jobs': [] if self.missing_gate else [dict(name='gate', conclusion=self.gate, status='completed', run_id=22)]}
        raise AssertionError(route)


class TagValidation(unittest.TestCase):
    def test_green_main_gate_is_accepted(self):
        self.assertEqual(verify.validate('v2.1.0-beta2', SHA, '2.1.0-beta2', FakeApi()), SHA)
        self.assertEqual(verify.validate('v2.1.0', SHA, '2.1.0', FakeApi()), SHA)
        self.assertEqual(verify.validate('v2.1.0-beta2', SHA, '2.1.0-beta2', FakeApi(annotated=True)), SHA)

    def test_tag_version_must_match_pom(self):
        for tag, version in [('v2.1.0', '2.1.0-beta2'), ('v2.1.0-beta2', '2.1.0'), ('v02.1.0', '02.1.0'), ('evil-tag', 'evil-tag')]:
            with self.subTest(tag=tag), self.assertRaises(verify.ReleaseError):
                verify.validate(tag, SHA, version, FakeApi())

    def test_red_or_missing_main_gate_blocks(self):
        for fixture in [FakeApi(run='failure'), FakeApi(run='cancelled'), FakeApi(gate='failure'),
                        FakeApi(gate='skipped'), FakeApi(missing_gate=True), FakeApi(no_run=True),
                        FakeApi(run='failure', older_good=True)]:
            with self.subTest(state=fixture.__dict__), self.assertRaises(verify.ReleaseError):
                verify.validate('v2.1.0-beta2', SHA, '2.1.0-beta2', fixture)

    def test_non_main_or_moved_tag_blocks(self):
        for fixture in (FakeApi(branch='diverged'), FakeApi(branch='behind'), FakeApi(moving=True)):
            with self.subTest(state=fixture.__dict__), self.assertRaises(verify.ReleaseError):
                verify.validate('v2.1.0-beta2', SHA, '2.1.0-beta2', fixture)

    def test_remote_ref_recheck_detects_movement(self):
        self.assertEqual(verify.remote_tag_commit(FakeApi(), 'v2.1.0-beta2'), SHA)
        self.assertNotEqual(verify.remote_tag_commit(FakeApi(moving=True), 'v2.1.0-beta2'), SHA)


class Assets(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='issue49-assets-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'repo'; self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', self.repo], check=True)
        (self.repo / 'README.md').write_text('Fixture source without credentials.\n')
        subprocess.run(['git', '-C', self.repo, 'add', 'README.md'], check=True)
        subprocess.run(['git', '-C', self.repo, '-c', 'user.name=Fixture', '-c', 'user.email=test@example.test', 'commit', '-qm', 'fixture'], check=True)
        self.jar = self.base / 'jar'; self.jar.mkdir()
        (self.jar/'clamav-web-client-2.1.0-beta2.jar').write_bytes(b'fixture jar bytecode')
        self.cli = self.base / 'cli'; self.cli.mkdir()
        manifest = []
        for system, arch in sorted(assets.TARGETS):
            suffix = '.exe' if system == 'windows' else ''
            relative = f'{system}_{arch}/clamctl{suffix}'
            file = self.cli/relative
            file.parent.mkdir(parents=True)
            file.write_bytes(f'fixture {relative}'.encode())
            manifest.append(dict(os=system, arch=arch, version='2.1.0-beta2',
                                cgoEnabled='0', path=relative, sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
        (self.cli/'manifest.json').write_text(json.dumps(manifest))
        self.out = self.base/'out'

    def test_exact_artifacts_and_checksum_roundtrip(self):
        files = assets.make_release('v2.1.0-beta2', self.jar, self.cli, self.out, self.repo)
        self.assertEqual(len(files), 9)
        self.assertIn('clamctl-windows-arm64.exe', files)
        self.assertIn('clamav-webui-v2.1.0-beta2-full-source.zip', files)
        assets.verify_release(self.out)

    def test_source_manifest_covers_the_committed_archive(self):
        manifest_name = 'SOURCE_MANIFEST.sha256'
        (self.repo / manifest_name).write_text('0' * 64 + '  obsolete.txt\n')
        recipe = self.repo / 'docker' / 'example' / 'APKBUILD'
        recipe.parent.mkdir(parents=True)
        recipe.write_bytes(b'pkgver=1\n')
        subprocess.run(['git', '-C', self.repo, 'add', manifest_name, 'docker'], check=True)
        subprocess.run(['git', '-C', self.repo, '-c', 'user.name=Fixture', '-c',
                        'user.email=test@example.test', 'commit', '-qm', 'add package'], check=True)
        (self.repo / 'README.md').write_text('Uncommitted change.\n')

        assets.make_release('v2.1.0-beta2', self.jar, self.cli, self.out, self.repo)
        prefix = 'clamav-webui-v2.1.0-beta2/'
        expected_contents = {
            'README.md': b'Fixture source without credentials.\n',
            'docker/example/APKBUILD': b'pkgver=1\n',
        }
        with ZipFile(self.out / 'clamav-webui-v2.1.0-beta2-full-source.zip') as archive:
            files = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
            self.assertCountEqual(files, [prefix + name for name in [*expected_contents, manifest_name]])
            rows = []
            for name, contents in expected_contents.items():
                self.assertEqual(archive.read(prefix + name), contents)
                rows.append(f'{hashlib.sha256(contents).hexdigest()}  {name}\n')
            self.assertEqual(archive.read(prefix + manifest_name).decode(), ''.join(rows))

    def test_modified_file_fails_verification(self):
        assets.make_release('v2.1.0-beta2', self.jar, self.cli, self.out, self.repo)
        (self.out/'clamctl-linux-amd64').write_bytes(b'tampered')
        with self.assertRaises(assets.AssetError):
            assets.verify_release(self.out)

    def test_mismatched_cli_version_or_missing_platform_blocks(self):
        manifest = json.loads((self.cli/'manifest.json').read_text())
        manifest[0]['version'] = '2.1.0'
        (self.cli/'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaises(assets.AssetError):
            assets.make_release('v2.1.0-beta2', self.jar, self.cli, self.out, self.repo)


if __name__ == '__main__':
    unittest.main(verbosity=2)
