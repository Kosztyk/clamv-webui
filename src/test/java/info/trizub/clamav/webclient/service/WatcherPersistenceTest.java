package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={
    "spring.datasource.url=jdbc:h2:mem:watcher-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({ScanJobService.class,WatchFileSubmissionService.class,UploadStorageService.class,UploadJobCommitter.class,WatcherPersistenceTest.JsonConfiguration.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class WatcherPersistenceTest {
    @TestConfiguration static class JsonConfiguration { @Bean ObjectMapper json(){return new ObjectMapper();} }
    @TempDir Path folder;
    @Autowired ProcessedFileRepository processed;
    @Autowired ScanJobRepository jobRepo;
    @Autowired WatchedDirectoryRepository watchRepo;
    @Autowired ClamdEndpointRepository endpoints;
    @Autowired WatchFileSubmissionService submissions;
    @MockitoSpyBean ScanJobService jobs;
    @MockitoBean SettingsService settings;
    @MockitoBean ScanExecutionService executor;
    @MockitoBean DaemonScanPolicy policy;
    ClamdEndpoint endpoint;
    WatcherService watcher;

    @BeforeEach void prepare() {
        watchRepo.deleteAll();jobRepo.deleteAll();processed.deleteAll();endpoints.deleteAll();
        endpoint=endpoints.save(new ClamdEndpoint("watch-test","localhost",3310,xyz.capybara.clamav.Platform.UNIX));
        watchRepo.save(new WatchedDirectory(folder.toString(),endpoint));
        when(settings.watchEnabled()).thenReturn(true);when(settings.watchPollSeconds()).thenReturn(30);
        when(settings.allowedRoots()).thenReturn(List.of(folder));
        watcher=new WatcherService(settings,watchRepo,processed,submissions,Clock.fixed(Instant.ofEpochMilli(1_000_000),ZoneOffset.UTC));
        clearInvocations(executor,jobs);
    }

    @Test void directoryWith2500FilesCreates2500DurableJobsAndDoesNotRepeatUnchangedFiles() throws Exception {
        for(int n=0;n<2500;n++) Files.writeString(folder.resolve(String.format("file-%04d.txt",n)),"fixture");
        watcher.poll();
        assertThat(jobRepo.count()).isEqualTo(2500);assertThat(processed.count()).isEqualTo(2500);
        assertThat(jobRepo.findAll()).allSatisfy(job->{assertThat(job.getType()).isEqualTo(ScanJobType.WATCH);assertThat(job.getStatus()).isEqualTo(ScanJobStatus.QUEUED);});
        assertThat(jobRepo.findAll().stream().map(ScanJob::getTarget)).doesNotHaveDuplicates().hasSize(2500);
        verify(executor,times(2500)).enqueue(anyString());
        watcher.start();watcher.poll();
        assertThat(jobRepo.count()).isEqualTo(2500);verify(executor,times(2500)).enqueue(anyString());
        verify(jobs,times(2500)).createWatchFileJob(any(),any(),eq("watcher"));
    }

    @Test void createFailureRollsBackTheJobAndLeavesANewFileEligibleForTheNextPoll() throws Exception {
        Path file=Files.writeString(folder.resolve("retry.txt"),"fixture");var fail=new AtomicBoolean(true);
        doAnswer(invocation->{Object result=invocation.callRealMethod();if(fail.getAndSet(false))throw new IllegalStateException("synthetic failure after insert");return result;})
            .when(jobs).createWatchFileJob(eq(file),any(ClamdEndpoint.class),eq("watcher"));
        watcher.poll();assertThat(jobRepo.count()).isZero();assertThat(processed.count()).isZero();verifyNoInteractions(executor);
        watcher.start();watcher.poll();assertThat(jobRepo.count()).isEqualTo(1);assertThat(processed.count()).isEqualTo(1);
        verify(executor).enqueue(anyString());
    }

    @Test void failedChangedFileRetainsItsOldFingerprintAndRetriesWithoutTouchingTheFileAgain() throws Exception {
        Path file=Files.writeString(folder.resolve("changed.txt"),"old");watcher.poll();
        ProcessedFile before=processed.findByPath(file.toString()).orElseThrow();
        Files.writeString(file,"new and longer");var fail=new AtomicBoolean(true);
        doAnswer(invocation->{Object result=invocation.callRealMethod();if(fail.getAndSet(false))throw new IllegalStateException("synthetic failure");return result;})
            .when(jobs).createWatchFileJob(eq(file),any(ClamdEndpoint.class),eq("watcher"));
        watcher.start();watcher.poll();
        ProcessedFile failed=processed.findByPath(file.toString()).orElseThrow();
        assertThat(failed.getSizeBytes()).isEqualTo(before.getSizeBytes());assertThat(failed.getLastModified()).isEqualTo(before.getLastModified());
        assertThat(jobRepo.count()).isEqualTo(1);
        watcher.start();watcher.poll();
        assertThat(jobRepo.count()).isEqualTo(2);assertThat(processed.count()).isEqualTo(1);
        assertThat(processed.findByPath(file.toString()).orElseThrow().getSizeBytes()).isEqualTo(Files.size(file));
        verify(executor,times(2)).enqueue(anyString());
    }

    @Test void markerWriteFailureRollsBackItsJobAndNoWorkerSeesUncommittedWork() throws Exception {
        Path file=Files.writeString(folder.resolve("marker-write.txt"),"fixture");
        ProcessedFile existing=processed.saveAndFlush(new ProcessedFile(file.toString(),1,1,null));
        // A duplicate path forces a real database write failure after the job insert.
        assertThatThrownBy(()->submissions.submit(file,endpoint,null,2,2)).isInstanceOf(RuntimeException.class);
        assertThat(jobRepo.count()).isZero();assertThat(processed.count()).isEqualTo(1);verifyNoInteractions(executor);
        assertThat(processed.findById(existing.getId()).orElseThrow().getSizeBytes()).isEqualTo(1);
        watcher.poll();assertThat(jobRepo.count()).isEqualTo(1);verify(executor).enqueue(anyString());
    }

    @Test void postCommitQueueFailureRetainsRecoverableDurableWorkRatherThanLosingTheFile() throws Exception {
        Files.writeString(folder.resolve("queue-retry.txt"),"fixture");
        doThrow(new RejectedExecutionException("synthetic queue unavailable")).when(executor).enqueue(anyString());
        watcher.poll();assertThat(jobRepo.count()).isEqualTo(1);assertThat(processed.count()).isEqualTo(1);
        assertThat(jobRepo.findAll().get(0).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
        doNothing().when(executor).enqueue(anyString());jobs.reconcileQueued();
        verify(executor,times(2)).enqueue(anyString());
        watcher.start();watcher.poll();assertThat(jobRepo.count()).isEqualTo(1);
    }

    @Test void bulkLookupEscapesWildcardCharactersAndExcludesSiblingDirectoryPrefixes() throws Exception {
        Path root=Files.createDirectories(folder.resolve("literal%_root"));
        Path child=Files.createDirectories(root.resolve("nested")).resolve("child.txt");
        var exact=processed.saveAndFlush(new ProcessedFile(root.resolve("one.txt").toString(),1,1,null));
        var nested=processed.saveAndFlush(new ProcessedFile(child.toString(),1,1,null));
        processed.saveAndFlush(new ProcessedFile(folder.resolve("literalAAroot/other.txt").toString(),1,1,null));
        processed.saveAndFlush(new ProcessedFile(folder.resolve("literal%_root-sibling/other.txt").toString(),1,1,null));
        assertThat(processed.findByPathStartingWith(root+root.getFileSystem().getSeparator())).extracting(ProcessedFile::getId)
            .containsExactlyInAnyOrder(exact.getId(),nested.getId());
    }

    @Test void hourlyPruningTraversesAllBatchesWithoutDeletingExistingFiles() throws Exception {
        List<ProcessedFile> markers=new ArrayList<>();
        for(int n=0;n<1205;n++) {
            Path file=folder.resolve("old-"+n+".txt");if(n%250==0)Files.writeString(file,"keep");
            markers.add(new ProcessedFile(file.toString(),1,1,null));
        }
        processed.saveAllAndFlush(markers);watcher.pruneDeletedMarkers();
        assertThat(processed.count()).isEqualTo(5);
        assertThat(processed.findAll()).allSatisfy(marker->assertThat(Path.of(marker.getPath())).exists());
        watcher.pruneDeletedMarkers();assertThat(processed.count()).isEqualTo(5);
    }
}
