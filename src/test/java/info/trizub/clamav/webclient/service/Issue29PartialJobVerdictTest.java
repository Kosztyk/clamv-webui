package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

class Issue29PartialJobVerdictTest {
  @TempDir Path tempDir;

  @Test
  void partialCleanResultsArePersistedAsErrorInsteadOfFalseOk() {
    var repo = mock(ScanJobRepository.class);
    var scan = spy(createService(repo, mock(QuarantineService.class)));
    doNothing().when(scan).finishError(eq("partial"), anyString());
    ReflectionTestUtils.invokeMethod(
        scan,
        "handleResult",
        "partial",
        ScanJobType.PATH,
        ScanResult.OK.INSTANCE,
        null,
        "Directory scan incomplete: denied.txt could not be scanned",
        tempDir,
        true);
    verify(scan).finishError(eq("partial"), contains("denied.txt"));
    verify(scan, never()).finishOk(anyString());
  }

  @Test
  void partialInfectedResultsKeepVirusVerdictAndPersistPartialFailureDetails() throws Exception {
    var repo = mock(ScanJobRepository.class);
    var quarantine = mock(QuarantineService.class);
    when(quarantine.attemptSource(any(Path.class), eq(tempDir), anyList(), eq(true)))
        .thenReturn(new QuarantineService.Result(null, false));
    var job = new ScanJob();
    job.setId("partial-malware");
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.RUNNING);
    when(repo.findById("partial-malware")).thenReturn(Optional.of(job));
    var scan = createService(repo, quarantine);
    var virus =
        new ScanResult.VirusFound(
            Map.of(tempDir.resolve("infected.bin").toString(), List.of("EICAR-Test")));
    ReflectionTestUtils.invokeMethod(
        scan,
        "handleResult",
        "partial-malware",
        ScanJobType.PATH,
        virus,
        null,
        "Directory scan incomplete: unreadable.bin",
        tempDir,
        true);
    assertThat(job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);
    assertThat(job.getErrorMessage()).contains("unreadable.bin");
    assertThat(job.getFoundVirusesJson()).contains("EICAR-Test");
    verify(repo).save(job);
  }

  private ScanExecutionService createService(ScanJobRepository repo, QuarantineService quarantine) {
    var settings = mock(SettingsService.class);
    when(settings.allowedRoots()).thenReturn(List.of(tempDir));
    return new ScanExecutionService(
        settings,
        repo,
        new ObjectMapper(),
        mock(ClamavClientProvider.class),
        quarantine,
        mock(NotificationService.class),
        mock(AuditService.class),
        mock(ScanMetrics.class),
        mock(DaemonScanPolicy.class));
  }
}
