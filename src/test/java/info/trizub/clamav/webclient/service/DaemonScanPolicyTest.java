package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonScanPolicyTest {
    @TempDir Path dir;
    private final SettingsService settings=mock(SettingsService.class);
    private final ClamdEndpoint endpoint=new ClamdEndpoint("default","localhost",3310,Platform.UNIX);
    private ClamdConfigurationObserver observer;

    private DaemonScanPolicy policy(String config) throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),config);
        observer=new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true);
        when(settings.uploadMaxBytes()).thenReturn(2L*1024*1024*1024);
        when(settings.allowedRoots()).thenReturn(List.of(dir));
        return new DaemonScanPolicy(observer,settings);
    }

    @Test void uploadLimitIsTheMinimumAndRefreshChangesItImmediately() throws Exception {
        var policy=policy("StreamMaxLength 2M\nMaxScanSize 3M\nMaxFileSize 1M\n");
        assertThat(policy.uploadMaxBytes(endpoint)).isEqualTo(1024*1024);
        when(settings.uploadMaxBytes()).thenReturn(512L);assertThat(policy.uploadMaxBytes(endpoint)).isEqualTo(512);
        Files.writeString(dir.resolve("clamd.conf"),"StreamMaxLength 64\n");observer.refresh();
        assertThatThrownBy(() -> policy.requireUpload(65,endpoint)).isInstanceOf(IllegalArgumentException.class);
        policy.requireUpload(64,endpoint);
        assertThat(policy.uploadMaxBytes(new ClamdEndpoint("remote","remote",3310,Platform.UNIX))).isEqualTo(512);
    }

    @Test void zeroScanLimitsDoNotRemoveTheApplicationUploadCeilingButZeroStreamLimitForbidsData() throws Exception {
        var policy=policy("MaxScanSize 0\nMaxFileSize 0\n");
        assertThat(policy.uploadMaxBytes(endpoint)).isEqualTo(2L*1024*1024*1024);
        Files.writeString(dir.resolve("clamd.conf"),"StreamMaxLength 0\n");observer.refresh();
        assertThat(policy.uploadMaxBytes(endpoint)).isZero();
        assertThatThrownBy(() -> policy.requireUpload(1,endpoint)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void exclusionsPreserveEscapesSupportPosixClassesAndExcludeWholeSubtrees() throws Exception {
        var policy=policy("ExcludePath \\.log$\nExcludePath /private/\nExcludePath /file[[:digit:]]\\.txt$\n");
        assertThat(policy.isExcluded(dir.resolve("server.log"),endpoint)).isTrue();
        assertThat(policy.isExcluded(dir.resolve("serverXlog"),endpoint)).isFalse();
        assertThat(policy.isExcluded(dir.resolve("private/nested/file.txt"),endpoint)).isTrue();
        assertThat(policy.isExcluded(dir.resolve("file7.txt"),endpoint)).isTrue();
        assertThat(policy.isExcluded(dir.resolve("fileX.txt"),endpoint)).isFalse();
    }

    @Test void recursiveScanSendsOnlyEligibleFilesAndCombinesVirusSignatures() throws Exception {
        var policy=policy("ExcludePath \\.skip$\nExcludePath /private/\n");
        Path root=Files.createDirectory(dir.resolve("scan"));
        Path one=Files.writeString(root.resolve("one.txt"),"one");
        Path two=Files.writeString(root.resolve("two.txt"),"two");
        Files.writeString(root.resolve("three.skip"),"ignored");
        Files.createDirectory(root.resolve("private"));Files.writeString(root.resolve("private/four.txt"),"ignored");
        Path outside=Files.createTempFile("policy-outside", ".txt");
        try {
            Files.createSymbolicLink(root.resolve("outside-link"),outside);
            var client=mock(ClamdClient.class);
            when(client.parallelScan(one)).thenReturn(new ScanResult.VirusFound(Map.of(one.toString(),List.of("Test.One"))));
            when(client.parallelScan(two)).thenReturn(new ScanResult.VirusFound(Map.of(two.toString(),List.of("Test.Two"))));
            var result=policy.scanPath(client,endpoint,root);
            assertThat(result).isInstanceOf(ScanResult.VirusFound.class);
            assertThat(((ScanResult.VirusFound)result).getFoundViruses()).hasSize(2);
            verify(client).parallelScan(one);verify(client).parallelScan(two);verifyNoMoreInteractions(client);
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void emptyOrFullyExcludedDirectoryIsNeverReportedClean() throws Exception {
        var policy=policy("ExcludePath \\.skip$\n");Path root=Files.createDirectory(dir.resolve("scan"));
        Files.writeString(root.resolve("only.skip"),"ignored");var client=mock(ClamdClient.class);
        assertThatThrownBy(() -> policy.scanPath(client,endpoint,root)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No eligible");verifyNoInteractions(client);
    }

    @Test void scanSizeLimitRejectsOversizedPathAndDirectoryMemberBeforeSendingItsBytes() throws Exception {
        var policy=policy("MaxScanSize 3\n");Path root=Files.createDirectory(dir.resolve("scan"));
        Path file=Files.writeString(root.resolve("large.txt"),"1234");
        var client=mock(ClamdClient.class);
        assertThatThrownBy(() -> policy.requirePath(file,endpoint)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.scanPath(client,endpoint,root)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test void invalidInitialConfigurationBlocksDefaultScansButInspectionOnlyDoesNot() throws Exception {
        var policy=policy("ExcludePath [\n");
        assertThatThrownBy(() -> policy.requireUpload(1,endpoint)).hasMessageContaining("paused");
        var inspection=new DaemonScanPolicy(new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",false),settings);
        inspection.requireUpload(1,endpoint);
    }
}
