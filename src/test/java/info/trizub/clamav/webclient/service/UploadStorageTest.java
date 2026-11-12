package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadStorageTest {
    @TempDir Path folder;
    final SettingsService settings=mock(SettingsService.class);
    final ScanJobRepository jobs=mock(ScanJobRepository.class);
    final Instant now=Instant.parse("2026-11-10T12:00:00Z");
    UploadStorageService storage;
    @BeforeEach void prepare() {
        when(settings.uploadDir()).thenReturn(folder.resolve("uploads"));when(settings.quarantineDir()).thenReturn(folder.resolve("quarantine"));
        when(settings.uploadRetentionHours()).thenReturn(24L);when(settings.deleteCleanUploads()).thenReturn(true);
        storage=new UploadStorageService(settings,jobs,Clock.fixed(now,ZoneOffset.UTC));
    }
    String id(){return UUID.randomUUID().toString().replace("-","");}
    ScanJob upload(String id) throws Exception {
        Path file;
        try(var batch=storage.beginBatch()){file=batch.create(id,"sample.txt");Files.writeString(file,"fixture");batch.succeeded();}
        var job=new ScanJob();job.setId(id);job.setType(ScanJobType.UPLOAD);job.setTarget("sample.txt");job.setStoredPath(file.toString());
        job.setEndpoint(new ClamdEndpoint("engine","localhost",3310,xyz.capybara.clamav.Platform.UNIX));
        when(jobs.findById(id)).thenReturn(Optional.of(job));return job;
    }
    void run(ScanJob job,ScanResult result) throws Exception {
        var provider=mock(ClamavClientProvider.class);var client=mock(ClamdClient.class);
        when(provider.clientFor(any())).thenReturn(client);when(client.scan(any(java.io.InputStream.class))).thenReturn(result);
        when(jobs.claimQueued(eq(job.getId()),any(),any(),any())).thenAnswer(i->{job.setStatus(ScanJobStatus.RUNNING);return 1;});
        var worker=new ScanExecutionService(settings,jobs,new ObjectMapper(),provider,new QuarantineService(settings),mock(NotificationService.class),
            mock(AuditService.class),mock(ScanMetrics.class),mock(DaemonScanPolicy.class),storage);
        ReflectionTestUtils.invokeMethod(worker,"runJob",job.getId());
    }
    @Test void aCleanScanRemovesItsUploadAfterPersistingTheVerdict() throws Exception {
        var job=upload(id());run(job,ScanResult.OK.INSTANCE);
        assertThat(job.getStatus()).isEqualTo(ScanJobStatus.FINISHED);assertThat(job.getVerdict()).isEqualTo(ScanVerdict.OK);
        assertThat(Path.of(job.getStoredPath())).doesNotExist();verify(jobs,atLeastOnce()).save(job);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void infectedUploadsAreDeletedOrQuarantinedAndNeverLeftInUploads(boolean quarantine) throws Exception {
        when(settings.quarantineEnabled()).thenReturn(quarantine);var job=upload(id());
        run(job,new ScanResult.VirusFound(Map.of("sample.txt",List.of("Eicar-Test-Signature"))));
        assertThat(job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);assertThat(Path.of(job.getStoredPath())).doesNotExist();
        if(quarantine){assertThat(Path.of(job.getQuarantinePath())).exists();assertPrivate(Path.of(job.getQuarantinePath()),"rw-------");assertPrivate(settings.quarantineDir(),"rwx------");}
        else assertThat(job.getQuarantinePath()).isNull();
    }
    @Test void quarantineFailureDeletesTheInfectedUploadedCopy() throws Exception {
        when(settings.quarantineEnabled()).thenReturn(true);Files.writeString(settings.quarantineDir(),"not a directory");var job=upload(id());
        run(job,new ScanResult.VirusFound(Map.of("sample.txt",List.of("Eicar-Test-Signature"))));
        assertThat(job.getVerdict()).isEqualTo(ScanVerdict.VIRUS_FOUND);assertThat(Path.of(job.getStoredPath())).doesNotExist();
        assertThat(job.getErrorMessage()).isEqualTo(QuarantineService.UPLOAD_FAILURE);
        var publicJob=info.trizub.clamav.webclient.api.v1.ApiV1Dtos.Job.from(job);
        assertThat(publicJob.quarantineStatus()).isEqualTo("FAILED");assertThat(publicJob.error().code()).isEqualTo("QUARANTINE_FAILED");
        assertThat(publicJob.toString()).doesNotContain(folder.toString());
    }
    @Test void retainedCleanFilesExpireButActiveAndRecentlyFinishedUploadsDoNot() throws Exception {
        when(settings.deleteCleanUploads()).thenReturn(false);var clean=upload(id());run(clean,ScanResult.OK.INSTANCE);
        Path retained=Path.of(clean.getStoredPath());assertThat(retained).exists();
        Files.setLastModifiedTime(retained,FileTime.from(now.minus(Duration.ofDays(3))));clean.setFinishedAt(now.minus(Duration.ofHours(1)));
        storage.pruneExpired();assertThat(retained).exists();clean.setFinishedAt(now.minus(Duration.ofDays(2)));
        for(ScanJobStatus status:List.of(ScanJobStatus.QUEUED,ScanJobStatus.RUNNING)) {
            var active=upload(id());active.setStatus(status);Files.setLastModifiedTime(Path.of(active.getStoredPath()),FileTime.from(now.minus(Duration.ofDays(3))));
        }
        storage.pruneExpired();assertThat(retained).doesNotExist();
        try(var files=Files.list(settings.uploadDir())){assertThat(files.count()).isEqualTo(2);}
    }
    @Test void janitorRemovesExpiredOrphansButPreservesYoungUnknownAndSymlinkedFiles() throws Exception {
        Files.createDirectories(settings.uploadDir());
        Path orphan=Files.writeString(settings.uploadDir().resolve(id()+"-orphan.txt"),"orphan");
        Path young=Files.writeString(settings.uploadDir().resolve(id()+"-young.txt"),"young");
        Path unknown=Files.writeString(settings.uploadDir().resolve("keep-manual.txt"),"manual");
        Path outside=Files.writeString(folder.resolve("outside.txt"),"outside");
        Path link=Files.createSymbolicLink(settings.uploadDir().resolve(id()+"-link.txt"),outside);
        FileTime old=FileTime.from(now.minus(Duration.ofDays(3)));Files.setLastModifiedTime(orphan,old);Files.setLastModifiedTime(unknown,old);
        storage.pruneExpired();assertThat(orphan).doesNotExist();assertThat(young).exists();assertThat(unknown).exists();
        assertThat(link).isSymbolicLink();assertThat(outside).hasContent("outside");
    }
    @Test void inProgressBatchesAreProtectedEvenIfTheirFilesAreOld() throws Exception {
        try(var batch=storage.beginBatch()) {
            Path file=batch.create(id(),"slow-upload.txt");Files.writeString(file,"slow");Files.setLastModifiedTime(file,FileTime.from(now.minus(Duration.ofDays(3))));
            storage.pruneExpired();assertThat(file).exists();
        }
        try(var files=Files.list(settings.uploadDir())){assertThat(files.count()).isZero();}
    }
    @Test void cleanupRefusesPathsOutsideStorageAndDoesNotDeletePathScans() throws Exception {
        var job=upload(id());Path original=Path.of(job.getStoredPath()),outside=Files.writeString(folder.resolve(id()+"-outside.txt"),"outside");
        job.setStatus(ScanJobStatus.FINISHED);job.setVerdict(ScanVerdict.OK);job.setStoredPath(outside.toString());
        storage.cleanupFinished(job.getId());assertThat(outside).exists();
        job.setStoredPath(original.toString());job.setType(ScanJobType.PATH);storage.cleanupFinished(job.getId());assertThat(original).exists();
    }
    @Test void uploadFilesAndDirectoriesArePrivateOnPosixAndNameCollisionsPreserveExistingFiles() throws Exception {
        String id=id();var job=upload(id);Path file=Path.of(job.getStoredPath());
        assertPrivate(settings.uploadDir(),"rwx------");assertPrivate(file,"rw-------");
        try(var batch=storage.beginBatch()){assertThatThrownBy(()->batch.create(id,"sample.txt")).isInstanceOf(FileAlreadyExistsException.class);}
        assertThat(file).hasContent("fixture");
    }
    @Test void quarantineNamesDoNotGrowWithAnAlreadyMaximumLengthSourceName() throws Exception {
        when(settings.quarantineEnabled()).thenReturn(true);
        Path source=Files.writeString(folder.resolve("s".repeat(251)+".exe"),"source fixture");
        Path moved=new QuarantineService(settings).quarantine(source);
        assertThat(moved).exists();assertThat(source).doesNotExist();
        assertThat(moved.getFileName().toString().length()).isLessThan(64);assertPrivate(moved,"rw-------");
    }

    static void assertPrivate(Path path,String expected) throws Exception {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null)
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))).isEqualTo(expected);
    }
}
