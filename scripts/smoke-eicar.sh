#!/usr/bin/env bash
# End-to-end scan through the published REST API and an actual clamd container.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE_URL="${CLAMAV_SMOKE_URL:-}"
if [[ -z "$BASE_URL" ]]; then
  published="$(docker compose port clamav-web-client 8080)"
  published_port="${published##*:}"
  [[ "$published_port" =~ ^[0-9]+$ ]] || { echo 'Cannot resolve the published Web UI port; set CLAMAV_SMOKE_URL.' >&2; exit 1; }
  BASE_URL="http://127.0.0.1:$published_port"
fi
SMOKE_USERNAME="${CLAMAV_SMOKE_USER:-admin}"
smoke_directory="$(mktemp -d -t clamav-smoke-XXXXXX)"
trap 'rm -rf "$smoke_directory"' EXIT
cookie_file="$smoke_directory/cookies"

csrf_token() {
  python3 - "$1" <<'PY'
from html.parser import HTMLParser
from pathlib import Path
import sys
class Token(HTMLParser):
    token = None
    def handle_starttag(self, tag, attributes):
        fields = dict(attributes)
        if tag == 'input' and fields.get('name') == '_csrf':
            self.token = fields.get('value')
parser = Token()
parser.feed(Path(sys.argv[1]).read_text())
if not parser.token:
    raise SystemExit('CSRF token missing; check login/session response')
print(parser.token)
PY
}
if [[ -n "${CLAMAV_SMOKE_PASSWORD:-}" ]]; then
  PASSWORD="$CLAMAV_SMOKE_PASSWORD"
else
  PASSWORD=""
  for attempt in $(seq 1 30); do
    PASSWORD="$(docker compose exec -T clamav-web-client cat /app/data/bootstrap-admin-password 2>/dev/null | tr -d '\r\n' || true)"
    [[ -n "$PASSWORD" ]] && break
    sleep 2
  done
fi
[[ -n "$PASSWORD" ]] || { echo 'Empty bootstrap password' >&2; exit 1; }

# Local HTTP smoke uses the browser session/CSRF flow. HTTP Basic still requires HTTPS.
web_ready=0
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 4 -c "$cookie_file" "$BASE_URL/login" -o "$smoke_directory/login.html"; then
    web_ready=1; break
  fi
  sleep 2
done
[[ "$web_ready" = 1 ]] || { echo 'Web login page did not become ready' >&2; exit 1; }
csrf="$(csrf_token "$smoke_directory/login.html")"
login_status="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  -b "$cookie_file" -c "$cookie_file" \
  --data-urlencode "username=$SMOKE_USERNAME" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "_csrf=$csrf" "$BASE_URL/login")"
[[ "$login_status" = 302 ]] || { echo "Login failed: HTTP $login_status" >&2; exit 1; }
curl -fsS --max-time 10 -b "$cookie_file" "$BASE_URL/scan" -o "$smoke_directory/scan.html"
csrf="$(csrf_token "$smoke_directory/scan.html")"

echo 'Waiting for web login page and clamd STATS to report online…'
ready=0
for attempt in $(seq 1 180); do
  if curl -fsS --max-time 4 "$BASE_URL/login" | grep -q 'ClamAV Web UI'; then
    if curl -fsS --max-time 5 -b "$cookie_file" "$BASE_URL/api/clamdtop" | python3 -c 'import json,sys;sys.exit(0 if json.load(sys.stdin).get("ok") else 1)' 2>/dev/null; then
      ready=1; break
    fi
  fi
  sleep 5
done
[[ "$ready" = 1 ]] || { echo 'Web UI or clamd failed to become ready' >&2; docker compose logs --tail=120; exit 1; }

endpoint_id="$(curl -fsS --max-time 10 -b "$cookie_file" "$BASE_URL/api/endpoints" | python3 -c 'import json,sys; print(next(x["id"] for x in json.load(sys.stdin) if x["enabled"]))')"
file="$smoke_directory/eicar.com"
clean_smoke="${CLAMAV_SMOKE_CLEAN:-false}"
# Industry-standard harmless EICAR signature, not malware.
if [[ "$clean_smoke" = true ]]; then
  file="$smoke_directory/clean-fixture.txt"
  printf '%s' 'Harmless ClamAV upload cleanup fixture.' > "$file"
else
  printf '%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' > "$file"
fi
smoke_filename="${CLAMAV_SMOKE_FILENAME:-$(basename "$file")}"
[[ "$smoke_filename" =~ ^[A-Za-z0-9._-]+$ ]] || { echo 'Smoke fixture filename must contain only ASCII letters, numbers, dots, underscores or hyphens.' >&2; exit 1; }
expect_quarantine_failure="${CLAMAV_SMOKE_EXPECT_QUARANTINE_FAILED:-false}"
response="$(curl -fsS --max-time 45 -b "$cookie_file" -H "X-CSRF-TOKEN: $csrf" -F "endpointId=$endpoint_id" -F "files=@$file;filename=$smoke_filename" "$BASE_URL/api/scan/upload")"
job_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["jobIds"][0])' <<< "$response")"
[[ -n "$job_id" ]] || { echo 'No job ID returned' >&2; exit 1; }
echo "Queued upload scan job: $job_id"
for attempt in $(seq 1 90); do
  result="$(curl -fsS --max-time 10 -b "$cookie_file" "$BASE_URL/api/jobs/$job_id")"
  if python3 -c 'import json,sys; d=json.load(sys.stdin); clean=sys.argv[1]=="true"; verdict=(clean and d.get("verdict")=="OK" and d.get("status")=="FINISHED") or (not clean and d.get("verdict")=="VIRUS_FOUND" and any("eicar" in signature.lower() for signature in d.get("signatures", []))); quarantine=sys.argv[3]!="true" or (d.get("quarantineStatus")=="FAILED" and (d.get("error") or {}).get("code")=="QUARANTINE_FAILED"); sys.exit(0 if verdict and quarantine and d.get("filename")==sys.argv[2] else 1)' "$clean_smoke" "$smoke_filename" "$expect_quarantine_failure" <<< "$result"; then
    if [[ "$clean_smoke" = true ]]; then echo 'PASS: clean upload completed with OK verdict.'; else echo 'PASS: EICAR detected and persisted with its signature.'; fi
    if [[ ${#smoke_filename} -eq 400 ]]; then echo 'PASS: 400-character upload filename preserved in the job API.'; fi
    if [[ "$expect_quarantine_failure" = true ]]; then echo 'PASS: quarantine failure visible in the API without changing VIRUS_FOUND.'; fi
    curl -fsS -b "$cookie_file" "$BASE_URL/admin/audit" | grep -q 'SCAN_UPLOAD' || { echo 'Audit event not visible' >&2; exit 1; }
    echo 'PASS: SCAN_UPLOAD audit entry visible in admin UI.'
    exit 0
  fi
  if python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("status")=="FINISHED" or d.get("verdict")=="ERROR" else 1)' <<< "$result"; then
    echo "FAIL: Scan finished without EICAR verdict: $result" >&2
    exit 1
  fi
  sleep 2
done
echo "FAIL: Timed out waiting for job $job_id" >&2
exit 1
