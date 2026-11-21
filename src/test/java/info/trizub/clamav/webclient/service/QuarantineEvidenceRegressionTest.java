package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;

/** Failures that previously escaped source-string and syntax checks. */
class QuarantineEvidenceRegressionTest {
  @TempDir Path temp;
  final ObjectMapper mapper = new ObjectMapper();

  final class Fixture {
    final Path root;
    final Path qdir;
    final QuarantineArtifactLedger ledger = mock(QuarantineArtifactLedger.class);
    final ScanJobFinalizationService finalizer = mock(ScanJobFinalizationService.class);
    final ScanJobRepository jobs = mock(ScanJobRepository.class);
    final SettingsService settings = mock(SettingsService.class);
    final Map<String, QuarantineArtifact> records = new LinkedHashMap<>();
    final ScanJob job = new ScanJob();
    final QuarantineArtifactCoordinator coordinator;

    Fixture(Map<String, Collection<String>> detected) throws Exception {
      root = Files.createDirectories(temp.resolve("watched"));
      qdir = Files.createDirectories(temp.resolve("quarantine"));
      when(settings.quarantineEnabled()).thenReturn(true);
      when(settings.allowedRoots()).thenReturn(List.of(root));
      when(settings.quarantineDir()).thenReturn(qdir);
      when(settings.uploadDir()).thenReturn(temp.resolve("uploads"));
      job.setId("scan-job");
      job.setType(ScanJobType.PATH);
      job.setStatus(ScanJobStatus.RUNNING);
      job.setTarget(root.toString());
      job.setScanTargetWasDirectory(true);
      job.setQuarantineRequested(true);
      job.setFoundVirusesJson(mapper.writeValueAsString(detected));
      when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
      doAnswer(
              inv -> {
                List<QuarantineArtifact> batch = inv.getArgument(0);
                batch.forEach(a -> records.put(a.getId(), a));
                return null;
              })
          .when(ledger)
          .reserveBatch(anyList());
      doAnswer(
              inv -> {
                records.get(inv.getArgument(0)).setStatus(inv.getArgument(1));
                return null;
              })
          .when(ledger)
          .transition(anyString(), anyString());
      doAnswer(
              inv -> {
                records.get(inv.getArgument(0)).fail(inv.getArgument(1));
                return null;
              })
          .when(ledger)
          .fail(anyString(), anyString());
      when(ledger.forJob(job.getId())).thenAnswer(inv -> new ArrayList<>(records.values()));
      when(ledger.interruptedJobs())
          .thenAnswer(
              inv ->
                  job.getStatus() == ScanJobStatus.RUNNING && !records.isEmpty()
                      ? List.of(job.getId())
                      : List.of());
      when(jobs.findStagedPositiveJobIds(eq(ScanJobStatus.RUNNING), any(Pageable.class)))
          .thenAnswer(
              inv -> job.getStatus() == ScanJobStatus.RUNNING ? List.of(job.getId()) : List.of());
      when(ledger.outstandingAfter(anyString()))
          .thenAnswer(
              inv ->
                  records.values().stream()
                      .filter(a -> a.getId().compareTo((String) inv.getArgument(0)) > 0)
                      .filter(
                          a ->
                              a.getStatus().equals("HARDEN_FAILED")
                                  || a.getStatus().equals("PREPARED"))
                      .sorted(java.util.Comparator.comparing(QuarantineArtifact::getId))
                      .limit(100)
                      .toList());
      doAnswer(
              inv -> {
                job.setStatus(ScanJobStatus.FINISHED);
                job.setErrorMessage(inv.getArgument(3));
                return null;
              })
          .when(finalizer)
          .finishFound(eq(job.getId()), any(), nullable(Path.class), nullable(String.class));
      coordinator =
          new QuarantineArtifactCoordinator(
              new QuarantineService(settings), ledger, finalizer, jobs, settings, mapper);
    }

    List<QuarantineArtifact> reserve(Map<String, Collection<String>> found) {
      return coordinator.prepareSources(job.getId(), found, root, List.of(root), true);
    }

    QuarantineArtifact forPath(Path p) {
      return records.values().stream()
          .filter(a -> a.getOriginalPath().equals(p.toString()))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  void mixedRejectedSourceAndHardenRepairMustRetainFailure() throws Exception {
    Path good =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("good.bin"), "INFECTED");
    Path outside = Files.writeString(temp.resolve("outside.bin"), "CLEAN");
    Map<String, Collection<String>> detections =
        Map.of(good.toString(), List.of("A"), outside.toString(), List.of("B"));
    Fixture f = new Fixture(detections);
    var prepared = f.reserve(detections);
    assertThat(prepared).hasSize(2);
    assertThat(f.forPath(outside).getStatus()).isEqualTo("FAILED");
    var moved =
        f.coordinator.executePreparedSource(
            f.job.getId(), f.forPath(good), f.root, List.of(f.root), true);
    assertThat(moved.path()).exists();
    assertThat(outside).exists();
    f.forPath(good).setStatus("HARDEN_FAILED");
    f.job.setStatus(ScanJobStatus.FINISHED);
    f.job.setErrorMessage(QuarantineService.SOURCE_FAILURE);
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(f.forPath(good).getStatus()).isEqualTo("MOVED");
    verify(f.finalizer).reconcileQuarantineWarning(f.job.getId(), true);
  }

  @Test
  void changedQuarantineDirectoryFailsOneJournalEntryWithoutBlockingRecovery() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected.bin"), "MALWARE");
    Map<String, Collection<String>> detected = Map.of(source.toString(), List.of("Test-Signature"));
    Fixture f = new Fixture(detected);
    var prepared = f.reserve(detected);
    assertThat(prepared).hasSize(1);
    Path different = Files.createDirectory(temp.resolve("new-quarantine"));
    when(f.settings.quarantineDir()).thenReturn(different);
    f.coordinator.recoverStagedJob(f.job.getId());
    assertThat(source).exists(); // Never move to an unauthorized new location.
    assertThat(prepared.get(0).getStatus()).isEqualTo("FAILED");
    assertThat(prepared.get(0).getFailureCode()).isEqualTo("DESTINATION_CHANGED");
    verify(f.finalizer)
        .finishFound(
            eq(f.job.getId()), any(), isNull(), contains(QuarantineService.SOURCE_FAILURE));
  }

  @Test
  void oldMissingJournalRowAlsoPreservesIndependentFailure() throws Exception {
    Path good =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("good"), "MALWARE");
    Path bad = temp.resolve("watched").resolve("missing");
    Map<String, Collection<String>> detections =
        Map.of(good.toString(), List.of("A"), bad.toString(), List.of("B"));
    Fixture f = new Fixture(detections);
    var reserved = f.reserve(Map.of(good.toString(), List.of("A")));
    QuarantineArtifact a = reserved.get(0);
    var moved =
        f.coordinator.executePreparedSource(f.job.getId(), a, f.root, List.of(f.root), true);
    assertThat(moved.failed()).isFalse();
    a.setStatus("HARDEN_FAILED");
    f.job.setStatus(ScanJobStatus.FINISHED);
    f.job.setErrorMessage(QuarantineService.SOURCE_FAILURE);
    f.coordinator.recoverInterruptedQuarantine();
    verify(f.finalizer).reconcileQuarantineWarning(f.job.getId(), true);
  }

  @Test
  void replacementAfterReservationIsNotQuarantinedOnRestart() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected.bin"), "INFECTED");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("Malware"));
    Fixture f = new Fixture(detections);
    var prepared = f.reserve(detections);
    assertThat(prepared.get(0).getSourceSha256()).hasSize(64);
    Files.delete(source);
    Files.writeString(source, "CLEAN-REPLACEMENT");
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(Files.readString(source)).isEqualTo("CLEAN-REPLACEMENT");
    assertThat(prepared.get(0).getStatus()).isEqualTo("FAILED");
    assertThat(prepared.get(0).getFailureCode()).isEqualTo("IDENTITY_MISMATCH");
    assertThat(Files.exists(Path.of(prepared.get(0).getDestinationPath()))).isFalse();
    assertThat(f.job.getErrorMessage()).contains(QuarantineService.SOURCE_FAILURE);
    verify(f.finalizer, times(1))
        .finishFound(
            eq(f.job.getId()),
            any(),
            isNull(),
            argThat(v -> v != null && v.contains(QuarantineService.SOURCE_FAILURE)));
  }

  @Test
  void sameSizeAndTimestampReplacementStillFailsHash() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "MALWARE1");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("Malware"));
    Fixture f = new Fixture(detections);
    var prepared = f.reserve(detections);
    FileTime timestamp = Files.getLastModifiedTime(source);
    Files.delete(source);
    Files.writeString(source, "CLEAN123");
    Files.setLastModifiedTime(source, timestamp);
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(source).exists();
    assertThat(Files.readString(source)).isEqualTo("CLEAN123");
    assertThat(prepared.get(0).getStatus()).isEqualTo("FAILED");
  }

  @Test
  void replacementBeforeNormalMoveFailsClosed() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "MALWARE");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("Malware"));
    Fixture f = new Fixture(detections);
    var prepared = f.reserve(detections);
    Files.writeString(source, "CLEAN");
    var result =
        f.coordinator.executePreparedSource(
            f.job.getId(), prepared.get(0), f.root, List.of(f.root), true);
    assertThat(result.failed()).isTrue();
    assertThat(source).exists();
    assertThat(prepared.get(0).getStatus()).isEqualTo("FAILED");
  }

  @Test
  void crashBeforeBatchReservationNeverMovesAnyDetectedFile() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "OLD-INFECTED");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("Malware"));
    Fixture f = new Fixture(detections);
    Files.writeString(source, "NEW-REPLACEMENT");
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(source).exists();
    assertThat(f.records).isEmpty();
    assertThat(f.job.getErrorMessage()).contains(QuarantineService.SOURCE_FAILURE);
  }

  @Test
  void batchReservationFailureMovesNothing() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "MALWARE");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("Malware"));
    Fixture f = new Fixture(detections);
    doThrow(new IllegalStateException("DB down")).when(f.ledger).reserveBatch(anyList());
    assertThatThrownBy(() -> f.reserve(detections))
        .isInstanceOf(QuarantineArtifactCoordinator.JournalCommitFailure.class);
    assertThat(source).exists();
    assertThat(f.records).isEmpty();
  }

  @Test
  void twoPreparedFilesRecoverAfterFirstMove() throws Exception {
    Path root = Files.createDirectories(temp.resolve("watched"));
    Path a = Files.writeString(root.resolve("a"), "INFECTED-A");
    Path b = Files.writeString(root.resolve("b"), "INFECTED-B");
    Map<String, Collection<String>> detections =
        Map.of(a.toString(), List.of("A"), b.toString(), List.of("B"));
    Fixture f = new Fixture(detections);
    f.reserve(detections);
    var first = f.forPath(a);
    Files.move(a, Path.of(first.getDestinationPath()));
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(f.job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(b).doesNotExist();
    assertThat(f.records.values()).allMatch(v -> v.getStatus().equals("MOVED"));
    verify(f.finalizer, times(1))
        .finishFound(eq(f.job.getId()), any(), nullable(Path.class), isNull());
  }

  @Test
  void destinationTamperingDoesNotBecomeSuccessfulJournal() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "INFECTED");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("A"));
    Fixture f = new Fixture(detections);
    var prepared = f.reserve(detections);
    Files.writeString(Path.of(prepared.get(0).getDestinationPath()), "UNRELATED-CONTENT");
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(source).exists();
    assertThat(prepared.get(0).getStatus()).isEqualTo("FAILED");
    assertThat(f.job.getErrorMessage()).contains(QuarantineService.SOURCE_FAILURE);
  }

  @Test
  void explicitlyDisabledQuarantineDoesNotCreateFalseFailure() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "INFECTED");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("A"));
    Fixture f = new Fixture(detections);
    f.job.setQuarantineRequested(false);
    when(f.settings.quarantineEnabled()).thenReturn(false);
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(source).exists();
    verify(f.finalizer).finishFound(eq(f.job.getId()), any(), isNull(), isNull());
  }

  @Test
  void malformedDaemonPathIsJournaledAsFailedWithoutMovingOtherFiles() throws Exception {
    Path good =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("good"), "MALWARE");
    String malformed = "bad\u0000path";
    Map<String, Collection<String>> detections =
        Map.of(good.toString(), List.of("A"), malformed, List.of("B"));
    Fixture f = new Fixture(detections);
    var planned = f.reserve(detections);
    assertThat(planned).hasSize(2);
    assertThat(planned)
        .anyMatch(
            a -> "FAILED".equals(a.getStatus()) && "REJECTED_PATH".equals(a.getFailureCode()));
    QuarantineArtifact valid = f.forPath(good);
    var result =
        f.coordinator.executePreparedSource(f.job.getId(), valid, f.root, List.of(f.root), true);
    assertThat(result.failed()).isFalse();
    assertThat(good).doesNotExist();
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(f.job.getErrorMessage()).contains(QuarantineService.SOURCE_FAILURE);
  }

  @Test
  void scanTimeDisabledQuarantineStaysDisabledAfterConfigChange() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "INFECTED");
    Map<String, Collection<String>> detections = Map.of(source.toString(), List.of("A"));
    Fixture f = new Fixture(detections);
    f.job.setQuarantineRequested(false); // startup configuration is now enabled
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(source).exists();
    verify(f.finalizer).finishFound(eq(f.job.getId()), any(), isNull(), isNull());
  }

  @Test
  void legacyPreparedDestinationWithoutIdentityCannotEraseWarning() throws Exception {
    Path source =
        Files.writeString(
            Files.createDirectories(temp.resolve("watched")).resolve("infected"), "MALWARE");
    var detections = Map.<String, Collection<String>>of(source.toString(), List.of("A"));
    Fixture f = new Fixture(detections);
    String id = java.util.UUID.randomUUID().toString().replace("-", "");
    QuarantineArtifact legacy =
        new QuarantineArtifact(
            id, f.job.getId(), source.toString(), f.qdir.resolve(id + ".bin").toString(), "[]");
    f.records.put(id, legacy);
    Files.move(source, Path.of(legacy.getDestinationPath()));
    f.job.setStatus(ScanJobStatus.FINISHED);
    f.job.setErrorMessage(QuarantineService.SOURCE_FAILURE);
    f.coordinator.recoverInterruptedQuarantine();
    assertThat(legacy.getStatus()).isEqualTo("FAILED");
    assertThat(legacy.getFailureCode()).isEqualTo("MISSING_EVIDENCE");
    verify(f.finalizer).reconcileQuarantineWarning(f.job.getId(), true);
    assertThat(Path.of(legacy.getDestinationPath())).exists();
  }

  @Test
  void journaledUploadWithoutContentEvidenceCannotMoveFile() throws Exception {
    Path uploaded = Files.writeString(temp.resolve("uploaded.bin"), "TEST-CONTENT");
    SettingsService settings = mock(SettingsService.class);
    String id = "a".repeat(32);
    Path destination = temp.resolve("quarantine").resolve(id + ".bin");
    assertThatThrownBy(
            () ->
                new QuarantineService(settings)
                    .attemptUploadWithArtifactId(uploaded, id, destination))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires content evidence");
    assertThat(uploaded).exists();
    assertThat(destination).doesNotExist();
  }
}
