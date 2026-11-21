#!/usr/bin/env python3
"""Offline source contracts for issue #46; does not replace Maven/Spring execution."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
def read(path):
    return (root / path).read_text()

service = read('src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java')
execution = read('src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java')
committer = read('src/main/java/info/trizub/clamav/webclient/service/UploadJobCommitter.java')
storage = read('src/main/java/info/trizub/clamav/webclient/service/UploadStorageService.java')
quarantine = read('src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java')
ui = read('src/main/java/info/trizub/clamav/webclient/web/WebUiController.java')
legacy = read('src/main/java/info/trizub/clamav/webclient/api/ApiController.java')
v1 = read('src/main/java/info/trizub/clamav/webclient/api/v1/ApiV1Controller.java')
test = read('src/test/java/info/trizub/clamav/webclient/service/UploadBatchPersistenceTest.java')
checks = {
    'obsolete transition methods removed from ScanJobService': not re.search(r'\b(?:finishOk|finishFound|finishError|markRunning|setQuarantinePath)\s*\(', service),
    'one four-argument upload entry point': len(re.findall(r'public List<ScanJob> createUploadJobs\(', service)) == 1 and 'String transport' in service,
    'one string path submission entry point': len(re.findall(r'public ScanJob createPathJob\(\s*String', service)) == 1,
    'browser upload submits directly to four-argument method': 'jobs.createUploadJobs(files, ep, auth.getName(), "BROWSER")' in ui,
    'browser path submits directly to proxied method': 'jobs.createPathJob(path, ep, auth.getName(), "BROWSER")' in ui,
    'legacy API uses explicit transport': 'jobs.createUploadJobs(files, ep, auth.getName(), "API_BASIC_OR_SESSION")' in legacy,
    'v1 API calls same four-argument method': bool(re.search(r'createUploadJobs\(\s*files,\s*ep,\s*auth\.getName\(\),', v1)),
    'upload copying outside active DB transaction': 'TransactionSynchronizationManager.isActualTransactionActive()' in service and 'Upload copying must be called outside' in service,
    'all upload metadata handed to commit in single batch': 'uploadCommitter.commit(jobs, batch)' in service,
    'short metadata commit opens proxied transaction': '@Transactional(propagation = Propagation.REQUIRES_NEW)' in committer,
    'all rows persisted and flushed together': 'jobs.saveAllAndFlush(prepared)' in committer,
    'batch bound to transaction before database persistence': committer.index('batch.bindToTransaction()') < committer.index('jobs.saveAllAndFlush(prepared)'),
    'upload work enqueued after commit': 'void afterCommit()' in committer,
    'batch cleanup on rollback': bool(re.search(r'status\s*==\s*STATUS_ROLLED_BACK', storage)) and 'rollback();' in storage,
    'string path creation has transactional interceptor': bool(re.search(r'@Transactional\s+public ScanJob createPathJob\(\s*String', service)),
    'path work enqueued after transaction commit': 'enqueueAfterCommit(job.getId())' in service,
    'deprecated getId removed from thread naming': 't.threadId()' in execution and 't.getId()' not in execution,
    'notification failures logged at WARN': bool(re.search(r'log\.warn\(\s*"Scan notification delivery failed for job', execution)),
    'quarantine exception logged at WARN': 'log.warn("Quarantine operation failed for' in quarantine,
    'source quarantine rejects untrusted daemon paths': 'attemptSource(' in execution and 'settings.allowedRoots()' in execution and 'Source quarantine rejected or failed' in quarantine,
    'DB failure specifically on second row': "CHECK (target <> 'reject-second.txt')" in test and 'first.txt' in test and 'reject-second.txt' in test,
    'real rollback test includes browser and both API transports': '@ValueSource(strings = {"BROWSER", "API_BASIC_OR_SESSION", "API_TOKEN"})' in test,
    'real rollback verifies zero rows, no files, no dispatch': bool(re.search(
        r'assertThat\(jobs\.count\(\)\)\.isZero\(\);\s*emptyUploads\(\);\s*verifyNoInteractions\(executor\);', test)),
    'real proxied path submission test checks transaction': 'pathCreationUsesTheProxiedTransactionForBothRoutes' in test and 'TransactionSynchronizationManager.isActualTransactionActive()).isTrue()' in test,
    'identity and WebSocket readability changes retained': 'configuredRevision = revision' in read('src/main/java/info/trizub/clamav/webclient/identity/DynamicLdapAuthenticationProvider.java') and 'log.debug("ClamdTop PTY reader stopped' in read('src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java'),
}
for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + label)
if not all(checks.values()):
    raise SystemExit(1)
print(f'Issue #46 offline source contracts: {len(checks)}/{len(checks)} passed (Maven test is separate).')
