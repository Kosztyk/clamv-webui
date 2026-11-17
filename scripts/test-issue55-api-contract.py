#!/usr/bin/env python3
"""Adversarial API-contract regression tests; no JVM or external scanner required."""
from __future__ import annotations
from pathlib import Path
import importlib.util
import os
import shutil
import subprocess
import tempfile
import yaml

ROOT=Path(__file__).resolve().parents[1]

def module(name, filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    loaded=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded

api=module('api_contract_inventory','issue55-api-contract.py')
breaking=module('api_contract_breaking','issue55-api-breaking.py')
policy=module('api_contract_policy','check-issue55-api-contract.py')
selector=module('api_contract_selector','ci-detect-changes.py')
gate=module('api_contract_gate','ci-evaluate-gate.py')


def expect(condition, why):
    assert condition, why
    print('PASS:',why)


def main():
    expect(not api.validate(), 'Complete Java controller, CLI and current OpenAPI inventory agrees')
    expect(not policy.check(), 'CI/release API tools are required by stable gate')
    with tempfile.TemporaryDirectory(prefix='issue55-test-') as directory:
        tmp=Path(directory)
        document=yaml.safe_load((ROOT/'src/main/resources/openapi/clamav-v1.yaml').read_text())
        target=tmp/'altered.yaml'
        # Missing documented operation cannot be hidden by the handler's existence.
        del document['paths']['/jobs/{id}']
        target.write_text(yaml.safe_dump(document,sort_keys=False))
        expect(any('Go CLI endpoint' in x or 'controller' in x for x in api.validate(spec_path=target)),
               'Deleting /jobs/{id} OpenAPI operation fails against controller and Go CLI')
        document=yaml.safe_load((ROOT/'src/main/resources/openapi/clamav-v1.yaml').read_text())
        del document['components']['schemas']['Job']['properties']['status']
        target.write_text(yaml.safe_dump(document,sort_keys=False))
        expect(any('Job schema' in x for x in api.validate(spec_path=target)),
               'Deleting required response field fails source contract validation')
        # Check a fake CLI endpoint is rejected, not silently allowed.
        for name in ('ApiV1Controller.java','ApiTokenAdminController.java'):
            dest=tmp/'src/main/java/info/trizub/clamav/webclient/api/v1'/name
            dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(ROOT/'src/main/java/info/trizub/clamav/webclient/api/v1'/name,dest)
        for src in (ROOT/'cli/internal/cli').glob('*.go'):
            dest=tmp/'cli/internal/cli'/src.name
            dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(src,dest)
        bad=tmp/'cli/internal/cli/mcp.go'
        bad.write_text(bad.read_text().replace('"/metrics/clamd"','"/not-in-openapi"',1))
        expect(any('Go CLI endpoint' in x for x in api.validate(root=tmp,spec_path=ROOT/'src/main/resources/openapi/clamav-v1.yaml')),
               'Unknown Go client endpoint blocks CI')
        expect(selector.classify(['src/main/resources/openapi/clamav-v1.yaml'])['api'],
               'OpenAPI change activates API contract job')
        expect(selector.classify(['src/main/resources/openapi/clamav-v1.yaml'])['cli'],
               'OpenAPI change also activates real Spring/CLI integration')
        expect(selector.classify(['cli/internal/cli/client.go'])['api'],
               'CLI change activates API contract job')
        expect(not selector.classify(['README.md'])['api'],
               'Docs-only PR skips expensive API scans')
        flags=selector.classify(['src/main/resources/openapi/clamav-v1.yaml'])
        needs={'changes':{'result':'success','outputs':{k:str(v).lower() for k,v in flags.items()}}}
        for name,scope in gate.JOB_TO_FLAG.items():needs[name]={'result':'success' if flags[scope] else 'skipped'}
        expect(not gate.evaluate(needs),'All selected API/Go/Java checks permit required gate to pass')
        needs['api-contract']['result']='failure'
        expect(any('api-contract' in x for x in gate.evaluate(needs)),
               'Failed API breaking change blocks single required gate')
        # Simulate an oasdiff binary with a deterministic fixture, exercising fail-on-WARN
        # and both historical-release and change-base comparisons.
        repo=tmp/'repo';repo.mkdir()
        def git(*a):return subprocess.check_output(['git','-C',str(repo),*a],text=True).strip()
        git('init','-q');git('config','user.name','API contract fixture');git('config','user.email','fixture@invalid.test')
        spec=repo/breaking.SPEC;spec.parent.mkdir(parents=True)
        spec.write_text(yaml.safe_dump({'openapi':'3.0.3','paths':{'/jobs':{'get':{}}}}))
        git('add','.');git('commit','-qm','api baseline');base=git('rev-parse','HEAD');git('tag','V2.0.0')
        bin=tmp/'mock-oasdiff'
        bin.write_text('''#!/usr/bin/env python3
import sys,yaml
assert sys.argv[1:4]==['breaking','--fail-on','WARN']
with open(sys.argv[4]) as f: old=yaml.safe_load(f)
with open(sys.argv[5]) as f: new=yaml.safe_load(f)
removed=set(old.get('paths',{}))-set(new.get('paths',{}))
if removed:print('ERR removed operations:',sorted(removed));sys.exit(1)
print('No breaking changes')
''');bin.chmod(0o755)
        reports=tmp/'results'
        expect(not breaking.compare(repo,spec,'V2.0.0',str(bin),reports,base),
               'Previous Git tag compatibility passes for identical contract')
        spec.write_text(yaml.safe_dump({'openapi':'3.0.3','paths':{'/unrelated':{'get':{}}}}))
        expect(bool(breaking.compare(repo,spec,'V2.0.0',str(bin),reports,base)),
               'oasdiff fails on removed released endpoint; report is saved')
        expect((reports/'previous-release.txt').is_file(),
               'Comparison report persisted as a CI artifact')
        try:breaking.git_read(repo,'unknown-release')
        except RuntimeError:pass
        else:raise AssertionError('Missing previous release tag incorrectly skipped')
        expect(True,'Missing release tag fails closed')
        # Policy tamper tests in a lightweight source overlay.
        overlay=tmp/'policy';(overlay/'.github/workflows').mkdir(parents=True)
        (overlay/'scripts').mkdir()
        (overlay/'src/test/java').mkdir(parents=True)
        shutil.copy2(ROOT/'.github/workflows/ci.yml',overlay/'.github/workflows/ci.yml')
        shutil.copy2(ROOT/'.github/workflows/release.yml',overlay/'.github/workflows/release.yml')
        shutil.copy2(ROOT/'scripts/ci-evaluate-gate.py',overlay/'scripts/ci-evaluate-gate.py')
        shutil.copy2(ROOT/'.spectral.yaml',overlay/'.spectral.yaml')
        (overlay/'src/test/java/Issue55OpenApiContractIntegrationTest.java').write_text('fixture')
        expect(not policy.check(overlay),'Baseline scanner policy passes')
        ci=overlay/'.github/workflows/ci.yml';s=ci.read_text()
        ci.write_text(s.replace('breaking --fail-on','breaking --fail-on')) # no-op control
        ci.write_text(s.replace('github.com/oasdiff/oasdiff@v1.11.8','github.com/oasdiff/oasdiff@latest'))
        expect(any('oasdiff' in x for x in policy.check(overlay)),
               'Mutable oasdiff version rejected')
        ci.write_text(s.replace('needs: [changes, static, api-contract,','needs: [changes, static,'))
        expect(any('gate' in x for x in policy.check(overlay)),
               'API contract removed from gate is rejected')
    print('PASS: Issue #55 adversarial contract tests complete')

if __name__=='__main__':main()
