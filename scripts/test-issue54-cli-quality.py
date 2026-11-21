#!/usr/bin/env python3
"""Adversarial tests for issue #54 signed CLI inventory and CI/release policy."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]

def mod(name, filename):
    spec=importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    obj=importlib.util.module_from_spec(spec)
    sys.modules[name]=obj
    spec.loader.exec_module(obj)
    return obj

sign=mod('cli_sign54', 'issue54-cli-signing.py')
assets=mod('release_assets54', 'release-assets.py')
repro=mod('repro54', 'issue54-cli-repro.py')
image51=mod('image51for54', 'issue51-image-release.py')
vexrelease=mod('vexrelease54', 'vex_release.py')
selector=mod('selector54', 'ci-detect-changes.py')


def positive_negative_checksum():
    with tempfile.TemporaryDirectory(prefix='issue54-signed-') as temp:
        root=Path(temp); cli=root/'cli'; cli.mkdir()
        manifest=[]
        release=root/'release';release.mkdir()
        for system,arch in sign.PLATFORMS:
            suffix='.exe' if system=='windows' else ''
            name=f'{system}_{arch}/clamctl{suffix}'
            path=cli/name;path.parent.mkdir(parents=True)
            path.write_bytes(('fake binary for '+name).encode())
            digest=hashlib.sha256(path.read_bytes()).hexdigest()
            manifest.append({'os':system,'arch':arch,'path':name,'sha256':digest,'version':'2.1.0-beta2','cgoEnabled':'0'})
            (release/f'clamctl-{system}-{arch}{suffix}').write_bytes(path.read_bytes())
        (cli/'manifest.json').write_text(json.dumps(manifest))
        signed=root/'signed';sign.make(cli,signed)
        (release/sign.MANIFEST).write_bytes((signed/sign.MANIFEST).read_bytes())
        bundle={'mediaType':'application/vnd.dev.sigstore.bundle.v0.3+json',
                'messageSignature':{'messageDigest':{'algorithm':'SHA2_256','digest':'fixture'}},
                'verificationMaterial':{'certificate':{'rawBytes':'fixture'}}}
        (release/sign.BUNDLE).write_text(json.dumps(bundle))
        sign.verify(release)
        print('PASS: deterministic detached CLI checksums match all 6 files')
        changed=release/'clamctl-linux-amd64';changed.write_bytes(b'different')
        try: sign.verify(release)
        except ValueError: print('PASS: tampered release binary rejected')
        else: raise AssertionError('Tampered binary accepted')
        changed.write_bytes((cli/'linux_amd64/clamctl').read_bytes())
        (release/sign.BUNDLE).unlink()
        try: sign.verify(release)
        except ValueError: print('PASS: missing cryptographic bundle rejected')
        else: raise AssertionError('Missing bundle accepted')
        (release/sign.BUNDLE).write_text(json.dumps(bundle))
        row=(release/sign.MANIFEST)
        row.write_text(row.read_text().replace('clamctl-linux-amd64', 'clamctl-linux-arm64'))
        try: sign.verify(release)
        except ValueError: print('PASS: signature inventory rename/duplicate rejected')
        else: raise AssertionError('Duplicate inventory accepted')
        assert repro.version_from_pom() == '2.1.0'
        for wrong in ('-1','hi','18446744073709551616000000x'):
            try: repro.source_date_epoch(wrong)
            except ValueError: pass
            else: raise AssertionError('Invalid SOURCE_DATE_EPOCH accepted')


def release_fixture():
    with tempfile.TemporaryDirectory(prefix='issue54-release-') as temp:
        root=Path(temp); cli=root/'cli';cli.mkdir();jar=root/'jar';jar.mkdir();src=root/'repo';src.mkdir()
        subprocess.run(['git','init','-q',str(src)],check=True)
        (src/'README.md').write_text('source sample')
        subprocess.run(['git','-C',str(src),'add','.'],check=True)
        subprocess.run(['git','-C',str(src),'-c','user.email=example@example.test','-c','user.name=Test','commit','-qm','example'],check=True)
        (jar/'clamav-web-client-2.1.0-beta2.jar').write_bytes(b'fake jar')
        manifest=[]
        for os_, arch in sign.PLATFORMS:
            suffix='.exe' if os_=='windows' else ''
            path=cli/f'{os_}_{arch}/clamctl{suffix}';path.parent.mkdir(parents=True)
            path.write_bytes(('binary '+str(path)).encode())
            manifest.append({'os':os_,'arch':arch,'path':f'{os_}_{arch}/clamctl{suffix}', 'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'version':'2.1.0-beta2','cgoEnabled':'0'})
        (cli/'manifest.json').write_text(json.dumps(manifest))
        evidence=root/'evidence';sign.make(cli,evidence)
        (evidence/sign.BUNDLE).write_text(json.dumps({'messageSignature':{'digest':'dummy'},'verificationMaterial':{'certificate':'dummy'}}))
        out=root/'out'
        files=assets.make_release('v2.1.0-beta2',jar,cli,out,src,cli_sign_dir=evidence)
        assert len(files)==11
        assets.verify_release(out,require_cli_signature=True)
        (out/'clamctl-linux-amd64').write_bytes(b'bad')
        try: assets.verify_release(out,require_cli_signature=True)
        except assets.AssetError: print('PASS: release checksum verification rejects modified signed CLI')
        else: raise AssertionError('Tampered release accepted')
        unsigned=root/'unsigned'
        assets.make_release('v2.1.0-beta2',jar,cli,unsigned,src)
        try: assets.verify_release(unsigned,require_cli_signature=True)
        except assets.AssetError: print('PASS: old unsigned artifact inventory rejected for modern releases')
        else: raise AssertionError('Unsigned release accepted')
        image=root/'image';image.mkdir()
        src_sha='b'*40
        (image/'image-reference.json').write_text(json.dumps(image51.evidence('ghcr.io/kosztyk/clamav-web-client', 'sha256:'+'a'*64, 'v2.1.0-beta2', src_sha)))
        manifests=[]
        for idx,arch in enumerate(('amd64','arm64')):
            digest='sha256:'+str(idx+1)*64
            manifests.append({'mediaType':'application/vnd.oci.image.manifest.v1+json','digest':digest,'size':6000,'platform':{'os':'linux','architecture':arch}})
            manifests.append({'mediaType':'application/vnd.oci.image.manifest.v1+json','digest':'sha256:'+str(idx+4)*64,
                              'size':1200,'platform':{'os':'unknown','architecture':'unknown'},
                              'annotations':{'vnd.docker.reference.type':'attestation-manifest','vnd.docker.reference.digest':digest}})
        (image/'oci-index.json').write_text(json.dumps({'schemaVersion':2,'mediaType':'application/vnd.oci.image.index.v1+json','manifests':manifests}))
        for arch in ('amd64','arm64'):
            (image/f'sbom-{arch}.cdx.json').write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','components':[{'name':'fixture','type':'library'}]}))
        (image/'CVE-2026-107891.vex.json').write_text(json.dumps(vexrelease.build_document(
            'ghcr.io/kosztyk/clamav-web-client','sha256:'+'a'*64,'2.1.0-beta2','2026-11-20T07:00:00Z')))
        combined=root/'combined'
        files=assets.make_release('v2.1.0-beta2',jar,cli,combined,src,image,evidence)
        assert len(files)==16
        assets.verify_release(combined,require_cli_signature=True)
        (combined/sign.MANIFEST).write_text('tampered')
        try: assets.verify_release(combined,require_cli_signature=True)
        except assets.AssetError: print('PASS: full image + signed CLI release rejects manipulated checksum inventory')
        else: raise AssertionError('Tampered image+CLI release accepted')
    for name in ('pom.xml','.github/workflows/ci.yml','.github/workflows/release.yml'):
        assert selector.classify([name])['cli'], 'CLI checks not selected for '+name
    print('PASS: Maven version and CI/release workflow changes select CLI regression lanes')


def mutated_policy():
    ci=(ROOT/'.github/workflows/ci.yml').read_text()
    release=(ROOT/'.github/workflows/release.yml').read_text()
    validator=ROOT/'scripts/check-issue54-cli-quality.py'
    with tempfile.TemporaryDirectory(prefix='issue54-policy-') as temp:
        a=Path(temp)/'ci.yml';b=Path(temp)/'release.yml'
        def verify(ci_text,release_text,expected):
            a.write_text(ci_text);b.write_text(release_text)
            result=subprocess.run([sys.executable,str(validator),'--ci',str(a),'--release',str(b)],capture_output=True,text=True)
            if (result.returncode==0)!=expected:
                raise AssertionError('Incorrect policy result: '+result.stdout+result.stderr)
        verify(ci,release,True)
        for before,after,reason in [
            ('staticcheck@2026.1','echo skipped staticcheck','staticcheck removed'),
            ('govulncheck@v1.1.4','echo no vulnerability scan','govulncheck removed'),
            ('go test -race -count=1 ./...','go test ./...','race scan skipped'),
            ('issue54-cli-repro.py --out cli/dist','go run ./tools/build.go','reproducibility skipped'),
            ('issue54-cli-repro.py --verify-only','echo no-version-check','integration artifact not verified'),
        ]:
            assert before in ci
            verify(ci.replace(before,after),release,False)
            print('PASS: CI policy rejects', reason)
        for before,after,reason in [
            ('cosign sign-blob --yes --new-bundle-format --bundle','echo unsigned checksum','no CLI signature'),
            ('--cli-sign-dir artifacts/cli-sign --require-cli-signature','--skip-signed-cli','unsigned release asset'),
            ('--verify-only --require-image-evidence --require-cli-signature','--verify-only --require-image-evidence','unsigned approved release'),
            ("'id-token': 'write'", "'id-token': 'none'", 'none')
        ]:
            if before not in release: # workflow YAML has a different spelling for id-token
                if reason == 'none':
                    before='id-token: write';after='id-token: none'
            assert before in release
            verify(ci,release.replace(before,after),False)
            print('PASS: release policy rejects', reason)

if __name__=='__main__':
    positive_negative_checksum()
    release_fixture()
    mutated_policy()
    print('PASS: issue #54 adversarial regression scenarios')
