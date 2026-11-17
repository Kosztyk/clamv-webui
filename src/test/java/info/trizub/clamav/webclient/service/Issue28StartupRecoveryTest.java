package info.trizub.clamav.webclient.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class Issue28StartupRecoveryTest {
  @Test
  void startupUsesIndexedUpdateAndBoundedIdQueryNotFullTable() {
    var repo = mock(ScanJobRepository.class);
    var settings = mock(SettingsService.class);
    var worker = mock(ScanExecutionService.class);
    when(worker.acceptingWork()).thenReturn(true);
    var policy = mock(DaemonScanPolicy.class);
    when(repo.requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED)).thenReturn(1);
    when(repo.findQueuedIds(eq(ScanJobStatus.QUEUED), any(Pageable.class)))
        .thenReturn(List.of("queued-one", "queued-two"));
    var service = new ScanJobService(repo, settings, worker, policy);
    service.resumeQueued();
    verify(repo).requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED);
    verify(repo)
        .findQueuedIds(
            eq(ScanJobStatus.QUEUED),
            argThat(p -> p.getPageSize() == 100 && p.getPageNumber() == 0));
    verify(worker).enqueue("queued-one");
    verify(worker).enqueue("queued-two");
    verify(repo, never()).findAll();
    verify(repo, never()).save(any());
    clearInvocations(repo, worker);
    service.reconcileQueued();
    verify(repo, never()).requeueInterrupted(any(), any());
    verify(repo).findQueuedIds(eq(ScanJobStatus.QUEUED), any(Pageable.class));
    clearInvocations(repo, worker);
    when(worker.acceptingWork()).thenReturn(false);
    service.reconcileQueued();
    verify(repo, never()).findQueuedIds(any(), any(Pageable.class));
    verify(repo, never()).requeueInterrupted(any(), any());
  }
}
