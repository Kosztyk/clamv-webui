package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

class Issue29DirectoryScanTest {
  @TempDir Path root;
  private final ClamdEndpoint endpoint =
      new ClamdEndpoint("default", "localhost", 3310, Platform.UNIX);
  private final SettingsService settings = mock(SettingsService.class);

  private DaemonScanPolicy policy(String contents) throws IOException {
    Path config = root.resolve("clamd.conf");
    Files.writeString(config, contents);
    when(settings.allowedRoots()).thenReturn(List.of(root));
    return new DaemonScanPolicy(
        new ClamdConfigurationObserver(config.toString(), "", true), settings);
  }

  @Test
  @Timeout(25)
  void tenThousandFilesWithoutExclusionsUseOneDirectoryClamdCommand() throws Exception {
    DaemonScanPolicy policy = policy("MaxScanSize 0\nMaxFileSize 0\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    for (int i = 0; i < 10_000; i++) Files.writeString(tree.resolve("file-" + i + ".txt"), "OK");
    ClamdClient client = mock(ClamdClient.class);
    when(client.parallelScan(tree)).thenReturn(ScanResult.OK.INSTANCE);
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.directoryCommand()).isTrue();
    assertThat(report.scannedFiles()).isEqualTo(10_000);
    assertThat(report.partial()).isFalse();
    verify(client, times(1)).parallelScan(tree);
    verifyNoMoreInteractions(client);
  }

  @Test
  void excludedSubtreeIsSkippedAndOtherFilesAreScanned() throws Exception {
    DaemonScanPolicy policy = policy("ExcludePath /private/\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path eligible = Files.writeString(tree.resolve("ok.txt"), "ok");
    Path hidden = Files.createDirectory(tree.resolve("private"));
    Files.writeString(hidden.resolve("secret.txt"), "private");
    ClamdClient client = mock(ClamdClient.class);
    when(client.parallelScan(eligible)).thenReturn(ScanResult.OK.INSTANCE);
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.directoryCommand()).isFalse();
    assertThat(report.scannedFiles()).isEqualTo(1);
    assertThat(report.failedFiles()).isZero();
    verify(client).parallelScan(eligible);
    verifyNoMoreInteractions(client);
  }

  @Test
  void unreadableFileReturnsPartialErrorWithoutDiscardingDetectedMalware() throws Exception {
    DaemonScanPolicy policy = policy("ExcludePath \\.skip$\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path infected = Files.writeString(tree.resolve("infected.txt"), "EICAR");
    Path denied = Files.writeString(tree.resolve("denied.txt"), "unreadable");
    ClamdClient client = mock(ClamdClient.class);
    when(client.parallelScan(infected))
        .thenReturn(
            new ScanResult.VirusFound(
                Map.of(infected.toString(), List.of("Eicar-Test-Signature"))));
    when(client.parallelScan(denied)).thenThrow(new IOException("Permission denied"));
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.scannedFiles()).isEqualTo(1);
    assertThat(report.failedFiles()).isEqualTo(1);
    assertThat(report.failureSummary()).contains("denied.txt", "incomplete");
    assertThat(report.result()).isInstanceOf(ScanResult.VirusFound.class);
    assertThat(((ScanResult.VirusFound) report.result()).getFoundViruses())
        .containsKey(infected.toString());
    verify(client).parallelScan(infected);
    verify(client).parallelScan(denied);
  }

  @Test
  void nativeDirectoryCommandFailureFallsBackToFileLevelAndReportsUnscannableEntry()
      throws Exception {
    DaemonScanPolicy policy = policy("MaxScanSize 0\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path eligible = Files.writeString(tree.resolve("valid.txt"), "ok");
    Path denied = Files.writeString(tree.resolve("denied.txt"), "unreadable");
    ClamdClient client = mock(ClamdClient.class);
    when(client.parallelScan(tree)).thenThrow(new IOException("ClamD directory ERROR"));
    when(client.parallelScan(eligible)).thenReturn(ScanResult.OK.INSTANCE);
    when(client.parallelScan(denied)).thenThrow(new IOException("Permission denied"));
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.directoryCommand()).isFalse();
    assertThat(report.scannedFiles()).isEqualTo(1);
    assertThat(report.failedFiles()).isEqualTo(1);
    assertThat(report.failureSummary()).contains("denied.txt");
    verify(client).parallelScan(tree);
    verify(client).parallelScan(eligible);
    verify(client).parallelScan(denied);
  }

  @Test
  void symlinkInsideTreePreventsUnsafeWholeDirectoryCommand() throws Exception {
    DaemonScanPolicy policy = policy("MaxFileSize 0\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path safe = Files.writeString(tree.resolve("safe.txt"), "ok");
    Path outside = Files.createTempFile("issue29-outside", ".txt");
    try {
      Files.createSymbolicLink(tree.resolve("external-link"), outside);
      ClamdClient client = mock(ClamdClient.class);
      when(client.parallelScan(safe)).thenReturn(ScanResult.OK.INSTANCE);
      var report = policy.scanPathReport(client, endpoint, tree);
      assertThat(report.directoryCommand()).isFalse();
      assertThat(report.scannedFiles()).isEqualTo(1);
      verify(client).parallelScan(safe);
      verifyNoMoreInteractions(client);
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  @Timeout(10)
  void excludedDirectoryScansEligibleFilesConcurrently() throws Exception {
    DaemonScanPolicy policy = policy("ExcludePath \\.skip$\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path a = Files.writeString(tree.resolve("a"), "ok");
    Path b = Files.writeString(tree.resolve("b"), "ok");
    ClamdClient client = mock(ClamdClient.class);
    CountDownLatch started = new CountDownLatch(2);
    when(client.parallelScan(any(Path.class)))
        .thenAnswer(
            invocation -> {
              started.countDown();
              if (!started.await(4, TimeUnit.SECONDS))
                throw new IOException("Parallel scan did not start");
              return ScanResult.OK.INSTANCE;
            });
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.scannedFiles()).isEqualTo(2);
    assertThat(report.failedFiles()).isZero();
    verify(client).parallelScan(a);
    verify(client).parallelScan(b);
  }

  @Test
  void fullyUnreadableFilesCannotBeReportedClean() throws Exception {
    DaemonScanPolicy policy = policy("ExcludePath \\.skip$\n");
    Path tree = Files.createDirectory(root.resolve("tree"));
    Path file = Files.writeString(tree.resolve("unreadable.txt"), "denied");
    ClamdClient client = mock(ClamdClient.class);
    when(client.parallelScan(file)).thenThrow(new IOException("Permission denied"));
    var report = policy.scanPathReport(client, endpoint, tree);
    assertThat(report.scannedFiles()).isZero();
    assertThat(report.partial()).isTrue();
    assertThat(report.failureSummary()).contains("unreadable.txt");
  }
}
