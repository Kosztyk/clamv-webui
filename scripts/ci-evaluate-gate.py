#!/usr/bin/env python3
"""Fail closed on CI results. Only jobs skipped by the change classifier may pass.

Input is the trusted `toJSON(needs)` object from the gate job. This script
intentionally does not interpret skipped as universally successful.
"""
from __future__ import annotations
import json
import os
import sys

JOB_TO_FLAG = {
    'static': 'static',
    'java-tests': 'java',
    'java-quality': 'java',
    'db-migrations': 'db',
    'upgrade-path': 'upgrade',
    'cli-test': 'cli',
    'cli-build': 'cli',
    'cli-integration': 'cli',
    'docker-smoke': 'docker',
    'browser': 'browser',
    'codeql': 'codeql',
    'api-contract': 'api',
    'secrets': 'static',
    'workflow-lint': 'static',
    'dependency-vulns': 'security',
    'filesystem-vulns': 'security',
}


def evaluate(needs: dict) -> list[str]:
    failures = []
    changes = needs.get('changes', {})
    if changes.get('result') != 'success':
        failures.append('changes: did not succeed; all conditional skips are unsafe')
    outputs = changes.get('outputs') or {}
    for job, flag in JOB_TO_FLAG.items():
        record = needs.get(job)
        if not isinstance(record, dict):
            failures.append(f'{job}: missing from gate needs')
            continue
        actual = record.get('result')
        expected = outputs.get(flag)
        if expected not in {'true', 'false'}:
            failures.append(f'{job}: change detector did not report {flag}')
        elif expected == 'true' and actual != 'success':
            failures.append(f'{job}: required but result was {actual!r}')
        elif expected == 'false' and actual not in {'success', 'skipped'}:
            failures.append(f'{job}: optional but result was {actual!r}')
        print(f'{job}: {actual} (expected {flag}={expected})')
    return failures


def main() -> None:
    raw = os.getenv('CI_NEEDS_JSON') or sys.stdin.read()
    needs = json.loads(raw)
    failures = evaluate(needs)
    summary = os.getenv('GITHUB_STEP_SUMMARY')
    lines = ['### CI gate', '', '| Job | Result |', '|---|---|']
    for job in ('changes', *JOB_TO_FLAG):
        lines.append(f'| `{job}` | `{needs.get(job, {}).get("result", "missing")}` |')
    lines += ['', '**FAIL**' if failures else '**PASS**', *[f'- {f}' for f in failures]]
    if summary:
        with open(summary, 'a', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')
    if failures:
        print('\n'.join(f'FAIL: {s}' for s in failures), file=sys.stderr)
        sys.exit(1)
    print('PASS: all selected jobs succeeded; only filter-disabled jobs were skipped')


if __name__ == '__main__':
    main()
