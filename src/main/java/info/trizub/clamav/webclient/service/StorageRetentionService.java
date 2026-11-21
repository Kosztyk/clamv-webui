package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class StorageRetentionService {
  private static final Logger log = LoggerFactory.getLogger(StorageRetentionService.class);
  private final SettingsService settings;
  private final UploadStorageService uploads;
  private final ScanJobRepository jobs;
  private final AuditEventRepository audits;
  private final QuarantineArtifactRepository quarantineArtifacts;
  private final Clock clock;

  public StorageRetentionService(
      SettingsService settings,
      UploadStorageService uploads,
      ScanJobRepository jobs,
      AuditEventRepository audits) {
    this(settings, uploads, jobs, audits, null, Clock.systemUTC());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public StorageRetentionService(
      SettingsService settings,
      UploadStorageService uploads,
      ScanJobRepository jobs,
      AuditEventRepository audits,
      QuarantineArtifactRepository quarantineArtifacts) {
    this(settings, uploads, jobs, audits, quarantineArtifacts, Clock.systemUTC());
  }

  StorageRetentionService(
      SettingsService settings,
      UploadStorageService uploads,
      ScanJobRepository jobs,
      AuditEventRepository audits,
      Clock clock) {
    this(settings, uploads, jobs, audits, null, clock);
  }

  StorageRetentionService(
      SettingsService settings,
      UploadStorageService uploads,
      ScanJobRepository jobs,
      AuditEventRepository audits,
      QuarantineArtifactRepository quarantineArtifacts,
      Clock clock) {
    this.settings = settings;
    this.uploads = uploads;
    this.jobs = jobs;
    this.audits = audits;
    this.quarantineArtifacts = quarantineArtifacts;
    this.clock = clock;
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public synchronized void clean() {
    try {
      uploads.pruneExpired();
    } catch (Exception ex) {
      log.warn("Upload retention deferred ({})", ex.getClass().getSimpleName());
    }
    try {
      pruneJobs();
    } catch (Exception ex) {
      log.warn("Job history retention deferred ({})", ex.getClass().getSimpleName());
    }
    try {
      pruneAudits();
    } catch (Exception ex) {
      log.warn("Audit history retention deferred ({})", ex.getClass().getSimpleName());
    }
  }

  private void pruneJobs() {
    if (settings.jobRetentionDays() == 0) return;
    Instant cutoff = clock.instant().minus(Duration.ofDays(settings.jobRetentionDays()));
    Instant afterTime = null;
    String afterId = null;
    long removed = 0;
    var batchSize = PageRequest.of(0, 500);
    while (!Thread.currentThread().isInterrupted()) {
      var batch =
          afterTime == null
              ? jobs.findRetentionFirst(ScanJobStatus.FINISHED, cutoff, batchSize)
              : jobs.findRetentionAfter(
                  ScanJobStatus.FINISHED, cutoff, afterTime, afterId, batchSize);
      if (batch.isEmpty()) break;
      // The first quarantinePath is not the complete evidence set for a
      // directory scan. Never delete a job (and cascade-delete its journal)
      // while *any* recorded quarantine artifact remains on disk.
      List<String> ids =
          batch.stream().filter(this::allArtifactsAbsent).map(ScanJobRetentionRow::id).toList();
      if (!ids.isEmpty()) {
        jobs.deleteAllByIdInBatch(ids);
        removed += ids.size();
      }
      var last = batch.get(batch.size() - 1);
      afterTime = last.finishedAt();
      afterId = last.id();
    }
    if (removed > 0) log.info("Retention pruned {} completed job records", removed);
  }

  private boolean allArtifactsAbsent(ScanJobRetentionRow row) {
    if (!uploads.artifactsAbsent(row.storedPath(), row.quarantinePath())) return false;
    if (quarantineArtifacts == null) return true; // Direct-constructor legacy tests.
    for (QuarantineArtifact artifact : quarantineArtifacts.findByJobIdOrderByIdAsc(row.id())) {
      try {
        if (java.nio.file.Files.exists(
            java.nio.file.Path.of(artifact.getDestinationPath()),
            java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
      } catch (RuntimeException invalidPath) {
        // Do not erase forensic history if its filesystem status is uncertain.
        return false;
      }
    }
    return true;
  }

  private void pruneAudits() {
    if (settings.auditRetentionDays() == 0) return;
    Instant cutoff = clock.instant().minus(Duration.ofDays(settings.auditRetentionDays()));
    Instant afterTime = null;
    long afterId = 0, removed = 0;
    var batchSize = PageRequest.of(0, 500);
    while (!Thread.currentThread().isInterrupted()) {
      var batch =
          afterTime == null
              ? audits.findRetentionFirst(cutoff, batchSize)
              : audits.findRetentionAfter(cutoff, afterTime, afterId, batchSize);
      if (batch.isEmpty()) break;
      audits.deleteAllByIdInBatch(batch.stream().map(AuditRetentionRow::id).toList());
      removed += batch.size();
      var last = batch.get(batch.size() - 1);
      afterTime = last.at();
      afterId = last.id();
    }
    if (removed > 0) log.info("Retention pruned {} audit records", removed);
  }
}
