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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;

/** Crash windows around reservation, filesystem move, journal commit and finalization. */
class QuarantineRecoveryCompletenessTest {
  @TempDir Path temp;
  final QuarantineArtifactLedger ledger = mock(QuarantineArtifactLedger.class);
  final ScanJobFinalizationService finalizer = mock(ScanJobFinalizationService.class);
  final ScanJobRepository jobs = mock(ScanJobRepository.class);
  final SettingsService settings = mock(SettingsService.class);
  final ObjectMapper mapper = new ObjectMapper();
  final Map<String, QuarantineArtifact> artifacts = new LinkedHashMap<>();

  QuarantineArtifactCoordinator coordinator(ScanJob job) throws Exception {
    when(settings.quarantineEnabled()).thenReturn(true);
    when(settings.quarantineDir()).thenReturn(temp.resolve("quarantine"));
    when(settings.uploadDir()).thenReturn(temp.resolve("uploads"));
    when(settings.allowedRoots()).thenReturn(List.of(temp.resolve("watched")));
    when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
    doAnswer(
            inv -> {
              List<QuarantineArtifact> batch = inv.getArgument(0);
              batch.forEach(a -> artifacts.put(a.getId(), a));
              return null;
            })
        .when(ledger)
        .reserveBatch(anyList());
    doAnswer(
            inv -> {
              QuarantineArtifact a = inv.getArgument(0);
              artifacts.put(a.getId(), a);
              return null;
            })
        .when(ledger)
        .reserve(any(QuarantineArtifact.class));
    doAnswer(
            inv -> {
              String id = inv.getArgument(0);
              String status = inv.getArgument(1);
              artifacts.get(id).setStatus(status);
              return null;
            })
        .when(ledger)
        .transition(anyString(), anyString());
    doAnswer(
            inv -> {
              artifacts.get(inv.getArgument(0)).fail(inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .fail(anyString(), anyString());
    when(ledger.forJob(job.getId())).thenAnswer(inv -> new ArrayList<>(artifacts.values()));
    when(ledger.interruptedJobs())
        .thenAnswer(
            inv ->
                job.getStatus() == ScanJobStatus.RUNNING && !artifacts.isEmpty()
                    ? List.of(job.getId())
                    : List.of());
    when(jobs.findStagedPositiveJobIds(eq(ScanJobStatus.RUNNING), any(Pageable.class)))
        .thenAnswer(
            inv -> job.getStatus() == ScanJobStatus.RUNNING ? List.of(job.getId()) : List.of());
    when(ledger.outstandingAfter(anyString())).thenReturn(List.of());
    doAnswer(
            inv -> {
              job.setStatus(ScanJobStatus.FINISHED);
              job.setErrorMessage(inv.getArgument(3));
              return null;
            })
        .when(finalizer)
        .finishFound(eq(job.getId()), any(), nullable(Path.class), nullable(String.class));
    return new QuarantineArtifactCoordinator(
        new QuarantineService(settings), ledger, finalizer, jobs, settings, mapper);
  }

  ScanJob job(ScanJobType type, Path target, Map<String, Collection<String>> detection)
      throws Exception {
    ScanJob job = new ScanJob();
    job.setId(type == ScanJobType.UPLOAD ? "a".repeat(32) : "source-job");
    job.setType(type);
    job.setStatus(ScanJobStatus.RUNNING);
    job.setTarget(target.toString());
    job.setStoredPath(target.toString());
    job.setScanTargetWasDirectory(type == ScanJobType.PATH);
    job.setFoundVirusesJson(mapper.writeValueAsString(detection));
    return job;
  }

  @Test
  void crashAfterFirstMoveStillQuarantinesSecondAndOnlyThenFinalizes() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    Path first = Files.writeString(source.resolve("first.bin"), "malware-first");
    Path second = Files.writeString(source.resolve("second.bin"), "malware-second");
    Path qdir = Files.createDirectories(temp.resolve("quarantine"));
    // The first journal destination is created after batch reservation.
    Map<String, Collection<String>> detected =
        Map.of(first.toString(), List.of("Malware.A"), second.toString(), List.of("Malware.B"));
    ScanJob job = job(ScanJobType.PATH, source, detected);
    var coordinator = coordinator(job);
    // A safe crash recovery requires BOTH files to have been reserved with
    // durable evidence before the first move (the new batch invariant).
    var pending = coordinator.prepareSources(job.getId(), detected, source, List.of(source), true);
    assertThat(pending).hasSize(2);
    QuarantineArtifact firstJournal =
        artifacts.values().stream()
            .filter(a -> a.getOriginalPath().equals(first.toString()))
            .findFirst()
            .orElseThrow();
    // The first file has already moved before the simulated crash.
    Files.move(first, Path.of(firstJournal.getDestinationPath()));
    coordinator.recoverInterruptedQuarantine();
    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(second).doesNotExist();
    assertThat(artifacts).hasSize(2);
    assertThat(artifacts.values()).allMatch(a -> a.getStatus().equals("MOVED"));
    try (var paths = Files.list(qdir)) {
      assertThat(paths.count()).isEqualTo(2);
    }
    verify(finalizer, times(1))
        .finishFound(eq(job.getId()), any(), nullable(Path.class), nullable(String.class));
  }

  @Test
  void crashAfterStagingBeforeReservationDoesNotMoveUnverifiedFiles() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    Path a = Files.writeString(source.resolve("a.bin"), "A");
    Path b = Files.writeString(source.resolve("b.bin"), "B");
    ScanJob job =
        job(
            ScanJobType.PATH,
            source,
            Map.of(a.toString(), List.of("A"), b.toString(), List.of("B")));
    var coordinator = coordinator(job);
    coordinator.recoverInterruptedQuarantine();
    assertThat(a).exists();
    assertThat(b).exists();
    assertThat(artifacts).isEmpty();
    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(job.getErrorMessage()).contains(QuarantineService.SOURCE_FAILURE);
  }

  @Test
  void legacyUnknownScopeNeverWidensFromFileToDirectory() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    Path newSibling = Files.writeString(source.resolve("untrusted.bin"), "B");
    ScanJob job = job(ScanJobType.PATH, source, Map.of(newSibling.toString(), List.of("X")));
    job.setScanTargetWasDirectory(null);
    var coordinator = coordinator(job);
    coordinator.recoverInterruptedQuarantine();
    assertThat(newSibling).exists();
    verify(finalizer)
        .finishFound(
            eq(job.getId()),
            any(),
            isNull(),
            argThat(s -> s != null && s.contains(QuarantineService.SOURCE_FAILURE)));
  }

  @Test
  void journalCommitFailureAfterMoveNeverFinishesAJob() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    Path infected = Files.writeString(source.resolve("infected"), "malware");
    ScanJob job = job(ScanJobType.PATH, source, Map.of(infected.toString(), List.of("Malware")));
    var coordinator = coordinator(job);
    doThrow(new IllegalStateException("DB offline"))
        .when(ledger)
        .transition(anyString(), anyString());
    assertThatThrownBy(
            () ->
                coordinator.attempt(
                    job.getId(), infected, source, List.of(source), true, List.of("Malware")))
        .isInstanceOf(QuarantineArtifactCoordinator.JournalCommitFailure.class);
    assertThat(infected).doesNotExist();
    assertThat(artifacts).hasSize(1);
    assertThat(artifacts.values().iterator().next().getStatus()).isEqualTo("PREPARED");
    verify(finalizer, never()).finishFound(anyString(), any(), any(), any());
  }

  @Test
  void missingDetectedFileIsRecordedAsQuarantineFailureNotSilentlySkipped() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    Path existing = Files.writeString(source.resolve("existing.bin"), "malware");
    Path missing = source.resolve("missing.bin");
    ScanJob job =
        job(
            ScanJobType.PATH,
            source,
            Map.of(existing.toString(), List.of("A"), missing.toString(), List.of("B")));
    var coordinator = coordinator(job);
    coordinator.prepareSources(
        job.getId(),
        Map.of(existing.toString(), List.of("A"), missing.toString(), List.of("B")),
        source,
        List.of(source),
        true);
    coordinator.recoverInterruptedQuarantine();
    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(existing).doesNotExist();
    verify(finalizer)
        .finishFound(
            eq(job.getId()),
            any(),
            nullable(Path.class),
            argThat(
                message -> message != null && message.contains(QuarantineService.SOURCE_FAILURE)));
  }

  @Test
  void finishedHardeningFailureIsRetriedOnRestartAndWarningReconciled() throws Exception {
    Path source = Files.createDirectories(temp.resolve("watched"));
    ScanJob job =
        job(ScanJobType.PATH, source, Map.of(source.resolve("infected").toString(), List.of("A")));
    job.setStatus(ScanJobStatus.FINISHED);
    var coordinator = coordinator(job);
    String id = "c".repeat(32);
    Path destination =
        Files.writeString(
            Files.createDirectories(temp.resolve("quarantine")).resolve(id + ".bin"), "malware");
    QuarantineArtifact artifact =
        new QuarantineArtifact(
            id,
            job.getId(),
            source.resolve("infected").toString(),
            destination.toString(),
            "[\"A\"]");
    var evidence = QuarantineFileIdentity.capture(destination);
    artifact.setSourceEvidence(evidence.sizeBytes(), evidence.sha256());
    artifact.setStatus("HARDEN_FAILED");
    artifacts.put(id, artifact);
    when(ledger.outstandingAfter("")).thenReturn(List.of(artifact));
    when(ledger.outstandingAfter(id)).thenReturn(List.of());
    coordinator.recoverInterruptedQuarantine();
    assertThat(artifact.getStatus()).isEqualTo("MOVED");
    verify(finalizer).reconcileQuarantineWarning(job.getId(), false);
  }

  @Test
  void interruptedUploadWithoutJournalCannotTrustExistingDestination() throws Exception {
    Path source = Files.createDirectories(temp.resolve("uploads"));
    Path stored = source.resolve("original.bin");
    ScanJob job = job(ScanJobType.UPLOAD, stored, Map.of(stored.toString(), List.of("EICAR")));
    var coordinator = coordinator(job);
    Path destination =
        Files.createDirectories(temp.resolve("quarantine")).resolve(job.getId() + ".bin");
    Files.writeString(destination, "infected");
    coordinator.recoverInterruptedQuarantine();
    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(artifacts).isEmpty();
    assertThat(destination).exists();
    assertThat(job.getErrorMessage()).contains(QuarantineService.UPLOAD_FAILURE);
  }

  @Test
  void missingUploadCannotEstablishEvidenceFromAnUnjournaledDestination() throws Exception {
    Path stored = Files.createDirectories(temp.resolve("uploads")).resolve("missing.bin");
    ScanJob job = job(ScanJobType.UPLOAD, stored, Map.of(stored.toString(), List.of("EICAR")));
    var coordinator = coordinator(job);
    Path destination =
        Files.writeString(
            Files.createDirectories(temp.resolve("quarantine")).resolve(job.getId() + ".bin"),
            "unrelated");

    var outcome = coordinator.attemptUpload(job.getId(), stored, List.of("EICAR"));

    assertThat(outcome.failed()).isTrue();
    assertThat(outcome.path()).isNull();
    assertThat(artifacts.get(job.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(Files.readString(destination)).isEqualTo("unrelated");
  }
}
