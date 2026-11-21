package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Short database transactions; no socket I/O or filesystem moves while holding a DB transaction.
 */
@Service
public class ScanJobFinalizationService {
  private static final Logger log = LoggerFactory.getLogger(ScanJobFinalizationService.class);
  private final ScanJobRepository jobs;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final ScanMetrics metrics;
  private final ProcessedFileRepository processedFiles;

  public ScanJobFinalizationService(
      ScanJobRepository jobs, ObjectMapper mapper, AuditService audit, ScanMetrics metrics) {
    this(jobs, mapper, audit, metrics, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScanJobFinalizationService(
      ScanJobRepository jobs,
      ObjectMapper mapper,
      AuditService audit,
      ScanMetrics metrics,
      ProcessedFileRepository processedFiles) {
    this.jobs = jobs;
    this.mapper = mapper;
    this.audit = audit;
    this.metrics = metrics;
    this.processedFiles = processedFiles;
  }

  @Transactional
  public void finishOk(String id) {
    ScanJob job = unfinished(id);
    if (job == null) return;
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.OK);
    job.setFinishedAt(Instant.now());
    persistAndRecord(job);
  }

  /** Persist the complete detection before any non-transactional filesystem quarantine move. */
  @Transactional
  public void stageDetections(String id, Object foundViruses, String partialWarning) {
    stageDetections(id, foundViruses, partialWarning, null, null);
  }

  @Transactional
  public void stageDetections(
      String id, Object foundViruses, String partialWarning, Boolean scanTargetWasDirectory) {
    stageDetections(id, foundViruses, partialWarning, scanTargetWasDirectory, null);
  }

  @Transactional
  public void stageDetections(
      String id,
      Object foundViruses,
      String partialWarning,
      Boolean scanTargetWasDirectory,
      Boolean quarantineRequested) {
    ScanJob job = unfinished(id);
    if (job == null) return;
    try {
      job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialize malware verdict", e);
    }
    if (partialWarning != null) job.setErrorMessage(partialWarning);
    if (scanTargetWasDirectory != null) job.setScanTargetWasDirectory(scanTargetWasDirectory);
    if (quarantineRequested != null) job.setQuarantineRequested(quarantineRequested);
    jobs.saveAndFlush(job);
  }

  @Transactional
  public void finishFound(
      String id, Object foundViruses, Path quarantinePath, String quarantineWarning) {
    ScanJob job = unfinished(id);
    if (job == null) return;
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    if (quarantinePath != null) job.setQuarantinePath(quarantinePath.toString());
    if (quarantineWarning != null) job.setErrorMessage(quarantineWarning);
    try {
      job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialize positive scan verdict", e);
    }
    job.setFinishedAt(Instant.now());
    persistAndRecord(job);
  }

  @Transactional
  public void finishError(String id, String message) {
    ScanJob job = unfinished(id);
    if (job == null) return;
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.ERROR);
    job.setErrorMessage(message);
    job.setFinishedAt(Instant.now());
    // Persist the WATCH retry clock atomically with the ERROR verdict. Pending
    // and running jobs keep suppressing duplicate scans until they actually fail.
    if (job.getType() == ScanJobType.WATCH && processedFiles != null) {
      processedFiles
          .findByLastScanJobId(id)
          .ifPresent(
              marker -> {
                int failures = Math.min(16, Math.max(0, marker.getRetryFailures()) + 1);
                long delay = Math.min(3600L, 30L << Math.min(failures - 1, 7));
                processedFiles.scheduleRetry(id, Instant.now().plusSeconds(delay), failures);
              });
    }
    persistAndRecord(job);
  }

  /** Update quarantine warning after startup permission repair without rewriting the verdict. */
  @Transactional
  public void reconcileQuarantineWarning(String id, boolean failed) {
    ScanJob job = jobs.findById(id).orElseThrow();
    if (job.getStatus() != ScanJobStatus.FINISHED || job.getVerdict() != ScanVerdict.VIRUS_FOUND)
      throw new IllegalStateException("Cannot reconcile non-final positive scan " + id);
    String marker =
        job.getType() == info.trizub.clamav.webclient.model.ScanJobType.UPLOAD
            ? QuarantineService.UPLOAD_FAILURE
            : QuarantineService.SOURCE_FAILURE;
    String current = job.getErrorMessage();
    java.util.List<String> parts = new java.util.ArrayList<>();
    if (current != null)
      for (String line : current.split("\\R")) {
        if (!line.equals(marker)) parts.add(line);
      }
    if (failed) parts.add(marker);
    String updated = String.join("\n", parts);
    job.setErrorMessage(updated.isEmpty() ? null : updated);
    jobs.saveAndFlush(job);
  }

  @Transactional
  public void setQuarantinePath(String id, String quarantinePath) {
    ScanJob job = jobs.findById(id).orElseThrow();
    job.setQuarantinePath(quarantinePath);
    jobs.save(job);
  }

  private ScanJob unfinished(String id) {
    ScanJob job = jobs.findById(id).orElseThrow();
    return job.getStatus() == ScanJobStatus.FINISHED ? null : job;
  }

  private void persistAndRecord(ScanJob job) {
    jobs.save(job);
    // A rolled-back finalization must not emit a successful-completion metric
    // or audit record. Within a Spring transaction, publish only after commit.
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              record(job);
            }
          });
    } else {
      // Also supports direct constructor-based unit tests without a transaction manager.
      record(job);
    }
  }

  private void record(ScanJob job) {
    try {
      metrics.recordCompletion(job);
    } catch (Exception e) {
      log.error("Unable to update scan completion metrics for job {}", job.getId(), e);
    }
    try {
      audit.recordResult(job);
    } catch (Exception e) {
      log.error("Unable to audit scan result for job {}", job.getId(), e);
    }
  }
}
