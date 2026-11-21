package info.trizub.clamav.webclient.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

/** Run failure/recovery transitions directly, not just string-based source checks. */
class LiveScanReconciliationTest {
  private final ScanJobRepository jobs = mock(ScanJobRepository.class);
  private final ScanExecutionService executor = mock(ScanExecutionService.class);
  private final QuarantineArtifactCoordinator coordinator =
      mock(QuarantineArtifactCoordinator.class);

  @SuppressWarnings("unchecked")
  ScanJobService service() {
    ObjectProvider<QuarantineArtifactCoordinator> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(coordinator);
    when(executor.acceptingWork()).thenReturn(true);
    return new ScanJobService(
        jobs,
        mock(SettingsService.class),
        executor,
        mock(DaemonScanPolicy.class),
        mock(UploadStorageService.class),
        mock(UploadJobCommitter.class),
        provider);
  }

  @Test
  void liveRecoverySkipsAnActivePositiveWorkerButRecoversAnUnownedStagedVerdict() {
    ScanJobService service = service();
    when(jobs.findStagedPositiveIdsAfter(
            eq(ScanJobStatus.RUNNING), anyString(), any(Pageable.class)))
        .thenReturn(List.of("active", "stranded"));
    when(jobs.findOrphanRunningIdsAfter(
            any(), any(Instant.class), anyString(), any(Pageable.class)))
        .thenReturn(List.of());
    when(executor.isLocallyInFlight("active")).thenReturn(true);
    service.reconcileInterrupted();
    verify(coordinator).recoverStagedJob("stranded");
    verify(coordinator, never()).recoverStagedJob("active");
    verify(jobs, never()).requeueOrphan(anyString(), any(), any());
  }

  @Test
  void interruptedNonPositiveJobCanRequeueOnlyAfterWorkerOwnershipEnds() {
    ScanJobService service = service();
    when(jobs.findStagedPositiveIdsAfter(any(), anyString(), any(Pageable.class)))
        .thenReturn(List.of());
    when(jobs.findOrphanRunningIdsAfter(
            any(), any(Instant.class), anyString(), any(Pageable.class)))
        .thenReturn(List.of("active", "stranded"));
    when(executor.isLocallyInFlight("active")).thenReturn(true);
    when(jobs.requeueOrphan("stranded", ScanJobStatus.RUNNING, ScanJobStatus.QUEUED)).thenReturn(1);
    service.reconcileInterrupted();
    verify(jobs).requeueOrphan("stranded", ScanJobStatus.RUNNING, ScanJobStatus.QUEUED);
    verify(jobs, never()).requeueOrphan(eq("active"), any(), any());
    verify(executor).enqueue("stranded");
  }

  @Test
  void aFailedRecoveryIsBackedOffWithoutBlockingOtherJobs() {
    ScanJobService service = service();
    when(jobs.findStagedPositiveIdsAfter(any(), anyString(), any(Pageable.class)))
        .thenReturn(List.of("a", "b"));
    when(jobs.findOrphanRunningIdsAfter(
            any(), any(Instant.class), anyString(), any(Pageable.class)))
        .thenReturn(List.of());
    doThrow(new IllegalStateException("journal offline")).when(coordinator).recoverStagedJob("a");
    service.reconcileInterrupted();
    service.reconcileInterrupted();
    verify(coordinator, times(1)).recoverStagedJob("a");
    verify(coordinator, times(2)).recoverStagedJob("b");
  }

  @Test
  void keysetCursorVisitsRowsBeyondTheFirstFourOnNextTick() {
    ScanJobService service = service();
    when(jobs.findStagedPositiveIdsAfter(eq(ScanJobStatus.RUNNING), eq(""), any(Pageable.class)))
        .thenReturn(List.of("a", "b", "c", "d", "e", "f"));
    when(jobs.findStagedPositiveIdsAfter(eq(ScanJobStatus.RUNNING), eq("d"), any(Pageable.class)))
        .thenReturn(List.of("e", "f"));
    when(jobs.findOrphanRunningIdsAfter(
            any(), any(Instant.class), anyString(), any(Pageable.class)))
        .thenReturn(List.of());
    service.reconcileInterrupted();
    verify(coordinator, never()).recoverStagedJob("e");
    service.reconcileInterrupted();
    verify(coordinator).recoverStagedJob("e");
    verify(coordinator).recoverStagedJob("f");
  }
}
