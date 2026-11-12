package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class ScanJobService {

    private static final Logger log = LoggerFactory.getLogger(ScanJobService.class);

    private final ScanJobRepository repo;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final ScanExecutionService executor;
    private final DaemonScanPolicy daemonPolicy;
    private final UploadStorageService uploads;
    private final UploadJobCommitter uploadCommitter;

    public ScanJobService(ScanJobRepository repo, SettingsService settings, ObjectMapper mapper, ScanExecutionService executor,
                          DaemonScanPolicy daemonPolicy) {
        this(repo,settings,mapper,executor,daemonPolicy,new UploadStorageService(settings,repo));
    }

    public ScanJobService(ScanJobRepository repo, SettingsService settings, ObjectMapper mapper, ScanExecutionService executor,
                          DaemonScanPolicy daemonPolicy, UploadStorageService uploads) {
        this(repo,settings,mapper,executor,daemonPolicy,uploads,new UploadJobCommitter(repo,executor));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScanJobService(ScanJobRepository repo, SettingsService settings, ObjectMapper mapper, ScanExecutionService executor,
                          DaemonScanPolicy daemonPolicy, UploadStorageService uploads, UploadJobCommitter uploadCommitter) {
        this.repo = repo;
        this.settings = settings;
        this.mapper = mapper;
        this.executor = executor;
        this.daemonPolicy = daemonPolicy;
        this.uploads = uploads;
        this.uploadCommitter = uploadCommitter;
    }

    @PostConstruct
    public void resumeQueued() {
        // If app restarts, re-enqueue queued/running jobs as queued
        repo.findAll().stream()
                .filter(j -> j.getStatus() != ScanJobStatus.FINISHED)
                .forEach(j -> {
                    j.setStatus(ScanJobStatus.QUEUED);
                    repo.save(j);
                    executor.enqueue(j.getId());
                });
    }

    /** After a transient DB outage, recover QUEUED work that was persisted but not enqueued.
     * CAS claiming in ScanExecutionService prevents duplicate execution if already taken.
     * RUNNING jobs require an operator-controlled single-instance restart; not HA recovery.
     */
    @Scheduled(fixedDelayString = "${app.scan.reconcile-interval-ms:60000}")
    public void reconcileQueued() {
        try {
            for (ScanJob j : repo.findTop100ByStatusOrderBySubmittedAtAsc(ScanJobStatus.QUEUED)) {
                executor.enqueue(j.getId());
            }
        } catch (Exception ex) {
            log.warn("Scan queue reconciliation deferred while database unavailable: {}", ex.getMessage());
        }
    }

    public List<ScanJob> latest() {
        return repo.findTop200ByOrderBySubmittedAtDesc();
    }

    public long uploadMaxBytes(ClamdEndpoint endpoint) { return daemonPolicy.uploadMaxBytes(endpoint); }

    /**
     * Fetch a job by id.
     *
     * NOTE: The web UI should not 500 if a job id is missing (e.g. stale link),
     * so this method returns null when not found.
     */
    public ScanJob getOrNull(String id) {
        return repo.findById(id).orElse(null);
    }

    public List<ScanJob> createUploadJobs(List<MultipartFile> files, ClamdEndpoint endpoint, String username) {
        return createUploadJobs(files, endpoint, username, "BROWSER");
    }

    public List<ScanJob> createUploadJobs(List<MultipartFile> files, ClamdEndpoint endpoint, String username, String transport) {
        // Avoid even an empty transaction synchronization scope around slow input streams.
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new org.springframework.transaction.IllegalTransactionStateException("Upload copying must be called outside an active database transaction");
        requireEnabled(endpoint);
        List<ScanJob> jobs = new ArrayList<>();
        record PendingUpload(MultipartFile file, String id, String name, String label, Path stored) {}
        List<PendingUpload> pending = new ArrayList<>();
        // Validate the entire batch before copying files or queuing any job.
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            daemonPolicy.requireUpload(file.getSize(),endpoint);
            String id = UUID.randomUUID().toString().replace("-", "");
            String name = info.trizub.clamav.webclient.util.UploadNames.stored(file.getOriginalFilename());
            Path stored = settings.uploadDir().resolve(id + "-" + name);
            daemonPolicy.requireNotExcluded(stored,endpoint);
            pending.add(new PendingUpload(file,id,name,info.trizub.clamav.webclient.util.UploadNames.display(file.getOriginalFilename()),stored));
        }
        try (var batch=uploads.beginBatch()) {
        for (PendingUpload upload : pending) {
            MultipartFile f = upload.file();
            String id = upload.id();
            String safeName = upload.name();
            Path stored = upload.stored();
            try {
                Path created=batch.create(id,safeName);
                if(!created.equals(stored)) throw new IllegalStateException("Upload storage changed; retry the batch");

                // Copy and hash in one pass, without a database transaction or connection.
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                long copied;
                try (InputStream in = new java.security.DigestInputStream(f.getInputStream(),digest);
                     var output=Files.newOutputStream(stored,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    copied=in.transferTo(output);
                }
                daemonPolicy.requireUpload(copied,endpoint);
                String sha=java.util.HexFormat.of().formatHex(digest.digest());

                ScanJob job = new ScanJob();
                job.setId(id);
                job.setType(ScanJobType.UPLOAD);
                job.setStatus(ScanJobStatus.QUEUED);
                job.setTarget(upload.label());
                job.setStoredPath(stored.toString());
                job.setSha256(sha);
                job.setSizeBytes(copied);
                job.setEndpoint(endpoint);
                job.setSubmittedBy(username);
                job.setSubmittedTransport(transport);
                job.setSubmittedAt(Instant.now());
                jobs.add(job);
                } catch (Exception e) {
                log.error("Failed to create upload job: {}", e.getMessage(), e);
                throw new IllegalStateException("Unable to create upload scan job", e);
            }
        }
        if(jobs.isEmpty()){batch.succeeded();return jobs;}
        List<ScanJob> committed=uploadCommitter.commit(jobs,batch);
        batch.succeeded();return committed;
        }
    }

    public ScanJob createPathJob(String path, ClamdEndpoint endpoint, String username) {
        return createPathJob(path, endpoint, username, "BROWSER");
    }

    @Transactional
    public ScanJob createPathJob(String path, ClamdEndpoint endpoint, String username, String transport) {
        requireEnabled(endpoint);
        Path normalized = PathPolicy.normalize(path);
        Path requested = PathPolicy.requireAllowed(normalized, settings.allowedRoots());
        daemonPolicy.requireNotExcluded(normalized,endpoint);
        return pathJob(requested, endpoint, username, transport);
    }

    /** The v1 bearer access check resolved this target; never re-resolve its submitted alias. */
    @Transactional
    public ScanJob createPathJob(Path canonical, ClamdEndpoint endpoint, String username, String transport) {
        requireEnabled(endpoint);
        return pathJob(PathPolicy.requireCanonicalAllowed(canonical, settings.allowedRoots()), endpoint, username, transport);
    }

    private ScanJob pathJob(Path requested, ClamdEndpoint endpoint, String username, String transport) {
        daemonPolicy.requirePath(requested,endpoint);

        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.PATH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(requested.toString());
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username);
        job.setSubmittedTransport(transport);
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
                return job;
    }

    @Transactional
    public ScanJob createWatchFileJob(Path file, ClamdEndpoint endpoint, String username) {
        requireEnabled(endpoint);
        Path requested = PathPolicy.requireAllowed(file,settings.allowedRoots());
        daemonPolicy.requirePath(requested,endpoint);
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.WATCH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(requested.toString());
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username != null ? username : "watcher");
        job.setSubmittedTransport("WATCH");
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
                return job;
    }

    
private static void requireEnabled(ClamdEndpoint endpoint) {
    if (endpoint == null || !endpoint.isEnabled()) throw new IllegalArgumentException("Endpoint is disabled");
}

private void enqueueAfterCommit(String jobId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.enqueue(jobId);
            }
        });
    } else {
        executor.enqueue(jobId);
    }
}

@Transactional
    public void finishOk(String id) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.OK);
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void finishFound(String id, Object foundViruses) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        try {
            job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
        } catch (Exception e) {
            job.setFoundVirusesJson(String.valueOf(foundViruses));
        }
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void finishError(String id, String message) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void markRunning(String id) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void setQuarantinePath(String id, String quarantinePath) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setQuarantinePath(quarantinePath);
        repo.save(job);
    }
}
