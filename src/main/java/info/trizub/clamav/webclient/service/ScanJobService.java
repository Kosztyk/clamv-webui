package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ScanJobService {

  private static final Logger log = LoggerFactory.getLogger(ScanJobService.class);

  private final ScanJobRepository repo;
  private final SettingsService settings;
  private final ScanExecutionService executor;
  private final DaemonScanPolicy daemonPolicy;
  private final UploadStorageService uploads;
  private final UploadJobCommitter uploadCommitter;
  private final QuarantineArtifactCoordinator quarantineRecovery;

  public ScanJobService(
      ScanJobRepository repo,
      SettingsService settings,
      ScanExecutionService executor,
      DaemonScanPolicy daemonPolicy) {
    this(repo, settings, executor, daemonPolicy, new UploadStorageService(settings, repo));
  }

  public ScanJobService(
      ScanJobRepository repo,
      SettingsService settings,
      ScanExecutionService executor,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads) {
    this(repo, settings, executor, daemonPolicy, uploads, new UploadJobCommitter(repo, executor));
  }

  public ScanJobService(
      ScanJobRepository repo,
      SettingsService settings,
      ScanExecutionService executor,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      UploadJobCommitter uploadCommitter) {
    this(
        repo,
        settings,
        executor,
        daemonPolicy,
        uploads,
        uploadCommitter,
        (QuarantineArtifactCoordinator) null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScanJobService(
      ScanJobRepository repo,
      SettingsService settings,
      ScanExecutionService executor,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      UploadJobCommitter uploadCommitter,
      org.springframework.beans.factory.ObjectProvider<QuarantineArtifactCoordinator>
          recoveryProvider) {
    this(
        repo,
        settings,
        executor,
        daemonPolicy,
        uploads,
        uploadCommitter,
        recoveryProvider.getIfAvailable());
  }

  private ScanJobService(
      ScanJobRepository repo,
      SettingsService settings,
      ScanExecutionService executor,
      DaemonScanPolicy daemonPolicy,
      UploadStorageService uploads,
      UploadJobCommitter uploadCommitter,
      QuarantineArtifactCoordinator quarantineRecovery) {
    this.quarantineRecovery = quarantineRecovery;
    this.repo = repo;
    this.settings = settings;
    this.executor = executor;
    this.daemonPolicy = daemonPolicy;
    this.uploads = uploads;
    this.uploadCommitter = uploadCommitter;
  }

  private static final int RECOVERY_BATCH = 100;
  private static final int PREVIEW_SIZE = 25;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int LEGACY_API_LIST_SIZE = 200;
  private static final int RECOVERY_PAGE_SIZE = 64;
  private static final int RECOVERY_ATTEMPTS_PER_TICK = 4;
  private static final int RECOVERY_BACKOFF_CAPACITY = 512;
  private final AtomicBoolean recoveryPolling = new AtomicBoolean();
  // Keyset cursors are process-local because multi-replica execution is unsupported.
  private String stagedRecoveryCursor = "";
  private String orphanRecoveryCursor = "";
  private final LinkedHashMap<String, RecoveryBackoff> retryAfterFailure = new LinkedHashMap<>();

  private record RecoveryBackoff(int failures, Instant nextAttempt) {}

  @PostConstruct
  public void resumeQueued() {
    // Reconcile filesystem moves before resetting RUNNING jobs: a moved infected
    // source must never be retried as a missing file and rewritten as ERROR.
    if (quarantineRecovery != null) quarantineRecovery.recoverInterruptedQuarantine();
    // The update touches only unfinished RUNNING records, using the status
    // index. Never materialize a potentially million-row history on boot.
    int interrupted = repo.requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED);
    if (interrupted > 0) log.info("Requeued {} interrupted scans", interrupted);
    enqueueNextBatch();
  }

  private void enqueueNextBatch() {
    for (String id : repo.findQueuedIds(ScanJobStatus.QUEUED, PageRequest.of(0, RECOVERY_BATCH))) {
      executor.enqueue(id);
    }
  }

  /**
   * Periodically recover persisted QUEUED work in bounded batches. The worker's CAS claim and
   * in-memory deduplication prevent duplicate scans. RUNNING jobs are reset only on single-instance
   * startup, not on live poll.
   */
  @Scheduled(fixedDelayString = "${app.scan.reconcile-interval-ms:60000}")
  public void reconcileQueued() {
    if (!executor.acceptingWork()) return;
    try {
      enqueueNextBatch();
    } catch (Exception ex) {
      log.warn(
          "Scan queue reconciliation deferred while database unavailable: {}", ex.getMessage());
    }
  }

  /**
   * Recover stranded work while the application stays up after a transient DB outage. Never touch a
   * job still owned by a local worker, and never retry staged malware as a fresh scan of a possibly
   * moved/replaced source.
   */
  @Scheduled(
      fixedDelayString = "${app.scan.staged-recovery-interval-ms:60000}",
      initialDelayString = "${app.scan.staged-recovery-interval-ms:60000}")
  public void reconcileInterrupted() {
    if (!executor.acceptingWork() || !recoveryPolling.compareAndSet(false, true)) return;
    try {
      recoverStagedPage();
      recoverOrphanPage();
    } catch (Exception ex) {
      log.warn(
          "Live scan reconciliation deferred while storage unavailable ({})",
          ex.getClass().getSimpleName());
    } finally {
      recoveryPolling.set(false);
    }
  }

  private void recoverStagedPage() {
    if (quarantineRecovery == null) return;
    List<String> ids =
        repo.findStagedPositiveIdsAfter(
            ScanJobStatus.RUNNING, stagedRecoveryCursor, PageRequest.of(0, RECOVERY_PAGE_SIZE));
    if (ids.isEmpty()) {
      stagedRecoveryCursor = "";
      return;
    }
    int attempted = 0;
    boolean visitedEveryRow = true;
    for (String id : ids) {
      stagedRecoveryCursor = id;
      if (executor.isLocallyInFlight(id)) continue;
      if (!eligibleForRecovery(id)) continue;
      try {
        quarantineRecovery.recoverStagedJob(id);
        retryAfterFailure.remove(id);
      } catch (Exception ex) {
        deferRecovery(id);
        log.warn(
            "Staged infected scan {} needs another recovery attempt ({})",
            id,
            ex.getClass().getSimpleName());
      }
      if (++attempted >= RECOVERY_ATTEMPTS_PER_TICK) {
        visitedEveryRow = id.equals(ids.get(ids.size() - 1));
        break;
      }
    }
    if (visitedEveryRow && ids.size() < RECOVERY_PAGE_SIZE) stagedRecoveryCursor = "";
  }

  private void recoverOrphanPage() {
    // Allow long scans to run: an orphan is eligible only after five minutes
    // AND after the worker's in-memory ownership has disappeared.
    List<String> ids =
        repo.findOrphanRunningIdsAfter(
            ScanJobStatus.RUNNING,
            Instant.now().minusSeconds(300),
            orphanRecoveryCursor,
            PageRequest.of(0, RECOVERY_PAGE_SIZE));
    if (ids.isEmpty()) {
      orphanRecoveryCursor = "";
      return;
    }
    int attempted = 0;
    boolean visitedEveryRow = true;
    for (String id : ids) {
      orphanRecoveryCursor = id;
      if (executor.isLocallyInFlight(id) || !eligibleForRecovery(id)) continue;
      try {
        if (repo.requeueOrphan(id, ScanJobStatus.RUNNING, ScanJobStatus.QUEUED) == 1)
          executor.enqueue(id);
        retryAfterFailure.remove(id);
      } catch (Exception ex) {
        deferRecovery(id);
        log.warn("Orphan scan {} requeue deferred ({})", id, ex.getClass().getSimpleName());
      }
      if (++attempted >= RECOVERY_ATTEMPTS_PER_TICK) {
        visitedEveryRow = id.equals(ids.get(ids.size() - 1));
        break;
      }
    }
    if (visitedEveryRow && ids.size() < RECOVERY_PAGE_SIZE) orphanRecoveryCursor = "";
  }

  private boolean eligibleForRecovery(String id) {
    RecoveryBackoff state = retryAfterFailure.get(id);
    return state == null || !Instant.now().isBefore(state.nextAttempt());
  }

  private void deferRecovery(String id) {
    RecoveryBackoff previous = retryAfterFailure.get(id);
    int failures = Math.min(10, previous == null ? 1 : previous.failures() + 1);
    long seconds = Math.min(1800L, 30L << Math.min(failures - 1, 6));
    if (!retryAfterFailure.containsKey(id) && retryAfterFailure.size() >= RECOVERY_BACKOFF_CAPACITY)
      retryAfterFailure.remove(retryAfterFailure.keySet().iterator().next());
    retryAfterFailure.put(id, new RecoveryBackoff(failures, Instant.now().plusSeconds(seconds)));
  }

  /** Lightweight recent preview used on dashboard and scan pages. */
  public List<ScanJobSummary> latest() {
    return repo.findRecentSummaries(PageRequest.of(0, PREVIEW_SIZE));
  }

  /** Preserve the legacy 200-item API list size without loading TEXT columns. */
  public List<ScanJobSummary> latestForLegacyApi() {
    return repo.findRecentSummaries(PageRequest.of(0, LEGACY_API_LIST_SIZE));
  }

  /** Bounded, deterministic job history pages (UI only). */
  public Page<ScanJobSummary> page(int page, int size) {
    int boundedPage = Math.max(0, page);
    int boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    return repo.findSummaries(
        PageRequest.of(
            boundedPage,
            boundedSize,
            Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id"))));
  }

  public long uploadMaxBytes(ClamdEndpoint endpoint) {
    return daemonPolicy.uploadMaxBytes(endpoint);
  }

  /**
   * Fetch a job by id.
   *
   * <p>NOTE: The web UI should not 500 if a job id is missing (e.g. stale link), so this method
   * returns null when not found.
   */
  public ScanJob getOrNull(String id) {
    return repo.findById(id).orElse(null);
  }

  /**
   * Shared browser/API entry point. Stream and hash files before starting a short transactional
   * metadata commit in {@link UploadJobCommitter}; never keep a DB connection checked out while
   * reading multipart request bodies.
   */
  public List<ScanJob> createUploadJobs(
      List<MultipartFile> files, ClamdEndpoint endpoint, String username, String transport) {
    // Avoid even an empty transaction synchronization scope around slow input streams.
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new org.springframework.transaction.IllegalTransactionStateException(
          "Upload copying must be called outside an active database transaction");
    }
    requireEnabled(endpoint);
    List<ScanJob> jobs = new ArrayList<>();
    record PendingUpload(MultipartFile file, String id, String name, String label, Path stored) {}
    List<PendingUpload> pending = new ArrayList<>();
    // Validate the entire batch before copying files or queuing any job.
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) continue;
      daemonPolicy.requireUpload(file.getSize(), endpoint);
      String id = UUID.randomUUID().toString().replace("-", "");
      String name =
          info.trizub.clamav.webclient.util.UploadNames.stored(file.getOriginalFilename());
      Path stored = settings.uploadDir().resolve(id + "-" + name);
      daemonPolicy.requireNotExcluded(stored, endpoint);
      pending.add(
          new PendingUpload(
              file,
              id,
              name,
              info.trizub.clamav.webclient.util.UploadNames.display(file.getOriginalFilename()),
              stored));
    }
    try (var batch = uploads.beginBatch()) {
      for (PendingUpload upload : pending) {
        MultipartFile f = upload.file();
        String id = upload.id();
        String safeName = upload.name();
        Path stored = upload.stored();
        try {
          Path created = batch.reserve(id, safeName);
          if (!created.equals(stored))
            throw new IllegalStateException("Upload storage changed; retry the batch");

          // A Tomcat-backed multipart part can move its spooled temporary file with
          // transferTo(File) when both directories share a filesystem. This avoids
          // a second full file write. For non-servlet MultipartFile implementations
          // retain the original copy+hash single pass (also used by existing tests).
          // Both branches keep the destination inside the private 0700 upload root.
          String sha;
          long copied;
          boolean movedServletPart = false;
          if (isServletMultipart(f)) {
            // Use a reserved, NOT precreated destination so Tomcat Part.write()
            // can rename its spool file on the same filesystem. Some compliant
            // Part implementations (including Spring MockPart) do not support
            // write/transferTo; use the one-pass streaming fallback for those.
            try {
              f.transferTo(stored.toFile());
              movedServletPart = true;
            } catch (UnsupportedOperationException unsupported) {
              if (Files.exists(stored, LinkOption.NOFOLLOW_LINKS))
                throw new java.io.IOException(
                    "Unsupported multipart transfer left a destination file", unsupported);
            }
          }
          if (movedServletPart) {
            if (!Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS))
              throw new java.io.IOException("Multipart transfer did not produce a regular file");
            info.trizub.clamav.webclient.util.PrivateFiles.restrict(stored, "rw-------");
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            copied = 0;
            try (InputStream in = Files.newInputStream(stored, LinkOption.NOFOLLOW_LINKS)) {
              byte[] bytes = new byte[64 * 1024];
              int n;
              while ((n = in.read(bytes)) != -1) {
                if (n > 0) {
                  digest.update(bytes, 0, n);
                  copied += n;
                }
              }
            }
            sha = java.util.HexFormat.of().formatHex(digest.digest());
          } else {
            info.trizub.clamav.webclient.util.PrivateFiles.create(stored);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.security.DigestInputStream(f.getInputStream(), digest);
                var output =
                    Files.newOutputStream(
                        stored, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
              copied = in.transferTo(output);
            }
            sha = java.util.HexFormat.of().formatHex(digest.digest());
          }
          daemonPolicy.requireUpload(copied, endpoint);

          ScanJob job = new ScanJob();
          job.setId(id);
          job.setType(ScanJobType.UPLOAD);
          job.setStatus(ScanJobStatus.QUEUED);
          job.setTarget(upload.label());
          job.setStoredPath(stored.toString());
          job.setSha256(sha);
          job.setSizeBytes(copied);
          job.setEndpoint(endpoint);
          job.setSubmittedBy(username);
          job.setSubmittedTransport(transport);
          job.setSubmittedAt(Instant.now());
          jobs.add(job);
        } catch (Exception e) {
          log.error("Failed to create upload job: {}", e.getMessage(), e);
          throw new IllegalStateException("Unable to create upload scan job", e);
        }
      }
      if (jobs.isEmpty()) {
        batch.succeeded();
        return jobs;
      }
      List<ScanJob> committed = uploadCommitter.commit(jobs, batch);
      batch.succeeded();
      return committed;
    }
  }

  /**
   * Only Tomcat's standard servlet-backed multipart can rename a spooled part. Spring's generic
   * MultipartFile.transferTo(Path) default always copies, hence the File overload and this narrowly
   * scoped check. Other implementations use a one-pass streamed copy and digest to retain their
   * original semantics.
   */
  private static boolean isServletMultipart(MultipartFile file) {
    return file.getClass()
        .getName()
        .equals(
            "org.springframework.web.multipart.support.StandardMultipartHttpServletRequest$StandardMultipartFile");
  }

  @Transactional
  public ScanJob createPathJob(
      String path, ClamdEndpoint endpoint, String username, String transport) {
    requireEnabled(endpoint);
    Path normalized = PathPolicy.normalize(path);
    Path requested = PathPolicy.requireAllowed(normalized, settings.allowedRoots());
    daemonPolicy.requireNotExcluded(normalized, endpoint);
    return pathJob(requested, endpoint, username, transport);
  }

  /** The v1 bearer access check resolved this target; never re-resolve its submitted alias. */
  @Transactional
  public ScanJob createPathJob(
      Path canonical, ClamdEndpoint endpoint, String username, String transport) {
    requireEnabled(endpoint);
    return pathJob(
        PathPolicy.requireCanonicalAllowed(canonical, settings.allowedRoots()),
        endpoint,
        username,
        transport);
  }

  private ScanJob pathJob(
      Path requested, ClamdEndpoint endpoint, String username, String transport) {
    daemonPolicy.requirePath(requested, endpoint);

    String id = UUID.randomUUID().toString().replace("-", "");
    ScanJob job = new ScanJob();
    job.setId(id);
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.QUEUED);
    job.setTarget(requested.toString());
    job.setEndpoint(endpoint);
    job.setSubmittedBy(username);
    job.setSubmittedTransport(transport);
    job.setSubmittedAt(Instant.now());
    repo.save(job);
    enqueueAfterCommit(job.getId());
    return job;
  }

  @Transactional
  public ScanJob createWatchFileJob(Path file, ClamdEndpoint endpoint, String username) {
    requireEnabled(endpoint);
    Path requested = PathPolicy.requireAllowed(file, settings.allowedRoots());
    daemonPolicy.requirePath(requested, endpoint);
    String id = UUID.randomUUID().toString().replace("-", "");
    ScanJob job = new ScanJob();
    job.setId(id);
    job.setType(ScanJobType.WATCH);
    job.setStatus(ScanJobStatus.QUEUED);
    job.setTarget(requested.toString());
    job.setEndpoint(endpoint);
    job.setSubmittedBy(username != null ? username : "watcher");
    job.setSubmittedTransport("WATCH");
    job.setSubmittedAt(Instant.now());
    repo.save(job);
    enqueueAfterCommit(job.getId());
    return job;
  }

  private static void requireEnabled(ClamdEndpoint endpoint) {
    if (endpoint == null || !endpoint.isEnabled()) {
      throw new IllegalArgumentException("Endpoint is disabled");
    }
  }

  private void enqueueAfterCommit(String jobId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.enqueue(jobId);
            }
          });
    } else {
      executor.enqueue(jobId);
    }
  }
}
