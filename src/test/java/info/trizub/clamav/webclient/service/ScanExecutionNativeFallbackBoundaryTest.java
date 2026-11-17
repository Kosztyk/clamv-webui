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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/** The INSTREAM retry is a transport fallback, never a result-finalization retry. */
class ScanExecutionNativeFallbackBoundaryTest {
  @TempDir Path tempDir;

  private final class Fixture {
    final SettingsService settings = mock(SettingsService.class);
    final ScanJobRepository db = mock(ScanJobRepository.class);
    final ClamavClientProvider provider = mock(ClamavClientProvider.class);
    final ClamdClient clamd = mock(ClamdClient.class);
    final QuarantineService quarantine = mock(QuarantineService.class);
    final NotificationService notifications = mock(NotificationService.class);
    final UploadStorageService uploads = mock(UploadStorageService.class);
    final ScanJob job = new ScanJob();
    final Path stored;
    final ScanExecutionService execution;

    Fixture(String id) throws IOException {
      stored = tempDir.resolve(id + ".bin").toAbsolutePath().normalize();
      Files.writeString(stored, "scan-fixture");
      job.setId(id);
      job.setType(ScanJobType.UPLOAD);
      job.setStatus(ScanJobStatus.QUEUED);
      job.setTarget(stored.getFileName().toString());
      job.setStoredPath(stored.toString());
      job.setEndpoint(new ClamdEndpoint("test", "localhost", 3310, Platform.UNIX));

      when(settings.uploadPathScanEnabled()).thenReturn(true);
      when(settings.uploadPathScanFallbackToInstream()).thenReturn(true);
      when(provider.clientFor(job.getEndpoint())).thenReturn(clamd);
      when(db.findById(job.getId())).thenReturn(Optional.of(job));
      when(db.claimQueued(
              eq(job.getId()),
              eq(ScanJobStatus.QUEUED),
              eq(ScanJobStatus.RUNNING),
              any(Instant.class)))
          .thenAnswer(
              invocation -> {
                job.setStatus(ScanJobStatus.RUNNING);
                return 1;
              });
      when(db.save(any(ScanJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
      execution =
          new ScanExecutionService(
              settings,
              db,
              new ObjectMapper(),
              provider,
              quarantine,
              notifications,
              mock(AuditService.class),
              mock(ScanMetrics.class),
              mock(DaemonScanPolicy.class),
              uploads);
    }

    // Drive the actual runJob branch synchronously; shutdown/concurrency is covered
    // separately by ScanExecutionShutdownLifecycleTest.
    void run() {
      ReflectionTestUtils.invokeMethod(execution, "runJob", job.getId());
    }

    void neverStreamed() throws IOException {
      verify(clamd, never()).scan(any(InputStream.class));
    }
  }

  @Test
  void nativeSuccessFinalizesOnceWithoutInstream() throws Exception {
    Fixture f = new Fixture("native-ok");
    when(f.clamd.parallelScan(f.stored)).thenReturn(ScanResult.OK.INSTANCE);
    f.run();
    assertThat(f.job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.OK);
    verify(f.clamd).parallelScan(f.stored);
    f.neverStreamed();
    verify(f.db, times(1)).save(f.job);
    verify(f.notifications, times(1)).notifyIfNeeded(f.job);
    verify(f.uploads).cleanupFinished(f.job.getId());
  }

  @Test
  void nativeIOExceptionFallsBackOnceAndFinalizesTheInstreamVerdict() throws Exception {
    Fixture f = new Fixture("native-io-fallback");
    when(f.clamd.parallelScan(f.stored))
        .thenThrow(new IOException("daemon cannot read mounted path"));
    when(f.clamd.scan(any(InputStream.class)))
        .thenReturn(
            new ScanResult.VirusFound(
                Map.of(f.stored.toString(), List.of("Eicar-Test-Signature"))));
    when(f.settings.quarantineEnabled()).thenReturn(true);
    when(f.quarantine.attempt(f.stored, f.job.getId()))
        .thenReturn(new QuarantineService.Result(tempDir.resolve("quarantined.bin"), false));
    f.run();
    assertThat(f.job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);
    assertThat(f.job.getFoundVirusesJson()).contains("Eicar-Test-Signature");
    verify(f.clamd, times(1)).parallelScan(f.stored);
    verify(f.clamd, times(1)).scan(any(InputStream.class));
    verify(f.quarantine, times(1)).attempt(f.stored, f.job.getId());
    verify(f.db, times(1)).save(f.job);
    verify(f.notifications, times(1)).notifyIfNeeded(f.job);
  }

  @Test
  void nativeIOExceptionWithoutFallbackKeepsExistingErrorBehavior() throws Exception {
    Fixture f = new Fixture("native-io-disabled");
    when(f.settings.uploadPathScanFallbackToInstream()).thenReturn(false);
    when(f.clamd.parallelScan(f.stored)).thenThrow(new IOException("permission denied"));
    f.run();
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.ERROR);
    assertThat(f.job.getErrorMessage()).contains("permission denied");
    f.neverStreamed();
    verify(f.db, times(1)).save(f.job);
    verify(f.notifications, times(1)).notifyIfNeeded(f.job);
  }

  @Test
  void databaseFinalizationFailureAfterNativeSuccessNeverStartsSecondScan() throws Exception {
    Fixture f = new Fixture("native-db-error");
    when(f.clamd.parallelScan(f.stored)).thenReturn(ScanResult.OK.INSTANCE);
    when(f.db.save(any(ScanJob.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    f.run();
    verify(f.clamd, times(1)).parallelScan(f.stored);
    f.neverStreamed();
    verify(f.db, atLeastOnce()).save(f.job);
  }

  @Test
  void quarantineFailureAfterNativeDetectionNeverStartsSecondScan() throws Exception {
    Fixture f = new Fixture("native-quarantine-error");
    when(f.clamd.parallelScan(f.stored))
        .thenReturn(new ScanResult.VirusFound(Map.of(f.stored.toString(), List.of("Test.Virus"))));
    when(f.settings.quarantineEnabled()).thenReturn(true);
    when(f.quarantine.attempt(f.stored, f.job.getId()))
        .thenThrow(new IllegalStateException("quarantine subsystem unavailable"));
    f.run();
    verify(f.clamd, times(1)).parallelScan(f.stored);
    f.neverStreamed();
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);
    assertThat(f.job.getErrorMessage()).isEqualTo(QuarantineService.UPLOAD_FAILURE);
    verify(f.db, times(1)).save(f.job);
  }

  @Test
  void notificationFailureAfterNativeSuccessDoesNotChangeVerdictOrFallback() throws Exception {
    Fixture f = new Fixture("native-notify-error");
    when(f.clamd.parallelScan(f.stored)).thenReturn(ScanResult.OK.INSTANCE);
    doThrow(new IllegalStateException("webhook unavailable"))
        .when(f.notifications)
        .notifyIfNeeded(f.job);
    f.run();
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.OK);
    f.neverStreamed();
    verify(f.db, times(1)).save(f.job);
    verify(f.notifications, times(1)).notifyIfNeeded(f.job);
  }

  @Test
  void unexpectedNativeRuntimeFailureDoesNotTriggerInstreamFallback() throws Exception {
    Fixture f = new Fixture("native-runtime-error");
    when(f.clamd.parallelScan(f.stored))
        .thenThrow(new IllegalStateException("unexpected client bug"));
    f.run();
    assertThat(f.job.getVerdict()).isEqualTo(ScanVerdict.ERROR);
    f.neverStreamed();
    verify(f.db, times(1)).save(f.job);
  }
}
