package info.trizub.clamav.webclient.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.model.ScanJobRetentionRow;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.repo.QuarantineArtifactRepository;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;

/** A scan's first quarantinePath is not proof that its other evidence files are absent. */
class QuarantineRetentionSafetyTest {
  @TempDir Path temp;

  @Test
  void jobHistorySurvivesWhileSecondQuarantineArtifactStillExists() throws Exception {
    var settings = mock(SettingsService.class);
    var uploads = mock(UploadStorageService.class);
    var jobs = mock(ScanJobRepository.class);
    var audits = mock(AuditEventRepository.class);
    var artifacts = mock(QuarantineArtifactRepository.class);
    Instant now = Instant.parse("2026-11-14T12:00:00Z");
    when(settings.jobRetentionDays()).thenReturn(1L);
    Path first = temp.resolve("already-deleted.bin");
    Path second = Files.writeString(temp.resolve("still-quarantined.bin"), "malware");
    String id = "f".repeat(32);
    when(jobs.findRetentionFirst(
            eq(ScanJobStatus.FINISHED), any(Instant.class), any(Pageable.class)))
        .thenReturn(
            List.of(
                new ScanJobRetentionRow(
                    "old-job", now.minus(Duration.ofDays(5)), null, first.toString())));
    when(jobs.findRetentionAfter(
            eq(ScanJobStatus.FINISHED),
            any(Instant.class),
            any(Instant.class),
            anyString(),
            any(Pageable.class)))
        .thenReturn(List.of());
    when(uploads.artifactsAbsent(null, first.toString())).thenReturn(true);
    when(artifacts.findByJobIdOrderByIdAsc("old-job"))
        .thenReturn(
            List.of(
                new QuarantineArtifact(
                    id,
                    "old-job",
                    temp.resolve("source").toString(),
                    second.toString(),
                    "[\"Malware\"]")));
    var service =
        new StorageRetentionService(
            settings, uploads, jobs, audits, artifacts, Clock.fixed(now, ZoneOffset.UTC));
    service.clean();
    verify(jobs, never()).deleteAllByIdInBatch(anyCollection());
    Files.delete(second);
    service.clean();
    verify(jobs).deleteAllByIdInBatch(List.of("old-job"));
  }
}
