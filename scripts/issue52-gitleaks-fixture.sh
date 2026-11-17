#!/usr/bin/env bash
set -euo pipefail
GITLEAKS="${GITLEAKS:-$(go env GOPATH)/bin/gitleaks}"
[[ -x "$GITLEAKS" ]] || { echo 'Gitleaks binary missing' >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# Generated only during CI. Not a functional token or checked-in fixture.
python3 - "$TMP/fixture.txt" <<'PYSECRET'
from pathlib import Path
import secrets, string, sys
alphabet = string.ascii_letters + string.digits
fake = 'ghp_' + ''.join(secrets.choice(alphabet) for _ in range(36))
Path(sys.argv[1]).write_text('export GH_TOKEN=' + fake + '\n')
PYSECRET
set +e
"$GITLEAKS" dir --no-banner --redact --exit-code 1 \
  --report-format json --report-path "$TMP/report.json" \
  "$TMP" >/dev/null 2>&1
status=$?
set -e
if [[ "$status" -ne 1 ]]; then
  echo "FAIL: Gitleaks exit code $status did not indicate synthetic credential detection" >&2
  exit 1
fi
python3 - "$TMP/report.json" <<'PYVERIFY'
import json, sys
from pathlib import Path
report = json.loads(Path(sys.argv[1]).read_text())
assert isinstance(report, list) and any(
    f.get('RuleID') and f.get('File', '').endswith('fixture.txt') for f in report
), 'A scanner runtime error is not a valid credential detection test'
PYVERIFY
echo 'PASS: Gitleaks detected and reported a generated, nonfunctional GitHub token'
