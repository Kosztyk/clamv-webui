#!/usr/bin/env python3
"""Enforce previous RELEASE and PR-base OpenAPI compatibility with pinned oasdiff.

The historical V2.0.0 Git tag predates the beta train and contains the actual
released /api/v1 contract. We also compare against the merge/push base to catch
newly introduced changes. Missing tags, refs, tools or scanner errors fail closed.
"""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = 'src/main/resources/openapi/clamav-v1.yaml'
REVIEWED_WARNINGS = ROOT / 'config/oasdiff-reviewed-warnings.txt'


def git_read(repo: Path, ref: str) -> str:
    if not re.fullmatch(r'[A-Za-z0-9._/-]+', ref) or ref.startswith('-') or '..' in ref:
        raise ValueError(f'Invalid git ref: {ref!r}')
    result = subprocess.run(['git','-C',str(repo),'show',f'{ref}:{SPEC}'],
                            capture_output=True, text=True)
    if result.returncode or not result.stdout.strip():
        raise RuntimeError(f'Cannot retrieve OpenAPI from {ref}: {result.stderr.strip()}')
    return result.stdout


def base_ref(env: dict[str, str]) -> str | None:
    event = env.get('GITHUB_EVENT_NAME', '')
    if event == 'pull_request':
        ref = env.get('CI_PR_BASE_SHA', '')
    elif event == 'push' and env.get('GITHUB_REF_TYPE') != 'tag':
        ref = env.get('CI_PUSH_BEFORE_SHA', '')
    else:
        return None
    if not re.fullmatch('[0-9a-fA-F]{40}', ref) or set(ref) == {'0'}:
        raise RuntimeError(f'No immutable baseline SHA for {event}; refusing to skip breaking-change check')
    return ref


def compare(repo: Path, spec: Path, previous: str, tool: str,
            report_dir: Path, pr_base: str | None) -> list[str]:
    report_dir.mkdir(parents=True, exist_ok=True)
    failures = []
    with tempfile.TemporaryDirectory(prefix='clamav-oasdiff-') as tmp:
        historical = [('previous-release',previous)]
        if pr_base and pr_base != previous:
            historical.append(('change-base',pr_base))
        for label, ref in historical:
            baseline = Path(tmp) / f'{label}.yaml'
            baseline.write_text(git_read(repo,ref), encoding='utf-8')
            args = [tool, 'breaking', '--fail-on', 'WARN', str(baseline), str(spec),
                    '--warn-ignore', str(REVIEWED_WARNINGS)]
            command = subprocess.run(args,capture_output=True,text=True)
            output = (command.stdout + '\n' + command.stderr).strip()
            (report_dir / f'{label}.txt').write_text(
                f'Baseline: {ref}\nCandidate: {spec}\n'
                f'Command: oasdiff breaking --fail-on WARN <baseline> <candidate> '
                f'--warn-ignore {REVIEWED_WARNINGS.relative_to(ROOT)}\n\n{output}\n',
                encoding='utf-8')
            if command.returncode:
                failures.append(f'{label} ({ref}): oasdiff exit {command.returncode}; {output[-1200:]}')
            else:
                print(f'PASS: no unreviewed ERR/WARN-level changes versus {label} {ref}')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=ROOT)
    parser.add_argument('--spec',type=Path,default=ROOT/SPEC)
    parser.add_argument('--previous-tag',default='V2.0.0')
    parser.add_argument('--oasdiff',default='oasdiff')
    parser.add_argument('--reports',type=Path,default=ROOT/'artifacts/api-contract')
    parser.add_argument('--base',help='Override PR base for deterministic offline tests')
    args=parser.parse_args()
    if not args.spec.is_file():
        parser.error(f'Candidate OpenAPI not found: {args.spec}')
    if not shutil.which(args.oasdiff) and not Path(args.oasdiff).is_file():
        raise SystemExit('FAIL: oasdiff is missing; the breaking-change gate must not silently skip')
    try:
        failures=compare(args.repo,args.spec,args.previous_tag,args.oasdiff,args.reports,
                         args.base or base_ref(os.environ))
    except (RuntimeError, ValueError, OSError) as e:
        raise SystemExit(f'FAIL: API compatibility check did not run: {e}') from e
    if failures:
        for failure in failures:
            print('FAIL:',failure,file=sys.stderr)
        raise SystemExit(1)
    print('PASS: released and PR-base OpenAPI compatibility verified by oasdiff')

if __name__=='__main__': main()
