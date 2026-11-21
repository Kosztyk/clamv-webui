#!/usr/bin/env python3
"""Verify issue #45 policy checker fails closed on malicious changes.

Runs in a disposable small copy; never modifies the working tree.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    '.github/dependabot.yml', '.env.example', 'Dockerfile', 'Dockerfile.clamd',
    'docker-compose.yml', 'docs/security/clamd-image-pin.json',
    'src/main/resources/static/js/VENDORED.md',
    'src/main/resources/static/js/jquery-3.7.1.min.js',
    'src/main/resources/static/js/bootstrap.bundle.min.js',
    'src/main/resources/static/js/xterm-vendored.js',
    'src/main/resources/static/css/bootstrap.min.css',
    'src/main/resources/static/css/xterm-vendored.css',
    'src/main/resources/templates/fragments/header.html',
    'scripts/check-issue45-supply-chain.py',
]


def main():
    with tempfile.TemporaryDirectory(prefix='clamav-issue45-gate-') as d:
        temp = Path(d)
        for rel in REQUIRED:
            target = temp / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT/rel, target)
        shutil.copytree(ROOT/'.github/workflows',temp/'.github/workflows')
        script = temp/'scripts/check-issue45-supply-chain.py'

        def run(expected, label):
            result=subprocess.run(['python3',str(script)], cwd=temp,
                                  stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            assert (result.returncode == 0) == expected, (label, result.stdout[-1200:])
            print('PASS:',label)

        run(True, 'Clean supplied baseline passes')
        asset = temp/'src/main/resources/static/js/xterm-vendored.js'
        original=asset.read_bytes()
        asset.write_bytes(original+b'\n// unreviewed mutation')
        run(False, 'Modified vendor asset without manifest update fails')
        asset.write_bytes(original)
        workflow=temp/'.github/workflows/ci.yml'
        initial=workflow.read_text()
        workflow.write_text(initial.replace("exit-code: '1'", "exit-code: '0'"))
        run(False, 'Trivy cannot be silently converted to advisory-only')
        workflow.write_text(initial)
        checkout=temp/'.github/workflows/release.yml'
        original=checkout.read_text()
        import re
        checkout.write_text(re.sub(r'(actions/checkout)@[0-9a-f]{40}',r'\1@v4',original))
        run(False, 'Mutable GitHub Action tag fails')
        checkout.write_text(original)
        run(True, 'Restored files pass after negative tests')

if __name__ == '__main__':main()
