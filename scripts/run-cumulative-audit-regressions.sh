#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo 'Java 17+ is required' >&2; exit 2; }
command -v javac >/dev/null || { echo 'javac 17+ is required' >&2; exit 2; }
command -v mvn >/dev/null || { echo 'Maven is required for the full suite' >&2; exit 2; }
command -v docker >/dev/null || { echo 'Docker is required for PostgreSQL/MySQL/MariaDB Testcontainers' >&2; exit 2; }
docker info >/dev/null || { echo 'Docker daemon is unavailable' >&2; exit 2; }
python3 scripts/test-2026-cumulative-regressions.py
python3 scripts/check-audit-policy-offline.py
python3 scripts/check-audit-endpoint-offline.py
python3 scripts/check-audit-api-v1-offline.py
python3 scripts/check-audit-retention-indexes.py
python3 scripts/check-safe-scanning-source.py
python3 scripts/check-phase1-static.py
python3 scripts/check-phase2-static.py
python3 scripts/phase4-static-check.py
python3 scripts/check-i18n.py
mvn -B -Dtest=ClamdConfigurationObserverTest,DaemonScanPolicyTest,Issue29DirectoryScanTest,Issue33ConfigurationPollingTest,UploadAdmissionFilterTest,Issue28JobHistoryTest,ApiV1PathScopeIntegrationTest,ApiV1ContractIntegrationTest,ApiV1DtoTest,UploadBatchPersistenceTest,Phase2H2MigrationTest,CumulativeAuditRetentionIndexesTest test
mvn -B verify
mvn -B -Pdb-integration verify
if [[ -z "${CLAMD_BIN:-}" ]]; then
  echo 'Set CLAMD_BIN to your local real clamd binary for the mandatory live scan test' >&2
  exit 2
fi
[[ -x "$CLAMD_BIN" ]] || { echo 'CLAMD_BIN is not executable' >&2; exit 2; }
JAR="${APP_JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(find target -maxdepth 1 -name '*.jar' ! -name '*original*' ! -name '*sources*' | head -n 1)"
fi
[[ -n "$JAR" && -f "$JAR" ]] || { echo 'Packaged runnable JAR not found; set APP_JAR' >&2; exit 2; }
python3 scripts/test-eicar-native.py --clamd "$CLAMD_BIN" --jar "$JAR"
echo 'PASS: all source, Maven, Docker/Testcontainers and native ClamAV checks'
