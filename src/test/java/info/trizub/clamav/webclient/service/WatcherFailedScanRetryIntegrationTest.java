package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:watcher_retry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  ScanJobService.class,
  WatchFileSubmissionService.class,
  UploadStorageService.class,
  UploadJobCommitter.class,
  ScanJobFinalizationService.class,
  WatcherFailedScanRetryIntegrationTest.Json.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WatcherFailedScanRetryIntegrationTest {
  @TestConfiguration
  static class Json {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper();
    }
  }

  @TempDir Path root;
  @Autowired ScanJobRepository jobs;
  @Autowired ProcessedFileRepository markers;
  @Autowired WatchedDirectoryRepository watches;
  @Autowired ClamdEndpointRepository endpoints;
  @Autowired WatchFileSubmissionService submitter;
  @Autowired ScanJobFinalizationService finalizer;
  @MockitoBean SettingsService settings;
  @MockitoBean DaemonScanPolicy policy;
  @MockitoBean ScanExecutionService executor;
  @MockitoBean AuditService audit;
  @MockitoBean ScanMetrics metrics;
  ClamdEndpoint endpoint;
  WatcherService watcher;

  @BeforeEach
  void prepare() {
    watches.deleteAll();
    jobs.deleteAll();
    markers.deleteAll();
    endpoints.deleteAll();
    endpoint =
        endpoints.save(
            new ClamdEndpoint(
                "watcher-retry", "localhost", 3310, xyz.capybara.clamav.Platform.UNIX));
    watches.save(new WatchedDirectory(root.toString(), endpoint));
    when(settings.allowedRoots()).thenReturn(List.of(root));
    when(settings.watchEnabled()).thenReturn(true);
    when(settings.watchPollSeconds()).thenReturn(30);
    when(executor.acceptingWork()).thenReturn(true);
    watcher = new WatcherService(settings, watches, markers, submitter, Clock.systemUTC());
  }

  @Test
  void failedWatchIsDeferredThenRequeuedWithoutChangingFileMetadata() throws Exception {
    Path file = Files.writeString(root.resolve("failed-watch.bin"), "unchanged");
    watcher.poll();
    assertThat(jobs.count()).isEqualTo(1);
    ScanJob original = jobs.findAll().get(0);
    var marker = markers.findByPathAndEndpointId(file.toString(), endpoint.getId()).orElseThrow();
    assertThat(marker.getLastScanJobId()).isEqualTo(original.getId());
    finalizer.finishError(original.getId(), "ClamAV temporarily unavailable");
    marker = markers.findByPathAndEndpointId(file.toString(), endpoint.getId()).orElseThrow();
    assertThat(marker.getRetryFailures()).isEqualTo(1);
    assertThat(marker.getRetryAfter()).isAfter(Instant.now());
    watcher.start();
    watcher.poll();
    assertThat(jobs.count()).isEqualTo(1); // backoff prevents a tight retry loop
    watcher.shutdown();
    watcher =
        new WatcherService(
            settings,
            watches,
            markers,
            submitter,
            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));
    watcher.poll();
    assertThat(jobs.count()).isEqualTo(2);
    marker = markers.findByPathAndEndpointId(file.toString(), endpoint.getId()).orElseThrow();
    assertThat(marker.getLastScanJobId()).isNotEqualTo(original.getId());
    assertThat(marker.getRetryAfter()).isNull();
    assertThat(marker.getRetryFailures()).isEqualTo(1); // exponential history preserved
    watcher.shutdown();
  }

  @Test
  void OldFailureCannotMarkNewerFingerprintAsRetryable() throws Exception {
    Path file = Files.writeString(root.resolve("replaced-watch.bin"), "old content");
    watcher.poll();
    String first = jobs.findAll().get(0).getId();
    Files.writeString(file, "changed content and different size");
    watcher.start();
    watcher.poll();
    assertThat(jobs.count()).isEqualTo(2);
    String second =
        markers
            .findByPathAndEndpointId(file.toString(), endpoint.getId())
            .orElseThrow()
            .getLastScanJobId();
    assertThat(second).isNotEqualTo(first);
    finalizer.finishError(first, "stale failure");
    var current = markers.findByPathAndEndpointId(file.toString(), endpoint.getId()).orElseThrow();
    assertThat(current.getLastScanJobId()).isEqualTo(second);
    assertThat(current.getRetryAfter()).isNull();
    watcher.shutdown();
  }
}
