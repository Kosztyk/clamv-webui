package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

@Service
public class ScanExecutionService implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ScanExecutionService.class);

  private final SettingsService settings;
  private final ObjectMapper resultMapper;
  private final ScanJobRepository jobRepo;
  private final ClamavClientProvider clientProvider;
  private final QuarantineService quarantineService;
  private final NotificationService notificationService;
  private final ScanJobFinalizationService finalizer;
  private final QuarantineArtifactCoordinator artifactCoordinator;
  private final info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics;
  private final DaemonScanPolicy daemonPolicy;
  private final UploadStorageService uploads;

  /** Only job IDs are queued locally; overflow is retained durably in the DB. */
  @Value("${app.scan.queue-capacity:256}")
  private int queueCapacity = 256;

  /** Preserve a bounded stop budget below the default 30s Spring lifecycle phase timeout. */
  @Value("${app.scan.shutdown-grace-seconds:20}")
  private int shutdownGraceSeconds = 20;

  @Value("${app.scan.shutdown-force-seconds:5}")
  private int shutdownForceSeconds = 5;

  @Value("${spring.lifecycle.timeout-per-shutdown-phase:45s}")
  private java.time.Duration lifecyclePhaseTimeout = java.time.Duration.ofSeconds(45);

  private final Object dispatchLock = new Object();
  private volatile ThreadPoolExecutor executor;
  private volatile boolean stopping;
  private volatile boolean cancelling;
  private final Set<String> locallyQueued = ConcurrentHashMap.newKeySet();

  public ScanExecutionService(
      SettingsService settings,
      ScanJobRepository jobRepo,
      ObjectMapper mapper,
      ClamavClientProvider clientProvider,
      QuarantineService quarantineService,
      NotificationService notificationService,
      AuditService auditService,
      info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
      DaemonScanPolicy daemonPolicy) {
    this(
        settings,
        jobRepo,
        mapper,
        clientProvider,
        quarantineService,
        notificationService,
        auditService,
        scanMetrics,
        daemonPolicy,
        new UploadStorageService(settings, jobRepo));
  }

  public ScanExecutionService(
      SettingsService settings,
      ScanJobRepository jobRepo,
      ObjectMapper mapper,
      ClamavClientProvider clientProvider,
      QuarantineService quarantineService,
      NotificationService notificationService,
      AuditService auditService,
      info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads) {
    this(
        settings,
        jobRepo,
        mapper,
        clientProvider,
        quarantineService,
        notificationService,
        auditService,
        scanMetrics,
        daemonPolicy,
        uploads,
        new ScanJobFinalizationService(jobRepo, mapper, auditService, scanMetrics));
  }

  public ScanExecutionService(
      SettingsService settings,
      ScanJobRepository jobRepo,
      ObjectMapper mapper,
      ClamavClientProvider clientProvider,
      QuarantineService quarantineService,
      NotificationService notificationService,
      AuditService auditService,
      info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      ScanJobFinalizationService finalizer) {
    this(
        settings,
        jobRepo,
        mapper,
        clientProvider,
        quarantineService,
        notificationService,
        auditService,
        scanMetrics,
        daemonPolicy,
        uploads,
        finalizer,
        (QuarantineArtifactCoordinator) null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScanExecutionService(
      SettingsService settings,
      ScanJobRepository jobRepo,
      ObjectMapper mapper,
      ClamavClientProvider clientProvider,
      QuarantineService quarantineService,
      NotificationService notificationService,
      AuditService auditService,
      info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      ScanJobFinalizationService finalizer,
      org.springframework.beans.factory.ObjectProvider<QuarantineArtifactCoordinator>
          coordinatorProvider) {
    this(
        settings,
        jobRepo,
        mapper,
        clientProvider,
        quarantineService,
        notificationService,
        auditService,
        scanMetrics,
        daemonPolicy,
        uploads,
        finalizer,
        coordinatorProvider.getIfAvailable());
  }

  private ScanExecutionService(
      SettingsService settings,
      ScanJobRepository jobRepo,
      ObjectMapper mapper,
      ClamavClientProvider clientProvider,
      QuarantineService quarantineService,
      NotificationService notificationService,
      AuditService auditService,
      info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      ScanJobFinalizationService finalizer,
      QuarantineArtifactCoordinator artifactCoordinator) {
    this.settings = settings;
    this.resultMapper = mapper;
    this.jobRepo = jobRepo;
    this.clientProvider = clientProvider;
    this.quarantineService = quarantineService;
    this.notificationService = notificationService;
    this.finalizer = finalizer;
    this.artifactCoordinator = artifactCoordinator;
    this.scanMetrics = scanMetrics;
    this.daemonPolicy = daemonPolicy;
    this.uploads = uploads;
  }

  @PostConstruct
  public void init() {
    int threads = Math.max(1, settings.concurrentScans());
    if (queueCapacity < 1 || queueCapacity > 10_000)
      throw new IllegalArgumentException("app.scan.queue-capacity must be between 1 and 10000");
    if (shutdownGraceSeconds < 0
        || shutdownForceSeconds < 0
        || lifecyclePhaseTimeout == null
        || lifecyclePhaseTimeout.toMillis() < 5000
        || (long) shutdownGraceSeconds + shutdownForceSeconds > 40
        || (long) shutdownGraceSeconds + shutdownForceSeconds
            > lifecyclePhaseTimeout.toSeconds() - 5)
      throw new IllegalArgumentException(
          "Combined scan shutdown waits must be <=40s and "
              + "at least 5s shorter than Spring's configured shutdown-phase timeout; "
              + "the packaged Docker stop grace is 60s");
    synchronized (dispatchLock) {
      if (executor != null && !executor.isTerminated())
        throw new IllegalStateException("Scan executor has already started");
      stopping = false;
      cancelling = false;
      locallyQueued.clear();
      executor =
          new ThreadPoolExecutor(
              threads,
              threads,
              60L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(queueCapacity),
              r -> {
                Thread t = new Thread(r);
                t.setName("scan-worker-" + t.threadId());
                // A daemon fallback is essential if a remote clamd socket ignores
                // interrupt after both bounded shutdown waits. The lifecycle still
                // waits for ordinary jobs before allowing bean destruction.
                t.setDaemon(true);
                return t;
              });
      scanMetrics.bindExecutor(executor);
    }
    log.info("Scan executor initialized with {} workers and {} queued IDs", threads, queueCapacity);
  }

  /**
   * Dispatch only committed IDs. Stop and submit are serialized so a job cannot enter the executor
   * after shutdown has started; rejected work stays QUEUED.
   */
  public void enqueue(String jobId) {
    synchronized (dispatchLock) {
      if (stopping || executor == null || executor.isShutdown()) return;
      // A periodic recovery sweep must not multiply tasks in the local queue.
      if (!locallyQueued.add(jobId)) return;
      log.debug("Enqueue scan job {}", jobId);
      try {
        executor.submit(
            () -> {
              try {
                runJob(jobId);
              } finally {
                locallyQueued.remove(jobId);
              }
            });
      } catch (RejectedExecutionException full) {
        // The DB row remains QUEUED. Reconciliation retries after overload;
        // during shutdown the next application instance picks it up.
        locallyQueued.remove(jobId);
        scanMetrics.recordDispatchDeferred();
        log.debug("Deferred scan {} because the bounded worker queue is full or stopping", jobId);
      } catch (RuntimeException ex) {
        locallyQueued.remove(jobId);
        throw ex;
      }
    }
  }

  /** Used by the scheduled sweeper to avoid database queries during shutdown. */
  public boolean acceptingWork() {
    return !stopping && executor != null && !executor.isShutdown();
  }

  /**
   * A staged positive may only be recovered after its original worker has completely exited. This
   * set includes currently executing and queued IDs.
   */
  public boolean isLocallyInFlight(String jobId) {
    return locallyQueued.contains(jobId);
  }

  /**
   * This lifecycle stops before singleton destruction (and before Hikari closes). Spring stops
   * higher phases first; the web server is stopped before this phase. Keep the default 20s+5s below
   * Spring's 30s per-phase budget and Docker's 60s.
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 2048;
  }

  @Override
  public boolean isRunning() {
    return acceptingWork();
  }

  @Override
  public void start() {
    /* Executor starts in @PostConstruct, before ScanJobService startup recovery. */
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public void stop() {
    ThreadPoolExecutor pool;
    int abandoned;
    synchronized (dispatchLock) {
      if (stopping) return; // SmartLifecycle and @PreDestroy may both invoke stop.
      stopping = true;
      pool = executor;
      if (pool == null) return;
      pool.shutdown(); // Active scans may persist their verdict during grace.
      abandoned = pool.getQueue().size();
      // QUEUED rows survive in the database; don't start further scans while
      // waiting for running jobs. Cancel their never-to-run FutureTasks.
      for (Runnable pending : pool.getQueue().toArray(new Runnable[0])) {
        if (pool.remove(pending) && pending instanceof Future<?> future) future.cancel(false);
      }
      locallyQueued.clear();
    }
    log.info(
        "Stopping scan executor: {} pending tasks left durable for restart, {} active workers",
        abandoned,
        pool.getActiveCount());
    try {
      if (pool.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
        log.info("Scan executor stopped cleanly");
        return;
      }
      // Interrupt clamd operations after the grace period. They must not
      // turn into ERROR verdicts; RUNNING rows are retried on next startup.
      cancelling = true;
      int interrupted = pool.shutdownNow().size();
      log.warn(
          "Scan shutdown grace expired; interrupted active workers; {} pending tasks remain durable",
          interrupted);
      if (!pool.awaitTermination(shutdownForceSeconds, TimeUnit.SECONDS))
        log.warn(
            "Scan workers did not stop after forced cancellation; JVM may exit while clamd I/O is blocked. RUNNING jobs will be recovered on restart");
    } catch (InterruptedException ex) {
      cancelling = true;
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      log.warn("Scan shutdown interrupted; remaining RUNNING jobs will be recovered on restart");
    }
  }

  @PreDestroy
  public void destroy() {
    stop();
  } // Safety net for contexts without lifecycle processing.

  private void checkCancellation() {
    if (cancelling || Thread.currentThread().isInterrupted()) throw new ScanShutdownCancellation();
  }

  private static final class ScanShutdownCancellation extends RuntimeException {
    private ScanShutdownCancellation() {
      super("Scan interrupted by application shutdown");
    }
  }

  private void runJob(String jobId) {
    // A confirmed positive result may NEVER be overwritten with ERROR because
    // post-scan/quarantine/persistence work fails. Recovery uses its staged verdict.
    boolean positiveVerdict = false;
    // Queued work popped concurrently with stop must stay QUEUED in the DB.
    if (stopping) return;
    checkCancellation();
    ScanJob job = jobRepo.findById(jobId).orElse(null);
    if (job == null) {
      log.warn("Enqueued scan job {} but it does not exist in DB", jobId);
      return;
    }

    log.debug(
        "Starting scan job {} type={} endpoint={}",
        jobId,
        job.getType(),
        job.getEndpoint() != null ? job.getEndpoint().getName() : "null");

    try {
      // A scan already running may finish during graceful shutdown, but a
      // newly dequeued ID must not claim a database row after stop begins.
      boolean claimed;
      synchronized (dispatchLock) {
        if (stopping) return;
        claimed = markRunning(jobId);
      }
      if (!claimed) {
        log.info("Job {} already claimed/completed; ignoring duplicate queue item", jobId);
        return;
      }
      log.debug("Job {} atomically claimed RUNNING", jobId);

      if (job.getEndpoint() == null || !job.getEndpoint().isEnabled())
        throw new IllegalArgumentException("Endpoint is disabled");

      ClamdClient client = clientProvider.clientFor(job.getEndpoint());

      if (job.getType() == ScanJobType.UPLOAD) {
        Path stored = Paths.get(job.getStoredPath()).toAbsolutePath().normalize();
        // Recheck queued work against refreshed configuration before any daemon command.
        daemonPolicy.requireUpload(Files.size(stored), job.getEndpoint());
        daemonPolicy.requirePath(stored, job.getEndpoint());

        ScanResult result;
        if (settings.uploadPathScanEnabled()) {
          // Only the native ClamD command belongs inside this catch.
          // Quarantine, database persistence and notifications happen once,
          // after selecting the scan transport, and must not trigger a rescan.
          try {
            result = runNativeUploadPathScan(job, stored, client);
          } catch (IOException pathScanError) {
            checkCancellation();
            String message =
                "Path-based upload scan failed for "
                    + stored
                    + ". "
                    + "Native clamdtop can show filenames only when clamd receives a SCAN/MULTISCAN path "
                    + "and the clamd process can read that exact path. Configure a secure read-only "
                    + "upload-only mount with matching file ownership, or enable INSTREAM fallback "
                    + "to keep old INSTREAM behavior. Details: "
                    + pathScanError.getMessage();

            if (!settings.uploadPathScanFallbackToInstream()) {
              log.warn(message, pathScanError);
              finishError(jobId, message);
              notifyIfNeeded(jobId);
              return;
            }

            log.warn(
                "{} Falling back to INSTREAM because fallback is enabled.", message, pathScanError);
            try (InputStream in = Files.newInputStream(stored)) {
              result = client.scan(in);
            }
          }
        } else {
          try (InputStream in = Files.newInputStream(stored)) {
            result = client.scan(in);
          }
        }
        positiveVerdict = result instanceof ScanResult.VirusFound;
        handleResult(jobId, job.getType(), result, stored);
      } else if (job.getType() == ScanJobType.PATH || job.getType() == ScanJobType.WATCH) {
        Path submitted = Paths.get(job.getTarget());
        Path target = PathPolicy.requireAllowed(submitted, settings.allowedRoots());
        if (!submitted.isAbsolute() || !submitted.equals(target))
          throw new IllegalArgumentException(
              "Scan path changed after submission; submit a new scan");
        // Pin file-versus-directory scope before asking the daemon to scan.
        // A replacement between scanning and quarantine must not widen it.
        boolean scanTargetWasDirectory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        DaemonScanPolicy.PathScanReport report =
            daemonPolicy.scanPathReport(client, job.getEndpoint(), target);
        positiveVerdict = report.result() instanceof ScanResult.VirusFound;
        handleResult(
            jobId,
            job.getType(),
            report.result(),
            null,
            report.failureSummary(),
            target,
            scanTargetWasDirectory);
        if (report.partial())
          log.warn(
              "Directory job {} completed with {} unreadable or unscannable entries; {} successfully scanned",
              jobId,
              report.failedFiles(),
              report.scannedFiles());
      } else {
        finishError(jobId, "Unsupported job type: " + job.getType());
        log.debug("Job {} finished ERROR (unsupported type)", jobId);
      }

      // Re-read to report final persisted state only while database work is safe.
      checkCancellation();
      ScanJob finished = jobRepo.findById(jobId).orElse(null);
      if (finished != null) {
        log.debug(
            "Finished scan job {} status={} verdict={}",
            jobId,
            finished.getStatus(),
            finished.getVerdict());
      }
    } catch (Exception e) {
      if (cancelling
          || Thread.currentThread().isInterrupted()
          || e instanceof ScanShutdownCancellation) {
        // Do not turn an interrupted scan into a terminal ERROR. Startup
        // requeueInterrupted() will retry the RUNNING row after restart.
        log.info("Scan {} interrupted for shutdown; retaining recoverable database state", jobId);
        return;
      }
      if (positiveVerdict) {
        // Preserve RUNNING + staged positive evidence; startup reconciliation
        // safely finishes this as VIRUS_FOUND instead of rescanning moved files.
        log.error(
            "Job {} has a positive ClamAV result but could not finalize; "
                + "retaining staged verdict for restart recovery",
            jobId,
            e);
        return;
      }
      log.error("Job {} failed", jobId, e);
      try {
        finishError(jobId, e.getMessage());
        log.debug("Job {} finished ERROR: {}", jobId, e.getMessage());
      } catch (Exception inner) {
        log.error("Job {} failed to persist ERROR state", jobId, inner);
      }
      notifyIfNeeded(jobId);
    } finally {
      // Cleanup consults the repository. Do not access Hikari once a forced
      // stop could outlive the Spring shutdown lifecycle.
      if (!cancelling && !Thread.currentThread().isInterrupted()) uploads.cleanupFinished(jobId);
    }
  }

  private void handleResult(String jobId, ScanJobType type, ScanResult result, Path uploadedFile) {
    handleResult(jobId, type, result, uploadedFile, null, null, false);
  }

  /** Process the daemon's untrusted filenames only within the authorized scan scope. */
  private void handleResult(
      String jobId,
      ScanJobType type,
      ScanResult result,
      Path uploadedFile,
      String partialWarning,
      Path scanTarget,
      boolean scanTargetWasDirectory) {
    checkCancellation();
    var limited = DetectionReportLimiter.limit(result, resultMapper);
    result = limited.result();
    partialWarning = DetectionReportLimiter.joinWarnings(partialWarning, limited.warning());
    if (result instanceof ScanResult.OK) {
      if (partialWarning == null) finishOk(jobId);
      else finishError(jobId, partialWarning);
      notifyIfNeeded(jobId);
    } else if (result instanceof ScanResult.VirusFound vf) {
      Map<String, Collection<String>> found = vf.getFoundViruses();
      boolean quarantineRequested = settings.quarantineEnabled();
      if (artifactCoordinator != null) {
        // Stage all positive verdicts, including uploads, before quarantine.
        // A failed journal/stage write is fail-closed (no untracked file move).
        finalizer.stageDetections(
            jobId,
            found,
            partialWarning,
            type == ScanJobType.PATH || type == ScanJobType.WATCH ? scanTargetWasDirectory : null,
            quarantineRequested);
      }
      Path quarantinePath = null;
      String quarantineWarning = null;
      if (uploadedFile != null) {
        try {
          var outcome =
              !quarantineRequested
                  ? new QuarantineService.Result(null, false)
                  : artifactCoordinator == null
                      ? quarantineService.attempt(uploadedFile, jobId)
                      : artifactCoordinator.attemptUpload(
                          jobId,
                          uploadedFile,
                          found.values().stream()
                              .filter(java.util.Objects::nonNull)
                              .flatMap(Collection::stream)
                              .toList());
          quarantinePath = outcome.path();
          if (outcome.failed() || (quarantineRequested && !settings.quarantineEnabled()))
            quarantineWarning = QuarantineService.UPLOAD_FAILURE;
        } catch (RuntimeException ex) {
          if (ex instanceof QuarantineArtifactCoordinator.JournalCommitFailure) throw ex;
          // Once ClamD returned VirusFound, secondary file handling cannot rewrite that verdict.
          log.warn("Upload quarantine failed after a positive scan for job {}", jobId, ex);
          quarantineWarning = QuarantineService.UPLOAD_FAILURE;
        }
      } else if ((type == ScanJobType.PATH || type == ScanJobType.WATCH) && found != null) {
        // Reserve evidence for ALL detections before the first filesystem
        // move. This also journals rejected paths as FAILED. A crash after
        // staging but before reservation must never move a replacement
        // file by its pathname on startup.
        if (artifactCoordinator != null) {
          var prepared =
              quarantineRequested
                  ? artifactCoordinator.prepareSources(
                      jobId, found, scanTarget, settings.allowedRoots(), scanTargetWasDirectory)
                  : java.util.List.<info.trizub.clamav.webclient.model.QuarantineArtifact>of();
          if (quarantineRequested && !settings.quarantineEnabled())
            quarantineWarning = QuarantineService.SOURCE_FAILURE;
          for (var artifact : prepared) {
            checkCancellation();
            var outcome =
                artifactCoordinator.executePreparedSource(
                    jobId, artifact, scanTarget, settings.allowedRoots(), scanTargetWasDirectory);
            if (outcome.path() != null && quarantinePath == null) quarantinePath = outcome.path();
            if (outcome.failed()) quarantineWarning = QuarantineService.SOURCE_FAILURE;
          }
        } else {
          // Standalone constructor-only fixtures retain the unjournaled
          // API; the actual Spring application always injects coordinator.
          for (String reported : found.keySet()) {
            checkCancellation();
            try {
              Path candidate = Paths.get(reported);
              var outcome =
                  quarantineService.attemptSource(
                      candidate, scanTarget, settings.allowedRoots(), scanTargetWasDirectory);
              if (outcome.path() != null && quarantinePath == null) quarantinePath = outcome.path();
              if (outcome.failed()) quarantineWarning = QuarantineService.SOURCE_FAILURE;
            } catch (RuntimeException ex) {
              if (ex instanceof ScanShutdownCancellation) throw ex;
              log.warn("Rejected untrusted source quarantine entry for job {}", jobId);
              quarantineWarning = QuarantineService.SOURCE_FAILURE;
            }
          }
        }
      }

      String warning =
          partialWarning == null
              ? quarantineWarning
              : quarantineWarning == null
                  ? partialWarning
                  : partialWarning + "\n" + quarantineWarning;
      finishFound(jobId, found, quarantinePath, warning);
      log.debug("Job {} finished VIRUS_FOUND", jobId);
      notifyIfNeeded(jobId);
    } else {
      finishError(jobId, "Unknown scan result type: " + result);
      log.debug("Job {} finished ERROR (unknown result)", jobId);
      notifyIfNeeded(jobId);
    }
  }

  /**
   * Run uploaded files through clamd's native MULTISCAN command using the file path.
   *
   * <p>This is what native clamdtop needs in order to display the filename. INSTREAM sends bytes
   * over TCP and clamd only sees a stream connection, so clamdtop can only display
   * instream(client-ip@port). MULTISCAN sends a real filesystem path, so clamdtop can display
   * MULTISCANFILE /app/data/uploads/original-name.
   *
   * <p>Only execute the ClamD operation here. Result finalization is transport-independent.
   */
  private ScanResult runNativeUploadPathScan(ScanJob job, Path stored, ClamdClient client)
      throws IOException {
    ClamdEndpoint endpoint = job.getEndpoint();
    if (endpoint == null) {
      throw new IOException("No clamd endpoint configured for job " + job.getId());
    }
    if (!Files.isRegularFile(stored)) {
      throw new IOException("Upload file is missing in the web container: " + stored);
    }

    ScanResult result = client.parallelScan(stored);
    log.debug("Job {} completed native MULTISCAN through the bounded ClamD client", job.getId());
    return result;
  }

  private void notifyIfNeeded(String jobId) {
    if (cancelling || Thread.currentThread().isInterrupted()) return;
    try {
      notificationService.notifyIfNeeded(jobRepo.findById(jobId).orElseThrow());
    } catch (Exception ex) {
      // Scanning is complete; a failed webhook must not rewrite a terminal verdict.
      log.warn(
          "Scan notification delivery failed for job {} ({})",
          jobId,
          ex.getClass().getSimpleName());
    }
  }

  public boolean markRunning(String id) {
    return jobRepo.claimQueued(id, ScanJobStatus.QUEUED, ScanJobStatus.RUNNING, Instant.now()) == 1;
  }

  /** Transaction boundaries live in a different Spring bean, not on self-invoked methods. */
  public void finishOk(String id) {
    checkCancellation();
    finalizer.finishOk(id);
  }

  public void finishFound(String id, Object foundViruses) {
    finishFound(id, foundViruses, null, null);
  }

  private void finishFound(
      String id, Object foundViruses, Path quarantinePath, String quarantineWarning) {
    checkCancellation();
    finalizer.finishFound(id, foundViruses, quarantinePath, quarantineWarning);
  }

  public void finishError(String id, String message) {
    checkCancellation();
    finalizer.finishError(id, message);
  }

  public void setQuarantinePath(String id, String quarantinePath) {
    checkCancellation();
    finalizer.setQuarantinePath(id, quarantinePath);
  }
}
