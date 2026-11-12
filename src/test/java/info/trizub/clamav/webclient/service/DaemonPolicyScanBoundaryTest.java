package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.Platform;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonPolicyScanBoundaryTest {
    @TempDir Path dir;
    private final SettingsService settings=mock(SettingsService.class);
    private final ScanJobRepository repository=mock(ScanJobRepository.class);
    private final ClamdEndpoint endpoint=new ClamdEndpoint("default","localhost",3310,Platform.UNIX);

    private DaemonScanPolicy policy() throws Exception {
        Files.writeString(dir.resolve("clamd.conf"),"StreamMaxLength 4\nMaxScanSize 4\nExcludePath \\.skip$\n");
        when(settings.uploadMaxBytes()).thenReturn(1024L);when(settings.uploadDir()).thenReturn(dir.resolve("uploads"));
        when(settings.allowedRoots()).thenReturn(List.of(dir));
        return new DaemonScanPolicy(new ClamdConfigurationObserver(dir.resolve("clamd.conf").toString(),"",true),settings);
    }

    @Test void mixedUploadBatchExceedingDaemonLimitQueuesAndCopiesNothing() throws Exception {
        var worker=mock(ScanExecutionService.class);
        var jobs=new ScanJobService(repository,settings,new ObjectMapper(),worker,policy());
        assertThatThrownBy(() -> jobs.createUploadJobs(List.of(
            new MockMultipartFile("files","small.txt","text/plain",new byte[2]),
            new MockMultipartFile("files","large.txt","text/plain",new byte[5])),endpoint,"test"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("effective upload limit");
        verifyNoInteractions(repository,worker);assertThat(Files.exists(dir.resolve("uploads"))).isFalse();
    }

    @Test void pathAndWatchSubmissionsEnforceExclusionsAndExistingAllowedRoots() throws Exception {
        var worker=mock(ScanExecutionService.class);
        var jobs=new ScanJobService(repository,settings,new ObjectMapper(),worker,policy());
        Path excluded=Files.writeString(dir.resolve("file.skip"),"data");
        assertThatThrownBy(() -> jobs.createPathJob(excluded.toString(),endpoint,"test")).hasMessageContaining("excluded");
        assertThatThrownBy(() -> jobs.createWatchFileJob(excluded,endpoint,"watcher")).hasMessageContaining("excluded");
        Path outside=Files.createTempFile("outside-policy-root", ".txt");
        try { assertThatThrownBy(() -> jobs.createPathJob(outside.toString(),endpoint,"test")).hasMessageContaining("not allowed"); }
        finally { Files.deleteIfExists(outside); }
        verifyNoInteractions(repository,worker);
    }

    @Test void queuedUploadIsRejectedByRefreshedPolicyBeforeAnyDaemonCommand() throws Exception {
        var policy=policy();
        Path stored=Files.writeString(dir.resolve("old-upload.txt"),"12345");
        var job=new ScanJob();job.setId("queued-job");job.setType(ScanJobType.UPLOAD);
        job.setStatus(ScanJobStatus.QUEUED);job.setStoredPath(stored.toString());job.setEndpoint(endpoint);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.claimQueued(eq(job.getId()),any(),any(),any())).thenReturn(1);
        var provider=mock(ClamavClientProvider.class);var client=mock(ClamdClient.class);
        when(provider.clientFor(endpoint)).thenReturn(client);
        var service=new ScanExecutionService(settings,repository,new ObjectMapper(),provider,
            mock(QuarantineService.class),mock(NotificationService.class),mock(AuditService.class),mock(ScanMetrics.class),policy);
        ReflectionTestUtils.invokeMethod(service,"runJob",job.getId());
        assertThat(job.getVerdict()).isEqualTo(ScanVerdict.ERROR);
        assertThat(job.getErrorMessage()).contains("upload limit");verifyNoInteractions(client);
    }

    @ParameterizedTest @EnumSource(value=ScanJobType.class,names={"PATH","WATCH"})
    void queuedCanonicalTargetCannotBeRedirectedToAnotherGloballyAllowedPath(ScanJobType type) throws Exception {
        var policy=policy();
        Path expected=Files.writeString(dir.resolve("authorized.txt"),"a").toRealPath();
        Path other=Files.writeString(dir.resolve("other.txt"),"b");
        var job=new ScanJob();job.setId("redirected-job");job.setType(type);job.setTarget(expected.toString());
        job.setStatus(ScanJobStatus.QUEUED);job.setEndpoint(endpoint);
        Files.delete(expected);Files.createSymbolicLink(expected,other);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.claimQueued(eq(job.getId()),any(),any(),any())).thenReturn(1);
        var provider=mock(ClamavClientProvider.class);var client=mock(ClamdClient.class);when(provider.clientFor(endpoint)).thenReturn(client);
        var service=new ScanExecutionService(settings,repository,new ObjectMapper(),provider,
            mock(QuarantineService.class),mock(NotificationService.class),mock(AuditService.class),mock(ScanMetrics.class),policy);
        ReflectionTestUtils.invokeMethod(service,"runJob",job.getId());
        assertThat(job.getVerdict()).isEqualTo(ScanVerdict.ERROR);
        assertThat(job.getErrorMessage()).contains("changed after submission");verifyNoInteractions(client);
    }
}
