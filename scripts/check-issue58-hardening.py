#!/usr/bin/env python3
"""Guard the queue, JaCoCo ratchet and published-release upgrade policy."""
from pathlib import Path
import yaml,sys
r=Path(__file__).resolve().parents[1]
def must(test,message):
    if not test:raise ValueError(message)
def check():
    w=(r/'src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java').read_text()
    m=(r/'src/main/java/info/trizub/clamav/webclient/metrics/ScanMetrics.java').read_text()
    must('new LinkedBlockingQueue<>(queueCapacity)' in w and 'new LinkedBlockingQueue<>()' not in w,'Unbounded queue')
    must('catch (RejectedExecutionException full)' in w and 'locallyQueued.remove(jobId);' in w,'Overflow may strand work')
    must('scanMetrics.recordDispatchDeferred()' in w and 'dispatchDeferredCounter.increment()' in m,'Lost overload metric')
    must('implements SmartLifecycle' in w and '@PreDestroy' in w and 'public void stop()' in w and
         'pool.shutdownNow()' in w and 'pool.awaitTermination' in w, 'Scan executor has no coordinated shutdown')
    must('if (stopping || executor == null || executor.isShutdown()) return;' in w and
         'for (Runnable pending : pool.getQueue().toArray' in w,
         'Shutdown could dispatch new jobs or drain durable pending work')
    must('checkCancellation();' in w and 'if (cancelling || Thread.currentThread().isInterrupted()' in w,
         'Forced scan cancellation could produce terminal ERROR verdicts')
    must('if (!executor.acceptingWork()) return;' in
         (r/'src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java').read_text(),
         'Queue sweeper could access database after shutdown')
    must((r/'src/test/java/info/trizub/clamav/webclient/service/ScanExecutionShutdownLifecycleTest.java').is_file(),
         'No concurrency and restart tests for scan shutdown')
    must('app.scan.queue-capacity=${APP_SCAN_QUEUE_CAPACITY:256}' in (r/'src/main/resources/application.properties').read_text(),'No capacity control')
    must((r/'src/test/java/info/trizub/clamav/webclient/service/ScanExecutionQueueCapacityTest.java').is_file(),'No Java overload test')
    ci=yaml.load((r/'.github/workflows/ci.yml').read_text(),Loader=yaml.BaseLoader)
    release=yaml.load((r/'.github/workflows/release.yml').read_text(),Loader=yaml.BaseLoader)
    quality='\n'.join(str(step.get('run','')) for step in ci['jobs']['java-quality']['steps'])
    must('issue58-coverage-ratchet.py --baseline' in quality,'Coverage regression check absent')
    must('-Pci-quality verify' in quality and '-DskipTests -Djacoco.skip=true verify' not in quality,'Coverage runs with tests disabled')
    must('spotless:check' in quality,'Formatter enforcement removed')
    chooser=(r/'scripts/issue58-resolve-predecessor.py').read_text()
    must('merge-base' in chooser and 'published_at' in chooser and 'fetch_releases' in chooser,'Predecessor selection unsafe')
    runtime=(r/'scripts/issue57-upgrade-path.py').read_text()
    must('args.resolved_predecessor.read_text()' in runtime and "if actual != cfg['commit_sha']" in runtime,'Moved predecessor tag not reverified')
    must("probe('version',0)" in runtime and 'expected_versions' in runtime,'Hard-coded Flyway version')
    for job in (ci['jobs']['upgrade-path'],release['jobs']['upgrade-path']):
        steps='\n'.join(str(st.get('run','')) for st in job['steps'])
        must('issue58-resolve-predecessor.py' in steps and 'issue57-upgrade-path.py' in steps,'Upgrade job not bound to resolved release')
    must('upgrade-path' in ci['jobs']['gate']['needs'],'Required CI gate bypass')
    must('upgrade-path' in release['jobs']['image-publish']['needs'],'Release could publish before migration check')
    static='\n'.join(str(st.get('run','')) for st in ci['jobs']['static']['steps'])
    must('check-issue58-hardening.py' in static and 'test-issue58-hardening.py' in static,'Hardening not enforced in CI')
    print('PASS: bounded scan workers and shutdown lifecycle, JaCoCo nonregression, immutable predecessor gates')
if __name__=='__main__':
    try:check()
    except (ValueError,KeyError) as exc:sys.exit('FAIL: '+str(exc))
