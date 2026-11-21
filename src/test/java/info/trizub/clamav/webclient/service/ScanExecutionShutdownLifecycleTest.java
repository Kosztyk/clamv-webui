package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

class ScanExecutionShutdownLifecycleTest {
  @TempDir Path tempDir;

  private static ScanExecutionService worker(
      SettingsService settings,
      ScanJobRepository db,
      ClamavClientProvider provider,
      UploadStorageService uploads) {
    ScanExecutionService service =
        new ScanExecutionService(
            settings,
            db,
            new ObjectMapper(),
            provider,
            mock(QuarantineService.class),
            mock(NotificationService.class),
            mock(AuditService.class),
            mock(ScanMetrics.class),
            mock(DaemonScanPolicy.class),
            uploads);
    ReflectionTestUtils.setField(service, "shutdownGraceSeconds", 2);
    ReflectionTestUtils.setField(service, "shutdownForceSeconds", 1);
    return service;
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Test
  void shutdownStopsNewSubmissionsAndDiscardsOnlyLocalPendingTasks() throws Exception {
    SettingsService settings = mock(SettingsService.class);
    when(settings.concurrentScans()).thenReturn(1);
    ScanJobRepository db = mock(ScanJobRepository.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(db.findById("busy"))
        .thenAnswer(
            inv -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("fixture timed out");
              return Optional.empty();
            });
    ScanExecutionService service =
        worker(settings, db, mock(ClamavClientProvider.class), mock(UploadStorageService.class));
    ReflectionTestUtils.setField(service, "queueCapacity", 1);
    service.init();
    ThreadPoolExecutor pool =
        (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
    Thread closer = null;
    try {
      service.enqueue("busy");
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      service.enqueue("waiting");
      assertThat(pool.getQueue()).hasSize(1);
      closer = new Thread(service::stop, "test-scan-shutdown");
      closer.start();
      until(pool::isShutdown);
      service.enqueue("after-stop");
      release.countDown();
      closer.join(4000);
      assertThat(closer.isAlive()).isFalse();
      assertThat(pool.isTerminated()).isTrue();
      assertThat(service.acceptingWork()).isFalse();
      verify(db, never()).findById("waiting");
      verify(db, never()).findById("after-stop");
      verify(db, never()).claimQueued(anyString(), any(), any(), any());
      // @PreDestroy must be idempotent after SmartLifecycle.stop().
      service.destroy();

      // A fresh process picks up the durable row without relying on an
      // in-memory FutureTask surviving the old executor's cancellation.
      when(db.requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED)).thenReturn(0);
      when(db.findQueuedIds(eq(ScanJobStatus.QUEUED), any(Pageable.class)))
          .thenReturn(List.of("waiting"));
      ScanExecutionService fresh =
          worker(settings, db, mock(ClamavClientProvider.class), mock(UploadStorageService.class));
      fresh.init();
      try {
        new ScanJobService(db, settings, fresh, mock(DaemonScanPolicy.class)).resumeQueued();
        verify(db, timeout(3000)).findById("waiting");
      } finally {
        fresh.stop();
      }
    } finally {
      release.countDown();
      service.stop();
      if (closer != null) closer.join(4000);
    }
  }

  @Test
  void activeScanFinishesAndPersistsVerdictWithinGrace() throws Exception {
    SettingsService settings = mock(SettingsService.class);
    when(settings.concurrentScans()).thenReturn(1);
    ScanJobRepository db = mock(ScanJobRepository.class);
    ClamavClientProvider provider = mock(ClamavClientProvider.class);
    ClamdClient client = mock(ClamdClient.class);
    when(provider.clientFor(any())).thenReturn(client);
    ScanJob job = upload("grace", tempDir.resolve("grace.bin"));
    when(db.findById(job.getId())).thenReturn(Optional.of(job));
    when(db.claimQueued(
            eq(job.getId()),
            eq(ScanJobStatus.QUEUED),
            eq(ScanJobStatus.RUNNING),
            any(Instant.class)))
        .thenAnswer(
            inv -> {
              job.setStatus(ScanJobStatus.RUNNING);
              return 1;
            });
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(client.scan(any(InputStream.class)))
        .thenAnswer(
            inv -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out");
              return ScanResult.OK.INSTANCE;
            });
    UploadStorageService uploads = mock(UploadStorageService.class);
    ScanExecutionService service = worker(settings, db, provider, uploads);
    ReflectionTestUtils.setField(service, "shutdownGraceSeconds", 3);
    service.init();
    Thread closer = null;
    try {
      service.enqueue(job.getId());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      closer = new Thread(service::stop);
      closer.start();
      ThreadPoolExecutor pool =
          (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
      until(pool::isShutdown);
      release.countDown();
      closer.join(4000);
      assertThat(closer.isAlive()).isFalse();
      assertThat(pool.isTerminated()).isTrue();
      assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
      assertThat(job.getVerdict()).isEqualTo(ScanVerdict.OK);
      verify(db).save(job);
      verify(uploads).cleanupFinished(job.getId());
    } finally {
      release.countDown();
      service.stop();
      if (closer != null) closer.join(4000);
    }
  }

  @Test
  void forcedCancellationPreservesRunningRowAndFreshStartupRecoversIt() throws Exception {
    SettingsService settings = mock(SettingsService.class);
    when(settings.concurrentScans()).thenReturn(1);
    ScanJobRepository db = mock(ScanJobRepository.class);
    ClamavClientProvider provider = mock(ClamavClientProvider.class);
    ClamdClient client = mock(ClamdClient.class);
    when(provider.clientFor(any())).thenReturn(client);
    ScanJob job = upload("interrupted", tempDir.resolve("retry.bin"));
    when(db.findById(job.getId())).thenReturn(Optional.of(job));
    when(db.claimQueued(
            eq(job.getId()),
            eq(ScanJobStatus.QUEUED),
            eq(ScanJobStatus.RUNNING),
            any(Instant.class)))
        .thenAnswer(
            inv -> {
              if (job.getStatus() != ScanJobStatus.QUEUED) return 0;
              job.setStatus(ScanJobStatus.RUNNING);
              job.setStartedAt(Instant.now());
              return 1;
            });
    CountDownLatch entered = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    when(client.scan(any(InputStream.class)))
        .thenAnswer(
            inv -> {
              if (attempts.incrementAndGet() == 1) {
                entered.countDown();
                try {
                  if (!new CountDownLatch(1).await(5, TimeUnit.SECONDS))
                    throw new IOException("fixture timed out");
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  throw new IOException("clamd request interrupted", ex);
                }
              }
              return ScanResult.OK.INSTANCE;
            });
    UploadStorageService uploads = mock(UploadStorageService.class);
    ScanExecutionService old = worker(settings, db, provider, uploads);
    ReflectionTestUtils.setField(old, "shutdownGraceSeconds", 0);
    old.init();
    try {
      old.enqueue(job.getId());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(job.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
      old.stop();
      assertThat(
              ((ThreadPoolExecutor) ReflectionTestUtils.getField(old, "executor")).isTerminated())
          .isTrue();
      assertThat(job.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
      assertThat(job.getVerdict()).isNull();
      verify(db, never()).save(any(ScanJob.class));
      verify(uploads, never()).cleanupFinished(job.getId());
      assertThat(Files.exists(Path.of(job.getStoredPath()))).isTrue();

      // Reconstruct the service as Spring would on a fresh process boot.
      when(db.requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED))
          .thenAnswer(
              inv -> {
                if (job.getStatus() != ScanJobStatus.RUNNING) return 0;
                job.setStatus(ScanJobStatus.QUEUED);
                return 1;
              });
      when(db.findQueuedIds(eq(ScanJobStatus.QUEUED), any(Pageable.class)))
          .thenAnswer(
              inv -> job.getStatus() == ScanJobStatus.QUEUED ? List.of(job.getId()) : List.of());
      ScanExecutionService fresh = worker(settings, db, provider, uploads);
      fresh.init();
      try {
        new ScanJobService(db, settings, fresh, mock(DaemonScanPolicy.class)).resumeQueued();
        verify(db, timeout(3000)).save(job);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
        assertThat(job.getVerdict()).isEqualTo(ScanVerdict.OK);
        verify(db).requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED);
      } finally {
        fresh.stop();
      }
    } finally {
      old.stop();
    }
  }

  private static ScanJob upload(String id, Path path) throws IOException {
    Files.writeString(path, "scan-fixture");
    ScanJob job = new ScanJob();
    job.setId(id);
    job.setType(ScanJobType.UPLOAD);
    job.setStatus(ScanJobStatus.QUEUED);
    job.setTarget(path.getFileName().toString());
    job.setStoredPath(path.toString());
    job.setEndpoint(new ClamdEndpoint("test", "localhost", 3310, Platform.UNIX));
    return job;
  }
}
