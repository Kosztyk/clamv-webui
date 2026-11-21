package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScanExecutionQueueCapacityTest {
  private static ScanExecutionService worker(
      SettingsService s, ScanJobRepository db, ScanMetrics m) {
    return new ScanExecutionService(
        s,
        db,
        new ObjectMapper(),
        mock(ClamavClientProvider.class),
        mock(QuarantineService.class),
        mock(NotificationService.class),
        mock(AuditService.class),
        m,
        mock(DaemonScanPolicy.class),
        mock(UploadStorageService.class));
  }

  @Test
  void overflowLeavesJobDurableAndRecoveryMayRetry() throws Exception {
    SettingsService s = mock(SettingsService.class);
    when(s.concurrentScans()).thenReturn(1);
    ScanJobRepository db = mock(ScanJobRepository.class);
    ScanMetrics metrics = mock(ScanMetrics.class);
    ScanExecutionService service = worker(s, db, metrics);
    ReflectionTestUtils.setField(service, "queueCapacity", 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(db.findById("busy"))
        .thenAnswer(
            inv -> {
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new AssertionError("fixture timed out");
              return Optional.empty();
            });
    when(db.findById("waiting")).thenReturn(Optional.empty());
    when(db.findById("deferred")).thenReturn(Optional.empty());
    ExecutorService pool = null;
    try {
      service.init();
      pool = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
      service.enqueue("busy");
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      service.enqueue("waiting");
      service.enqueue("deferred");
      verify(metrics).recordDispatchDeferred();
      verify(db, never()).claimQueued(eq("deferred"), any(), any(), any());
    } finally {
      release.countDown();
      if (pool != null) {
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
    // A fresh worker represents the durable QUEUED row's scheduled retry.
    service.init();
    pool = (ExecutorService) ReflectionTestUtils.getField(service, "executor");
    try {
      service.enqueue("deferred");
      pool.shutdown();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      verify(db).findById("deferred");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rejectsInvalidCapacityBeforeCreatingWorkers() {
    SettingsService s = mock(SettingsService.class);
    when(s.concurrentScans()).thenReturn(1);
    ScanExecutionService service =
        worker(s, mock(ScanJobRepository.class), mock(ScanMetrics.class));
    ReflectionTestUtils.setField(service, "queueCapacity", 0);
    assertThatThrownBy(service::init).isInstanceOf(IllegalArgumentException.class);
    ReflectionTestUtils.setField(service, "queueCapacity", 10001);
    assertThatThrownBy(service::init).isInstanceOf(IllegalArgumentException.class);
  }
}
