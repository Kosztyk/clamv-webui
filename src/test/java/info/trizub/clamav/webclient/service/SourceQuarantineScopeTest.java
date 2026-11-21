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

/** Never trust the filename keys provided in a ClamD VirusFound response. */
class SourceQuarantineScopeTest {
  @TempDir Path temporary;

  private final SettingsService settings = mock(SettingsService.class);
  private final QuarantineService quarantine = new QuarantineService(settings);

  private Path root() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("authorized"));
    when(settings.allowedRoots()).thenReturn(List.of(root));
    when(settings.quarantineEnabled()).thenReturn(true);
    when(settings.quarantineDir()).thenReturn(temporary.resolve("quarantine"));
    when(settings.uploadDir()).thenReturn(temporary.resolve("uploads"));
    return root;
  }

  private Path infected(Path parent, String name) throws Exception {
    Files.createDirectories(parent);
    Path file = parent.resolve(name);
    Files.writeString(file, "EICAR test fixture");
    return file;
  }

  @Test
  void rejectsOutsideAllowedRootWithoutMovingTheFile() throws Exception {
    Path root = root();
    Path target = Files.createDirectories(root.resolve("scan"));
    Path outside = infected(temporary.resolve("outside"), "important.txt");
    var result = quarantine.attemptSource(outside, target, List.of(root), true);
    assertThat(result.failed()).isTrue();
    assertThat(result.path()).isNull();
    assertThat(outside).exists();
  }

  @Test
  void rejectsDifferentFileInsideTheAllowedRootWhenScanningOneFile() throws Exception {
    Path root = root();
    Path target = infected(root, "scan.txt");
    Path sibling = infected(root, "sibling.txt");
    assertThat(quarantine.attemptSource(sibling, target, List.of(root), false).failed()).isTrue();
    assertThat(target).exists();
    assertThat(sibling).exists();
  }

  @Test
  void rejectsSiblingDirectoryEvenThoughItIsWithinAllowedRoots() throws Exception {
    Path root = root();
    Path target = Files.createDirectories(root.resolve("scan"));
    Path sibling = infected(root.resolve("scan-neighbor"), "data.bin");
    assertThat(quarantine.attemptSource(sibling, target, List.of(root), true).failed()).isTrue();
    assertThat(sibling).exists();
  }

  @Test
  void nullAllowedRootsFailsClosed() throws Exception {
    Path root = root();
    Path file = infected(root, "target.bin");
    assertThat(quarantine.attemptSource(file, file, null, false).failed()).isTrue();
    assertThat(file).exists();
  }

  @Test
  void rejectsTraversalAndRelativeAndNonCanonicalPaths() throws Exception {
    Path root = root();
    Path target = Files.createDirectories(root.resolve("scan"));
    Path victim = infected(root.resolve("other"), "data.bin");
    assertThat(
            quarantine
                .attemptSource(target.resolve("../other/data.bin"), target, List.of(root), true)
                .failed())
        .isTrue();
    assertThat(quarantine.attemptSource(Path.of("data.bin"), target, List.of(root), true).failed())
        .isTrue();
    assertThat(victim).exists();
  }

  @Test
  void refusesSymlinkEscapingTheScanTarget() throws Exception {
    Path root = root();
    Path target = Files.createDirectories(root.resolve("scan"));
    Path victim = infected(temporary.resolve("other"), "victim.bin");
    Path link = target.resolve("infected.bin");
    Files.createSymbolicLink(link, victim);
    assertThat(quarantine.attemptSource(link, target, List.of(root), true).failed()).isTrue();
    assertThat(link).isSymbolicLink();
    assertThat(victim).exists();
  }

  @Test
  void rejectsDetectionWhenAllowedRootWasRemovedFromConfiguration() throws Exception {
    Path root = root();
    Path target = infected(root, "target.bin");
    assertThat(quarantine.attemptSource(target, target, List.of(), false).failed()).isTrue();
    assertThat(target).exists();
  }

  @Test
  void quarantinesLegitimateDetectedDescendant() throws Exception {
    Path root = root();
    Path target = Files.createDirectories(root.resolve("scan"));
    Path file = infected(target.resolve("nested"), "infected.bin");
    var result = quarantine.attemptSource(file, target, List.of(root), true);
    assertThat(result.failed()).isFalse();
    assertThat(result.path()).isNotNull().exists();
    assertThat(file).doesNotExist();
  }

  @Test
  void quarantinesOnlyTheExactAuthorizedSingleFile() throws Exception {
    Path root = root();
    Path file = infected(root, "infected.bin");
    var result = quarantine.attemptSource(file, file, List.of(root), false);
    assertThat(result.failed()).isFalse();
    assertThat(result.path()).exists();
    assertThat(file).doesNotExist();
  }

  @Test
  void originalSingleFileCannotBecomeNewDirectoryScope() throws Exception {
    Path root = root();
    Path originalFile = infected(root, "was-a-file");
    Files.delete(originalFile);
    Files.createDirectory(originalFile);
    Path replacement = infected(originalFile, "new-unscanned.bin");
    assertThat(quarantine.attemptSource(replacement, originalFile, List.of(root), false).failed())
        .isTrue();
    assertThat(replacement).exists();
  }

  @Test
  void originalDirectoryCannotBeReplacedByFileForQuarantine() throws Exception {
    Path root = root();
    Path originalDirectory = Files.createDirectories(root.resolve("was-a-directory"));
    Files.delete(originalDirectory);
    Files.writeString(originalDirectory, "different target");
    assertThat(
            quarantine
                .attemptSource(originalDirectory, originalDirectory, List.of(root), true)
                .failed())
        .isTrue();
    assertThat(originalDirectory).exists();
  }

  @Test
  void disabledQuarantineDoesNotMoveAnything() throws Exception {
    Path root = root();
    when(settings.quarantineEnabled()).thenReturn(false);
    Path file = infected(root, "infected.bin");
    assertThat(quarantine.attemptSource(file, file, List.of(root), false).failed()).isFalse();
    assertThat(file).exists();
  }

  /**
   * Exercise the real worker path: an out-of-scope daemon filename is reported as infected, but the
   * source survives and a quarantine warning is saved.
   */
  @Test
  void scanWorkerKeepsVirusVerdictButNeverMovesOutOfScopeResult() throws Exception {
    Path root = root();
    Path directory = Files.createDirectories(root.resolve("scan"));
    Path outside = infected(root.resolve("other"), "outside.bin");
    Path inside = infected(directory, "inside.bin");
    var repository = mock(ScanJobRepository.class);
    var provider = mock(ClamavClientProvider.class);
    var policy = mock(DaemonScanPolicy.class);
    var client = mock(ClamdClient.class);
    var metrics = mock(ScanMetrics.class);
    var notifications = mock(NotificationService.class);
    var uploads = mock(UploadStorageService.class);
    var job = new ScanJob();
    job.setId("source-scope");
    job.setTarget(directory.toString());
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.QUEUED);
    job.setEndpoint(new ClamdEndpoint("test", "localhost", 3310, Platform.UNIX));
    when(repository.findById(job.getId())).thenReturn(Optional.of(job));
    when(repository.claimQueued(
            eq(job.getId()),
            eq(ScanJobStatus.QUEUED),
            eq(ScanJobStatus.RUNNING),
            any(Instant.class)))
        .thenAnswer(
            invocation -> {
              job.setStatus(ScanJobStatus.RUNNING);
              return 1;
            });
    when(provider.clientFor(job.getEndpoint())).thenReturn(client);
    when(policy.scanPathReport(client, job.getEndpoint(), directory))
        .thenReturn(
            new DaemonScanPolicy.PathScanReport(
                new ScanResult.VirusFound(
                    Map.of(
                        outside.toString(), List.of("evil"), inside.toString(), List.of("EICAR"))),
                2,
                0,
                List.of(),
                false));
    var executor =
        new ScanExecutionService(
            settings,
            repository,
            new ObjectMapper(),
            provider,
            quarantine,
            notifications,
            mock(AuditService.class),
            metrics,
            policy,
            uploads);
    ReflectionTestUtils.invokeMethod(executor, "runJob", job.getId());
    assertThat(job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);
    assertThat(job.getErrorMessage()).isEqualTo(QuarantineService.SOURCE_FAILURE);
    assertThat(job.getFoundVirusesJson()).contains("evil", "EICAR");
    assertThat(outside).exists();
    assertThat(inside).doesNotExist();
    verify(repository).save(job);
    verify(notifications).notifyIfNeeded(job);
  }
}
