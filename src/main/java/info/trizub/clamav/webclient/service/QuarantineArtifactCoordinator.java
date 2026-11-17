package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PrivateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Every infected file is accounted for before a staged positive job is finalized. A committed
 * PREPARED row precedes a move; an uncommitted journal transition must leave the scan RUNNING for
 * startup recovery, never FINISHED.
 */
@Service
public class QuarantineArtifactCoordinator {
  private static final Logger log = LoggerFactory.getLogger(QuarantineArtifactCoordinator.class);

  public static final class JournalCommitFailure extends IllegalStateException {
    public JournalCommitFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final QuarantineService quarantine;
  private final QuarantineArtifactLedger ledger;
  private final ScanJobFinalizationService finalizer;
  private final ScanJobRepository jobs;
  private final SettingsService settings;
  private final ObjectMapper mapper;
  private NotificationService notifications;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setNotifications(NotificationService notifications) {
    this.notifications = notifications;
  }

  public QuarantineArtifactCoordinator(
      QuarantineService quarantine,
      QuarantineArtifactLedger ledger,
      ScanJobFinalizationService finalizer,
      ScanJobRepository jobs,
      SettingsService settings,
      ObjectMapper mapper) {
    this.quarantine = quarantine;
    this.ledger = ledger;
    this.finalizer = finalizer;
    this.jobs = jobs;
    this.settings = settings;
    this.mapper = mapper;
  }

  /**
   * Reserve the ENTIRE source detection set in one committed transaction before moving the first
   * file. A rejected/vanished/invalid path is also represented by a FAILED row, so later permission
   * repair cannot erase its failure.
   *
   * <p>If the process dies between staging and this reservation, recovery must explicitly fail
   * unmatched detections. It must NOT identify them by path anew.
   */
  public List<QuarantineArtifact> prepareSources(
      String jobId,
      Map<String, ? extends Collection<String>> found,
      Path original,
      List<Path> roots,
      boolean wasDirectory) {
    if (!settings.quarantineEnabled() || found == null || found.isEmpty()) return List.of();
    List<QuarantineArtifact> prepared = new ArrayList<>(found.size());
    for (var entry : found.entrySet()) {
      String reported = entry.getKey();
      String id = UUID.randomUUID().toString().replace("-", "");
      Path destination = settings.quarantineDir().resolve(id + ".bin");
      Path candidate = null;
      String failure = null;
      try {
        candidate = Path.of(reported);
        quarantine.authorizeSource(candidate, original, roots, wasDirectory);
      } catch (RuntimeException rejected) {
        failure = "REJECTED_PATH";
      }
      // Some invalid paths contain NUL, which cannot be stored as PostgreSQL
      // TEXT. Preserve their attempted outcome with a safe journal key.
      String key = candidate == null ? "invalid-detection-" + id : candidate.toString();
      QuarantineArtifact artifact;
      try {
        artifact =
            new QuarantineArtifact(
                id,
                jobId,
                key,
                destination.toString(),
                mapper.writeValueAsString(entry.getValue() == null ? List.of() : entry.getValue()));
        if (failure == null) {
          try {
            QuarantineFileIdentity.Evidence evidence = QuarantineFileIdentity.capture(candidate);
            quarantine.authorizeSource(candidate, original, roots, wasDirectory);
            artifact.setSourceEvidence(evidence.sizeBytes(), evidence.sha256());
          } catch (Exception ex) {
            failure = "IDENTITY_UNAVAILABLE";
          }
        }
        if (failure != null) artifact.fail(failure);
      } catch (Exception ex) {
        throw new JournalCommitFailure("Cannot prepare quarantine detection for " + jobId, ex);
      }
      prepared.add(artifact);
    }
    try {
      ledger.reserveBatch(prepared);
    } catch (RuntimeException ex) {
      // Atomic reservation failed. No file was moved; leave RUNNING staged
      // evidence for safe recovery that explicitly reports unreserved files.
      throw new JournalCommitFailure("Source quarantine batch reservation failed for " + jobId, ex);
    }
    return prepared;
  }

  public QuarantineService.Result executePreparedSource(
      String jobId,
      QuarantineArtifact artifact,
      Path original,
      List<Path> roots,
      boolean wasDirectory) {
    if ("FAILED".equals(artifact.getStatus())) return new QuarantineService.Result(null, true);
    try {
      return reconcile(artifact, original, roots, wasDirectory, jobId, false);
    } catch (JournalCommitFailure ex) {
      throw ex;
    } catch (RuntimeException ex) {
      // An uncertain move must never become a finalized job.
      throw new JournalCommitFailure("Source quarantine outcome uncertain for " + jobId, ex);
    }
  }

  /** Compatible single-file entry point for callers and direct unit tests. */
  public QuarantineService.Result attempt(
      String jobId,
      Path candidate,
      Path original,
      List<Path> roots,
      boolean wasDirectory,
      Collection<String> signatures) {
    if (!settings.quarantineEnabled()) return new QuarantineService.Result(null, false);
    if (candidate == null) return new QuarantineService.Result(null, true);
    List<QuarantineArtifact> prepared =
        prepareSources(
            jobId,
            Map.of(candidate.toString(), signatures == null ? List.of() : signatures),
            original,
            roots,
            wasDirectory);
    return executePreparedSource(jobId, prepared.get(0), original, roots, wasDirectory);
  }

  public QuarantineService.Result attemptUpload(
      String jobId, Path uploaded, Collection<String> signatures) {
    if (!settings.quarantineEnabled()) return new QuarantineService.Result(null, false);
    if (jobId == null || !jobId.matches("[0-9a-f]{32}"))
      return new QuarantineService.Result(null, true);
    Path destination = settings.quarantineDir().resolve(jobId + ".bin");
    QuarantineArtifact artifact;
    try {
      artifact =
          new QuarantineArtifact(
              jobId,
              jobId,
              uploaded.toString(),
              destination.toString(),
              mapper.writeValueAsString(signatures == null ? List.of() : signatures));
      QuarantineFileIdentity.Evidence evidence = null;
      try {
        // Only a committed journal can authenticate an already-moved destination.
        evidence = QuarantineFileIdentity.capture(uploaded);
        // Uploaded bytes were hashed at ingestion. A replacement after
        // scanning must not become the quarantined artifact.
        ScanJob scan = jobs.findById(jobId).orElse(null);
        if (scan != null
            && scan.getSha256() != null
            && !scan.getSha256().equalsIgnoreCase(evidence.sha256()))
          throw new IllegalArgumentException("Upload content no longer matches original upload");
        artifact.setSourceEvidence(evidence.sizeBytes(), evidence.sha256());
      } catch (Exception ex) {
        artifact.fail("IDENTITY_UNAVAILABLE");
      }
      ledger.reserve(artifact);
    } catch (Exception ex) {
      log.error(
          "Upload quarantine reservation failed for job {}; upload will not be moved", jobId, ex);
      throw new JournalCommitFailure("Upload quarantine reservation failed for " + jobId, ex);
    }
    if ("FAILED".equals(artifact.getStatus())) return new QuarantineService.Result(null, true);
    try {
      QuarantineFileIdentity.Evidence evidence = evidenceOf(artifact);
      if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
        if (!matches(destination, evidence)) {
          markFailed(artifact, "IDENTITY_MISMATCH", jobId);
          return new QuarantineService.Result(null, true);
        }
        boolean secure = applyHardening(artifact, jobId);
        // Two live copies for one upload job are anomalous.
        return new QuarantineService.Result(
            destination, !secure || Files.exists(uploaded, LinkOption.NOFOLLOW_LINKS));
      }
      QuarantineService.Result result =
          quarantine.attemptUploadWithArtifactId(uploaded, jobId, destination, evidence);
      return persistOutcome(artifact, result, jobId);
    } catch (JournalCommitFailure ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new JournalCommitFailure("Upload quarantine outcome uncertain for " + jobId, ex);
    }
  }

  private QuarantineService.Result persistOutcome(
      QuarantineArtifact artifact, QuarantineService.Result result, String jobId) {
    // Disabling quarantine between reservation and move is an explicit failed
    // operation; it must never become a silent successful quarantine.
    if (result.path() == null && !result.failed())
      result = new QuarantineService.Result(null, true);
    String status = result.path() == null ? "FAILED" : result.failed() ? "HARDEN_FAILED" : "MOVED";
    try {
      if ("FAILED".equals(status)) ledger.fail(artifact.getId(), "MOVE_FAILED");
      else ledger.transition(artifact.getId(), status);
    } catch (RuntimeException ex) {
      // A move may have completed. Only startup reconciliation may decide its
      // final state; the worker must not mark the scan FINISHED.
      throw new JournalCommitFailure("Quarantine journal update failed for job " + jobId, ex);
    }
    return result;
  }

  /** Reconcile before the ordinary RUNNING -> QUEUED scan replay. */
  public void recoverInterruptedQuarantine() {
    while (true) {
      List<String> ids = ledger.interruptedJobs();
      if (ids.isEmpty()) break;
      for (String id : ids) recoverOne(id);
    }
    while (true) {
      List<String> ids =
          jobs.findStagedPositiveJobIds(ScanJobStatus.RUNNING, PageRequest.of(0, 100));
      if (ids.isEmpty()) break;
      for (String id : ids) recoverOne(id);
    }
    repairOutstandingArtifacts();
  }

  /**
   * Live reconciliation is deliberately per job; the caller owns scheduling, backoff and the
   * exclusion of IDs still held by a scan worker.
   */
  public void recoverStagedJob(String jobId) {
    recoverOne(jobId);
  }

  /**
   * Keyset scan covers PREPARED and HARDEN_FAILED rows in *finished* jobs too. It never replays a
   * file move for an already finished scan.
   */
  private void repairOutstandingArtifacts() {
    String after = "";
    while (true) {
      List<QuarantineArtifact> page = ledger.outstandingAfter(after);
      if (page.isEmpty()) return;
      java.util.Set<String> affectedJobs = new java.util.HashSet<>();
      for (QuarantineArtifact artifact : page) {
        after = artifact.getId();
        ScanJob job = jobs.findById(artifact.getJobId()).orElseThrow();
        if (job.getStatus() != ScanJobStatus.FINISHED)
          throw new IllegalStateException(
              "Unreconciled quarantine for unfinished job " + job.getId());
        QuarantineFileIdentity.Evidence evidence = evidenceOf(artifact);
        Path destination = recoveryDestination(artifact, job.getId());
        if (destination == null) {
          // The old quarantine location may still contain evidence.
          // Never relocate or delete it solely because settings changed.
        } else if (evidence == null) markFailed(artifact, "MISSING_EVIDENCE", job.getId());
        else if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
          markFailed(artifact, "DESTINATION_MISSING", job.getId());
        else if (!matches(destination, evidence))
          markFailed(artifact, "IDENTITY_MISMATCH", job.getId());
        else applyHardening(artifact, job.getId());
        affectedJobs.add(job.getId());
      }
      for (String jobId : affectedJobs) {
        ScanJob job = jobs.findById(jobId).orElseThrow();
        finalizer.reconcileQuarantineWarning(jobId, hasUnresolvedDetections(job));
      }
    }
  }

  private void recoverOne(String jobId) {
    ScanJob job = jobs.findById(jobId).orElseThrow();
    if (job.getStatus() != ScanJobStatus.RUNNING) return;
    Map<String, Collection<String>> found = parseDetections(job);
    Map<String, QuarantineArtifact> bySource = new HashMap<>();
    for (QuarantineArtifact artifact : ledger.forJob(jobId)) {
      if (bySource.putIfAbsent(artifact.getOriginalPath(), artifact) != null)
        throw new IllegalStateException("Duplicate quarantine journal source for job " + jobId);
    }
    Path firstMoved = null;
    String warning = job.getErrorMessage();
    boolean failed = false;
    if (job.getType() == ScanJobType.UPLOAD) {
      Path original = Path.of(job.getStoredPath()).toAbsolutePath().normalize();
      QuarantineArtifact saved = bySource.remove(original.toString());
      QuarantineService.Result result;
      if (saved != null) {
        result = reconcile(saved, original, null, false, jobId, true);
      } else if (Boolean.FALSE.equals(job.getQuarantineRequested())) {
        result = new QuarantineService.Result(null, false);
      } else if (settings.quarantineEnabled()) {
        // Staging is not source identity evidence. A replacement upload
        // must not be moved or trusted after an interrupted reservation.
        result = new QuarantineService.Result(null, true);
      } else {
        result = new QuarantineService.Result(null, false);
      }
      firstMoved = result.path();
      failed = result.failed();
    } else if (job.getType() == ScanJobType.PATH || job.getType() == ScanJobType.WATCH) {
      Path original = Path.of(job.getTarget());
      Boolean directory = job.getScanTargetWasDirectory();
      for (var entry : found.entrySet()) {
        Path candidate;
        try {
          candidate = Path.of(entry.getKey());
        } catch (RuntimeException ex) {
          failed = true;
          continue;
        }
        QuarantineArtifact saved = bySource.remove(candidate.toString());
        QuarantineService.Result result;
        if (saved != null) {
          result =
              reconcile(
                  saved,
                  original,
                  settings.allowedRoots(),
                  Boolean.TRUE.equals(directory),
                  jobId,
                  false);
        } else if (Boolean.FALSE.equals(job.getQuarantineRequested())
            || !settings.quarantineEnabled()) {
          result = new QuarantineService.Result(null, false);
        } else if (directory == null) {
          // Legacy staged scans have no persisted target type: never
          // infer directory access from an altered post-crash filesystem.
          result = new QuarantineService.Result(null, true);
        } else {
          // A crash before batch reservation leaves no identity evidence.
          // Do not fingerprint an arbitrary replacement on restart.
          result = new QuarantineService.Result(null, true);
        }
        if (firstMoved == null) firstMoved = result.path();
        failed |= result.failed();
      }
    } else {
      throw new IllegalStateException("Unexpected staged scan type " + job.getType());
    }
    if (!bySource.isEmpty()) {
      // Every journal entry is reconciled, even if it is not represented in
      // a damaged or old staged report. Never silently discard evidence.
      failed = true;
      for (QuarantineArtifact extra : bySource.values()) {
        // An invalid daemon path is deliberately stored under a safe
        // synthetic key. Do not overwrite its original rejection reason.
        if ("FAILED".equals(extra.getStatus())) continue;
        Path destination = recoveryDestination(extra, jobId);
        if (destination == null) continue;
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
          applyHardening(extra, jobId);
        else markFailed(extra, "DESTINATION_MISSING", jobId);
      }
    }
    // A successful finalization cannot leave an unresolved journal commit.
    if (ledger.forJob(jobId).stream().anyMatch(a -> "PREPARED".equals(a.getStatus())))
      throw new IllegalStateException("Quarantine journal still PREPARED for job " + jobId);
    // Failures such as invalid daemon paths must survive every repair cycle.
    failed |= hasUnresolvedDetections(job);
    if (failed)
      warning = DetectionReportLimiter.joinWarnings(warning, failureMarker(job.getType()));
    finalizer.finishFound(jobId, found, firstMoved, warning);
    notifyRecovered(jobId);
    log.warn("Recovered interrupted infected scan {} and reconciled all staged detections", jobId);
  }

  private QuarantineService.Result reconcile(
      QuarantineArtifact artifact,
      Path scanTarget,
      List<Path> allowedRoots,
      boolean directory,
      String jobId,
      boolean upload) {
    if ("FAILED".equals(artifact.getStatus())) return new QuarantineService.Result(null, true);
    Path destination = recoveryDestination(artifact, jobId);
    if (destination == null) return new QuarantineService.Result(null, true);
    QuarantineFileIdentity.Evidence evidence = evidenceOf(artifact);
    if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
      // A preexisting destination must also match the journal. Never attest
      // an arbitrary replacement file as successfully quarantined evidence.
      // Legacy entries without content evidence cannot attest a destination
      // merely because a file occupies the recorded pathname.
      if (evidence == null || !matches(destination, evidence)) {
        markFailed(artifact, evidence == null ? "MISSING_EVIDENCE" : "IDENTITY_MISMATCH", jobId);
        log.error("Quarantine destination identity unverifiable for job {}", jobId);
        return new QuarantineService.Result(null, true);
      }
      boolean secure = applyHardening(artifact, jobId);
      return new QuarantineService.Result(destination, !secure);
    }
    // A MOVED row with a vanished destination must not move a possibly
    // replaced source into quarantine again.
    if ("MOVED".equals(artifact.getStatus()) || evidence == null) {
      markFailed(artifact, evidence == null ? "MISSING_EVIDENCE" : "DESTINATION_MISSING", jobId);
      return new QuarantineService.Result(null, true);
    }
    Path source = Path.of(artifact.getOriginalPath());
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !settings.quarantineEnabled()) {
      markFailed(artifact, "SOURCE_MISSING", jobId);
      return new QuarantineService.Result(null, true);
    }
    if (!matches(source, evidence)) {
      markFailed(artifact, "IDENTITY_MISMATCH", jobId);
      log.warn("Quarantine source identity changed since detection for job {}", jobId);
      return new QuarantineService.Result(null, true);
    }
    QuarantineService.Result result =
        upload
            ? quarantine.attemptUploadWithArtifactId(
                source, artifact.getId(), destination, evidence)
            : quarantine.attemptSourceWithArtifactId(
                source,
                scanTarget,
                allowedRoots,
                directory,
                artifact.getId(),
                destination,
                evidence);
    return persistOutcome(artifact, result, jobId);
  }

  private static QuarantineFileIdentity.Evidence evidenceOf(QuarantineArtifact artifact) {
    if (artifact.getSourceSha256() == null || artifact.getSourceSizeBytes() == null) return null;
    return new QuarantineFileIdentity.Evidence(
        artifact.getSourceSizeBytes(), artifact.getSourceSha256());
  }

  private static boolean matches(Path path, QuarantineFileIdentity.Evidence evidence) {
    try {
      return QuarantineFileIdentity.matches(path, evidence);
    } catch (IOException | RuntimeException ex) {
      return false;
    }
  }

  /** Aggregate over every staged detection, never only the successful journal rows. */
  private boolean hasUnresolvedDetections(ScanJob job) {
    Map<String, Collection<String>> found = parseDetections(job);
    List<QuarantineArtifact> all = ledger.forJob(job.getId());
    if (Boolean.FALSE.equals(job.getQuarantineRequested()) && all.isEmpty())
      return QuarantineService.failureRecorded(job.getErrorMessage());
    if (all.stream().anyMatch(a -> !"MOVED".equals(a.getStatus()))) return true;
    if (job.getType() == ScanJobType.UPLOAD) {
      if (all.size() != 1 || job.getStoredPath() == null) return true;
      return !Path.of(all.get(0).getOriginalPath())
          .toAbsolutePath()
          .normalize()
          .equals(Path.of(job.getStoredPath()).toAbsolutePath().normalize());
    }
    if (job.getType() == ScanJobType.PATH || job.getType() == ScanJobType.WATCH) {
      if (all.size() != found.size()) return true;
      java.util.Set<String> paths = new java.util.HashSet<>();
      for (QuarantineArtifact a : all) if (!paths.add(a.getOriginalPath())) return true;
      return !paths.equals(found.keySet());
    }
    return true;
  }

  private Path checkedDestination(QuarantineArtifact artifact) {
    Path expected =
        settings.quarantineDir().toAbsolutePath().normalize().resolve(artifact.getId() + ".bin");
    Path recorded = Path.of(artifact.getDestinationPath()).toAbsolutePath().normalize();
    if (!artifact.getId().matches("[0-9a-f]{32}") || !recorded.equals(expected))
      throw new IllegalStateException(
          "Quarantine journal destination changed for " + artifact.getId());
    return recorded;
  }

  /**
   * A changed quarantine directory is an individual unresolved operation, not a reason to abort the
   * entire Spring application on restart.
   */
  private Path recoveryDestination(QuarantineArtifact artifact, String jobId) {
    try {
      return checkedDestination(artifact);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      markFailed(artifact, "DESTINATION_CHANGED", jobId);
      log.warn(
          "Quarantine destination no longer matches the configured directory for job {}", jobId);
      return null;
    }
  }

  private boolean applyHardening(QuarantineArtifact artifact, String jobId) {
    Path destination = checkedDestination(artifact);
    try {
      PrivateFiles.restrict(destination, "rw-------");
      transition(artifact, "MOVED", jobId);
      return true;
    } catch (IOException ex) {
      transition(artifact, "HARDEN_FAILED", jobId);
      log.warn("Quarantine artifact permissions could not be hardened for job {}", jobId);
      return false;
    }
  }

  private void markFailed(QuarantineArtifact artifact, String reason, String jobId) {
    try {
      ledger.fail(artifact.getId(), reason);
    } catch (RuntimeException ex) {
      throw new JournalCommitFailure("Quarantine failure record could not commit for " + jobId, ex);
    }
  }

  private void transition(QuarantineArtifact artifact, String state, String jobId) {
    try {
      ledger.transition(artifact.getId(), state);
    } catch (RuntimeException ex) {
      throw new JournalCommitFailure("Quarantine reconciliation DB update failed for " + jobId, ex);
    }
  }

  private Map<String, Collection<String>> parseDetections(ScanJob job) {
    if (job.getFoundVirusesJson() == null)
      throw new IllegalStateException(
          "Positive scan has no durable detection report: " + job.getId());
    try {
      Map<String, Collection<String>> parsed =
          mapper.readValue(
              job.getFoundVirusesJson(), new TypeReference<Map<String, Collection<String>>>() {});
      if (parsed == null) throw new IllegalStateException("Null detection report");
      return new LinkedHashMap<>(parsed);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot recover staged malware verdict " + job.getId(), ex);
    }
  }

  private static String failureMarker(ScanJobType type) {
    return type == ScanJobType.UPLOAD
        ? QuarantineService.UPLOAD_FAILURE
        : QuarantineService.SOURCE_FAILURE;
  }

  private void notifyRecovered(String jobId) {
    if (notifications == null) return;
    try {
      notifications.notifyIfNeeded(jobs.findById(jobId).orElseThrow());
    } catch (Exception ex) {
      log.warn("Recovered scan notification could not be scheduled for {}", jobId);
    }
  }
}
