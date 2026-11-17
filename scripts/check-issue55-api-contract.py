#!/usr/bin/env python3
"""Guard the Issue #55 API contract gate against policy downgrades."""
from pathlib import Path
import re
import sys
import yaml

ROOT=Path(__file__).resolve().parents[1]


def check(root: Path=ROOT) -> list[str]:
    violations=[]
    ci = yaml.safe_load((root/'.github/workflows/ci.yml').read_text())
    release=yaml.safe_load((root/'.github/workflows/release.yml').read_text())
    jobs=ci['jobs']
    api=jobs.get('api-contract',{})
    if not api or api.get('if','').find("outputs.api == 'true'")<0:
        violations.append('api-contract job must be conditionally selected by API source changes')
    if 'api-contract' not in jobs.get('gate',{}).get('needs',[]):
        violations.append('required gate must depend on api-contract')
    s=str(api.get('steps',''))
    for expected in ('issue55-api-contract.py','test-issue55-api-contract.py',
                     '@stoplight/spectral-cli@6.17.1','--fail-severity error',
                     'github.com/oasdiff/oasdiff@v1.11.8','issue55-api-breaking.py',
                     'fetch-depth', 'setup-go@', 'setup-node@'):
        if expected not in s: violations.append('CI API contract missing '+expected)
    if 'continue-on-error' in s: violations.append('API contract may not ignore scanner failure')
    if not re.search(r'(?m)^\s*api:\s*\$\{\{ steps\.filter\.outputs\.api \}\}',(root/'.github/workflows/ci.yml').read_text()):
        violations.append('change classifier output api missing')
    mapping=(root/'scripts/ci-evaluate-gate.py').read_text()
    if not re.search(r"'api-contract':\s*'api'",mapping):
        violations.append('gate evaluator does not require API contract success')
    release_steps=str(release['jobs']['release-tests'].get('steps',[]))
    for expected in ('issue55-api-contract.py','issue55-api-breaking.py','@stoplight/spectral-cli@6.17.1',
                     'github.com/oasdiff/oasdiff@v1.11.8'):
        if expected not in release_steps:violations.append('release missing '+expected)
    if 'continue-on-error' in release_steps: violations.append('release scanner failure may not be ignored')
    if 'Issue55OpenApiContractIntegrationTest.java' not in '\n'.join(str(p) for p in (root/'src/test/java').rglob('*Issue55*')):
        violations.append('real MockMvc contract test missing')
    if 'test-clamctl-integration.py' not in (root/'.github/workflows/ci.yml').read_text():
        violations.append('real built-server Go CLI integration not required')
    if 'test-clamctl-integration.py' not in (root/'.github/workflows/release.yml').read_text():
        violations.append('release built-server Go CLI integration missing')
    if (root/'.spectral.yaml').is_file():
        rules=yaml.safe_load((root/'.spectral.yaml').read_text())
        if 'spectral:oas' not in rules.get('extends',[]):violations.append('Spectral OAS baseline missing')
        if rules.get('rules',{}).get('oas3-schema')!='error':violations.append('OpenAPI schema validation must be an error')
    else:violations.append('Spectral ruleset missing')
    return violations

if __name__=='__main__':
    errors=check()
    if errors:
        for error in errors:print('FAIL:',error,file=sys.stderr)
        sys.exit(1)
    print('PASS: API contract lint, runtime validator, release compatibility, CLI live integration and required gate cannot be bypassed')
