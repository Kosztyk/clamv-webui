package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.datasource.url=jdbc:h2:mem:upload-batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=1","spring.datasource.hikari.connection-timeout=1000",
    // Flyway needs two migration connections; keep them separate from the single runtime connection being tested.
    "spring.flyway.url=jdbc:h2:mem:upload-batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.flyway.user=sa","spring.flyway.password="})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({ScanJobService.class,UploadStorageService.class,UploadJobCommitter.class,UploadBatchPersistenceTest.JsonConfiguration.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class UploadBatchPersistenceTest {
    @TestConfiguration static class JsonConfiguration {@Bean ObjectMapper json(){return new ObjectMapper();}}
    @TempDir Path folder;
    @Autowired ScanJobService service;
    @Autowired UploadStorageService uploads;
    @Autowired ScanJobRepository jobs;
    @Autowired AuditEventRepository audits;
    @Autowired ClamdEndpointRepository endpoints;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean SettingsService settings;
    @MockitoBean ScanExecutionService executor;
    @MockitoBean DaemonScanPolicy policy;
    ClamdEndpoint endpoint;
    @BeforeEach void prepare() {
        jobs.deleteAll();audits.deleteAll();endpoints.deleteAll();
        endpoint=endpoints.save(new ClamdEndpoint("uploads","localhost",3310,xyz.capybara.clamav.Platform.UNIX));
        when(settings.uploadDir()).thenReturn(folder.resolve("uploads"));when(settings.uploadRetentionHours()).thenReturn(24L);
        clearInvocations(executor);
    }
    MockMultipartFile good(){return new MockMultipartFile("files","good.txt","text/plain",new byte[]{1,2,3});}
    void emptyUploads() throws Exception {try(var files=Files.list(settings.uploadDir())){assertThat(files.count()).isZero();}}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void failedMultiFileBatchRollsBackRowsAndRemovesCompleteAndPartialFilesForBothPublicOverloads(boolean browser) throws Exception {
        var broken=new MockMultipartFile("files","broken.txt","text/plain",new byte[]{4}) {
            @Override public InputStream getInputStream() {return new InputStream(){int n;public int read() throws IOException {if(n++==0)return 4;throw new IOException("synthetic copy failure");}};}
        };
        assertThatThrownBy(()->{if(browser)service.createUploadJobs(List.of(good(),broken),endpoint,"admin");
            else service.createUploadJobs(List.of(good(),broken),endpoint,"admin","API_TOKEN");}).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.count()).isZero();emptyUploads();verifyNoInteractions(executor);
    }
    @Test void aDatabaseFailureAtCommitAlsoRemovesAlreadyWrittenFiles() throws Exception {
        // Column length violation is raised by the real database when the transaction flushes.
        assertThatThrownBy(()->service.createUploadJobs(List.of(good()),endpoint,"x".repeat(100))).isInstanceOf(RuntimeException.class);
        assertThat(jobs.count()).isZero();emptyUploads();verifyNoInteractions(executor);
    }
    @Test void successfulCommitKeepsQueuedFilesAndAnAfterCommitQueueFailureDoesNotDeleteThem() throws Exception {
        doThrow(new java.util.concurrent.RejectedExecutionException("synthetic queue failure")).when(executor).enqueue(anyString());
        assertThatThrownBy(()->service.createUploadJobs(List.of(good()),endpoint,"admin")).isInstanceOf(RuntimeException.class);
        assertThat(jobs.count()).isEqualTo(1);var job=jobs.findAll().get(0);assertThat(job.getStatus()).isEqualTo(ScanJobStatus.QUEUED);
        assertThat(Path.of(job.getStoredPath())).exists();uploads.pruneExpired();assertThat(Path.of(job.getStoredPath())).exists();
    }
    ScanJob oldJob(String id,ScanJobStatus status,Instant time) {
        var job=new ScanJob();job.setId(id);job.setType(ScanJobType.UPLOAD);job.setStatus(status);job.setTarget("sample");
        if(status==ScanJobStatus.FINISHED){job.setFinishedAt(time);job.setVerdict(ScanVerdict.OK);}return job;
    }
    @Test void optionalHistoryRetentionPrunesAllOldBatchesAndKeepsActiveRecentAndQuarantinedJobs() throws Exception {
        Instant now=Instant.parse("2026-11-10T12:00:00Z"),old=now.minus(Duration.ofDays(10));
        when(settings.jobRetentionDays()).thenReturn(2L);when(settings.auditRetentionDays()).thenReturn(2L);
        var historical=new ArrayList<ScanJob>();
        for(int n=0;n<605;n++)historical.add(oldJob(String.format("old-%04d",n),ScanJobStatus.FINISHED,old));
        historical.add(oldJob("queued",ScanJobStatus.QUEUED,old));historical.add(oldJob("running",ScanJobStatus.RUNNING,old));
        historical.add(oldJob("recent",ScanJobStatus.FINISHED,now.minus(Duration.ofHours(1))));
        var quarantine=oldJob("quarantine",ScanJobStatus.FINISHED,old);quarantine.setQuarantinePath(folder.resolve("quarantined-sample").toString());historical.add(quarantine);
        Path retained=Files.writeString(folder.resolve("retained-upload"),"keep");var artifact=oldJob("artifact",ScanJobStatus.FINISHED,old);artifact.setStoredPath(retained.toString());historical.add(artifact);
        jobs.saveAllAndFlush(historical);
        var history=new ArrayList<AuditEvent>();for(int n=0;n<605;n++){var event=new AuditEvent("admin","SCAN_UPLOAD","fixture","SUCCESS",null,"127.0.0.1",null);ReflectionTestUtils.setField(event,"at",old);history.add(event);}
        var recent=new AuditEvent("admin","LOGIN","recent","SUCCESS",null,"127.0.0.1",null);ReflectionTestUtils.setField(recent,"at",now);history.add(recent);audits.saveAllAndFlush(history);
        new StorageRetentionService(settings,uploads,jobs,audits,Clock.fixed(now,ZoneOffset.UTC)).clean();
        assertThat(jobs.findAll()).extracting(ScanJob::getId).containsExactlyInAnyOrder("queued","running","recent","quarantine","artifact");
        assertThat(audits.count()).isEqualTo(1);assertThat(retained).exists();
    }
    @Test void historyPruningIsDisabledByDefault() {
        jobs.saveAndFlush(oldJob("history",ScanJobStatus.FINISHED,Instant.EPOCH));
        var event=new AuditEvent("admin","LOGIN","keep","SUCCESS",null,"127.0.0.1",null);ReflectionTestUtils.setField(event,"at",Instant.EPOCH);audits.saveAndFlush(event);
        new StorageRetentionService(settings,uploads,jobs,audits).clean();assertThat(jobs.count()).isEqualTo(1);assertThat(audits.count()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"ascii","unicode"})
    void a400CharacterFilenamePersistsItsDisplayNameAndUsesABoundedPrivateStorageName(String kind) throws Exception {
        String name=(kind.equals("ascii")?"a":"ș").repeat(396)+".exe";
        var result=service.createUploadJobs(List.of(new MockMultipartFile("files",name,"application/octet-stream",new byte[]{1,2,3})),endpoint,"admin").get(0);
        ScanJob saved=jobs.findById(result.getId()).orElseThrow();Path path=Path.of(saved.getStoredPath());
        assertThat(saved.getTarget()).isEqualTo(name);assertThat(path.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(200);
        assertThat(path.getFileName().toString()).endsWith(".exe");assertThat(Files.readAllBytes(path)).containsExactly(1,2,3);
        assertThat(saved.getSha256()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3})));
    }

    @Test void threeCopiesLeaveASingleConnectionPoolAvailableForOtherQueries() throws Exception {
        var pool=dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        // Real pre-copy SQL must release its connection too, even when reached through a validation dependency.
        doAnswer(invocation->{new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT 1",Integer.class);return null;})
            .when(policy).requireNotExcluded(any(),any());
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var observed=new ConcurrentLinkedQueue<Boolean>();
        var workers=Executors.newFixedThreadPool(4);List<Future<List<ScanJob>>> pending=new ArrayList<>();
        try {
            for(int n=0;n<3;n++) {
                var file=new MockMultipartFile("files","slow-"+n+".txt","application/octet-stream",new byte[8192]) {
                    @Override public InputStream getInputStream() throws IOException {
                        return new FilterInputStream(new ByteArrayInputStream(getBytes())) {
                            int reads;
                            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                                if(reads++==1) {
                                    observed.add(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();
                                    try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("copy release timed out");}
                                    catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
                                }
                                return super.read(bytes,offset,Math.min(length,1024));
                            }
                        };
                    }
                };
                pending.add(workers.submit(()->service.createUploadJobs(List.of(file),endpoint,"admin")));
            }
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(observed).containsExactly(false,false,false);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            // A metadata/login-style query can borrow the only connection while all copies are blocked mid-stream.
            assertThat(workers.submit(()->new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT 1",Integer.class)).get(2,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jobs.count()).isZero();release.countDown();
            for(var future:pending)assertThat(future.get(10,TimeUnit.SECONDS)).hasSize(1);
            assertThat(jobs.count()).isEqualTo(3);verify(executor,times(3)).enqueue(anyString());
        } finally {release.countDown();workers.shutdown();if(!workers.awaitTermination(10,TimeUnit.SECONDS))workers.shutdownNow();}
    }

    @Test void anAmbientTransactionIsRejectedBeforeAnyCopyCanHoldItsConnection() {
        var file=spy(good());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            assertThatThrownBy(()->service.createUploadJobs(List.of(file),endpoint,"admin"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        });
        try {verify(file,never()).getInputStream();}catch(IOException ex){throw new AssertionError(ex);}
        assertThat(jobs.count()).isZero();
    }
}
