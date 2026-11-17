#!/usr/bin/env python3
"""Issue #51 behavioral regression and negative tests; no Docker/GHCR access needed."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

im = load('issue51image', ROOT/'scripts/issue51-image-release.py')
assets = load('issue51assets', ROOT/'scripts/release-assets.py')
SHA='a'*64
SRC='b'*40

def make_index(*, missing_arch=False, missing_attestation=False):
    arches=['amd64'] if missing_arch else ['amd64','arm64']
    manifests=[]
    for i,arch in enumerate(arches):
        sha='sha256:'+str(i+1)*64
        manifests.append({'mediaType': 'application/vnd.oci.image.manifest.v1+json', 'digest': sha,
                          'size': 6000, 'platform': {'os':'linux','architecture':arch}})
        if not missing_attestation or i!=1:
            manifests.append({'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                              'digest': 'sha256:'+str(i+4)*64, 'size': 1200,
                              'platform': {'os':'unknown','architecture':'unknown'},
                              'annotations': {'vnd.docker.reference.type':'attestation-manifest',
                                              'vnd.docker.reference.digest':sha}})
    return {'schemaVersion':2,'mediaType':'application/vnd.oci.image.index.v1+json','manifests':manifests}

class ImagePolicy(unittest.TestCase):
    def test_stable_and_beta_aliases(self):
        self.assertEqual(im.release_tags('v2.1.0'), ['2.1.0','2.1','latest'])
        self.assertEqual(im.release_tags('v2.1.0-beta2'), ['2.1.0-beta2'])
        for tag in ['V2.1.0', 'v02.1.0','v2.1.0evil', 'v2.1.0-']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                im.release_tags(tag)

    def test_record_requires_canonical_image_and_digest(self):
        r=im.evidence('ghcr.io/kosztyk/clamav-web-client','sha256:'+SHA,'v2.1.0-beta2',SRC)
        self.assertEqual(r['immutableReference'],r['smokeImageReference'])
        self.assertEqual(len(r['tags']),1)
        for bad in ['docker.io/kosztyk/clamav-web-client', 'ghcr.io/Kosztyk/clamav-web-client']:
            with self.assertRaises(ValueError):im.evidence(bad,'sha256:'+SHA,'v2.1.0',SRC)
        with self.assertRaises(ValueError):im.evidence('ghcr.io/kosztyk/clamav-web-client','sha256:bad','v2.1.0',SRC)

    def test_index_requires_both_platforms_and_attestations(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'index.json'
            for data,accepted in [(make_index(),True),(make_index(missing_arch=True),False),
                                  (make_index(missing_attestation=True),False)]:
                path.write_text(json.dumps(data))
                if accepted: im.read_manifest(path)
                else:
                    with self.assertRaises(ValueError):im.read_manifest(path)

    def test_cyclonedx_rejects_incomplete_sbom(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'sbom.json'
            path.write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','components':[{'name':'demo','type':'library'}]}))
            im.check_sbom(path)
            path.write_text('{}')
            with self.assertRaises(ValueError):im.check_sbom(path)

    def test_metadata_derives_owner_and_exact_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            outputs=Path(tmp)/'outputs'
            env=dict(os.environ, GITHUB_REPOSITORY='Kosztyk/clamv-webui', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2')
            result=subprocess.run(['python3',str(ROOT/'scripts/issue51-image-release.py'),'metadata','--tag','v2.1.0-beta2','--commit',SRC,'--github-output',str(outputs)],env=env,capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            text=outputs.read_text()
            self.assertIn('image=ghcr.io/kosztyk/clamav-web-client',text)
            self.assertIn('stage=ghcr.io/kosztyk/clamav-web-client:staging-',text)

    def test_release_assets_embed_complete_image_evidence_with_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            src=root/'repo';src.mkdir()
            subprocess.run(['git','init','-q',str(src)],check=True)
            (src/'README.md').write_text('safe fixture\n')
            subprocess.run(['git','-C',str(src),'add','README.md'],check=True)
            subprocess.run(['git','-C',str(src),'-c','user.name=fixture','-c','user.email=x@y.z','commit','-qm','fixture'],check=True)
            j=root/'jar';j.mkdir();(j/'clamav-web-client-2.1.0-beta2.jar').write_bytes(b'jar')
            cli=root/'cli';cli.mkdir();manifest=[]
            for system,arch in sorted(assets.TARGETS):
                suffix='.exe' if system=='windows' else ''
                relative=f'{system}_{arch}/clamctl{suffix}'
                path=cli/relative;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes((system+arch).encode())
                manifest.append({'os':system,'arch':arch,'version':'2.1.0-beta2',
                                'cgoEnabled':'0','path':relative,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
            (cli/'manifest.json').write_text(json.dumps(manifest))
            image=root/'image';image.mkdir()
            (image/'image-reference.json').write_text(json.dumps(im.evidence('ghcr.io/kosztyk/clamav-web-client','sha256:'+SHA,'v2.1.0-beta2',SRC)))
            (image/'oci-index.json').write_text(json.dumps(make_index()))
            for arch in ('amd64','arm64'):
                (image/f'sbom-{arch}.cdx.json').write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','components':[{'name':'test','type':'library'}]}))
            out=root/'out'
            names=assets.make_release('v2.1.0-beta2',j,cli,out,src,image)
            self.assertEqual(len(names),13)
            assets.verify_release(out)
            (out/'sbom-arm64.cdx.json').write_text('tampered')
            with self.assertRaises(assets.AssetError):assets.verify_release(out)
            # Missing evidence must stop packaging even if jar+CLI exist.
            (image/'sbom-arm64.cdx.json').unlink()
            with self.assertRaises(assets.AssetError):
                assets.make_release('v2.1.0-beta2',j,cli,root/'another',src,image)

if __name__=='__main__':
    unittest.main(verbosity=2)
