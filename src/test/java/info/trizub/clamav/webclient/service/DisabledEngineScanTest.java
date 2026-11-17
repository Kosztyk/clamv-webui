package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DisabledEngineScanTest {
  @Test
  void browserAndWatcherSubmissionPathsHonorTheCentralEngineSwitchBeforeWritingOrEnqueueing() {
    var repository = mock(ScanJobRepository.class);
    var executor = mock(ScanExecutionService.class);
    var settings = mock(SettingsService.class);
    var policy = mock(DaemonScanPolicy.class);
    var jobs = new ScanJobService(repository, settings, executor, policy);
    var endpoint =
        new ClamdEndpoint("engine", "localhost", 3310, xyz.capybara.clamav.Platform.UNIX);
    endpoint.setEnabled(false);
    assertThatThrownBy(
            () ->
                jobs.createUploadJobs(
                    List.of(
                        new MockMultipartFile("files", "one.txt", "text/plain", new byte[] {1})),
                    endpoint,
                    "admin",
                    "BROWSER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disabled");
    assertThatThrownBy(() -> jobs.createPathJob("/scans/file", endpoint, "admin", "BROWSER"))
        .hasMessageContaining("disabled");
    assertThatThrownBy(() -> jobs.createWatchFileJob(Path.of("/scans/file"), endpoint, "watcher"))
        .hasMessageContaining("disabled");
    verifyNoInteractions(repository, executor, settings, policy);
  }
}
