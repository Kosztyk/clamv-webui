package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineArtifactCoordinatorTest {
  @TempDir Path root;
  final QuarantineArtifactLedger ledger = mock(QuarantineArtifactLedger.class);
  final ScanJobFinalizationService finalizer = mock(ScanJobFinalizationService.class);
  final ScanJobRepository jobs = mock(ScanJobRepository.class);
  final SettingsService settings = mock(SettingsService.class);
  final ObjectMapper mapper = new ObjectMapper();

  QuarantineArtifactCoordinator coordinator() {
    when(settings.quarantineEnabled()).thenReturn(true);
    when(settings.quarantineDir()).thenReturn(root.resolve("quarantine"));
    when(settings.uploadDir()).thenReturn(root.resolve("uploads"));
    return new QuarantineArtifactCoordinator(
        new QuarantineService(settings), ledger, finalizer, jobs, settings, mapper);
  }

  @Test
  void journalReservationMustCommitBeforeAnySourceMove() throws Exception {
    Path infected = Files.writeString(root.resolve("infected.bin"), "EICAR");
    QuarantineArtifactCoordinator c = coordinator();
    doThrow(new IllegalStateException("database offline")).when(ledger).reserveBatch(anyList());
    assertThatThrownBy(
            () ->
                c.attempt("job1", infected, infected, List.of(root), false, List.of("EICAR-Test")))
        .isInstanceOf(QuarantineArtifactCoordinator.JournalCommitFailure.class);
    assertThat(Files.exists(infected)).isTrue();
    verify(ledger, never()).transition(anyString(), anyString());
  }

  @Test
  void journalRecordsEveryMovedSourceWithDistinctIdAndDestination() throws Exception {
    Path first = Files.writeString(root.resolve("a.bin"), "malware-a");
    Path second = Files.writeString(root.resolve("b.bin"), "malware-b");
    QuarantineArtifactCoordinator c = coordinator();
    var a = c.attempt("job1", first, root, List.of(root), true, List.of("Malware.A"));
    var b = c.attempt("job1", second, root, List.of(root), true, List.of("Malware.B"));
    assertThat(a.failed()).isFalse();
    assertThat(b.failed()).isFalse();
    assertThat(a.path()).exists().isNotEqualTo(b.path());
    assertThat(b.path()).exists();
    assertThat(first).doesNotExist();
    assertThat(second).doesNotExist();
    verify(ledger, times(2)).reserveBatch(anyList());
    verify(ledger, times(2)).transition(anyString(), eq("MOVED"));
  }

  @Test
  void restartRecoversMalwareVerdictAfterMoveBeforeFinalCommit() throws Exception {
    QuarantineArtifactCoordinator c = coordinator();
    Path infected = root.resolve("infected.bin");
    String id = "12345678901234567890123456789012";
    Path destination =
        Files.writeString(
            Files.createDirectories(root.resolve("quarantine")).resolve(id + ".bin"), "evidence");
    var artifact =
        new QuarantineArtifact(
            id, "job1", infected.toString(), destination.toString(), "[\"Malware.Test\"]");
    var evidence = QuarantineFileIdentity.capture(destination);
    artifact.setSourceEvidence(evidence.sizeBytes(), evidence.sha256());
    var job = new ScanJob();
    job.setId("job1");
    job.setStatus(ScanJobStatus.RUNNING);
    job.setType(info.trizub.clamav.webclient.model.ScanJobType.PATH);
    job.setTarget(root.toString());
    job.setScanTargetWasDirectory(true);
    when(settings.allowedRoots()).thenReturn(List.of(root));
    job.setFoundVirusesJson("{\"" + infected + "\":[\"Malware.Test\",\"Another.Signature\"]}");
    when(ledger.interruptedJobs()).thenReturn(List.of("job1"), List.of());
    when(ledger.forJob("job1")).thenReturn(List.of(artifact));
    doAnswer(
            inv -> {
              artifact.setStatus(inv.getArgument(1));
              return null;
            })
        .when(ledger)
        .transition(eq(id), anyString());
    when(jobs.findById("job1")).thenReturn(Optional.of(job));
    c.recoverInterruptedQuarantine();
    verify(ledger).transition(artifact.getId(), "MOVED");
    verify(finalizer)
        .finishFound(
            eq("job1"),
            argThat(
                report ->
                    report instanceof Map<?, ?> data && data.containsKey(infected.toString())),
            eq(destination),
            isNull());
  }
}
