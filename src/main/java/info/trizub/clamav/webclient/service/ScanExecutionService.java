package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import info.trizub.clamav.webclient.util.PathPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class ScanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScanExecutionService.class);

    private final SettingsService settings;
    private final ScanJobRepository jobRepo;
    private final ObjectMapper mapper;
    private final ClamavClientProvider clientProvider;
    private final QuarantineService quarantineService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics;
    private final DaemonScanPolicy daemonPolicy;
    private final UploadStorageService uploads;

    private ExecutorService executor;
    private final Set<String> locallyQueued = ConcurrentHashMap.newKeySet();

    public ScanExecutionService(SettingsService settings,
                               ScanJobRepository jobRepo,
                               ObjectMapper mapper,
                               ClamavClientProvider clientProvider,
                               QuarantineService quarantineService,
                               NotificationService notificationService, AuditService auditService, info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
                               DaemonScanPolicy daemonPolicy) {
        this(settings,jobRepo,mapper,clientProvider,quarantineService,notificationService,auditService,scanMetrics,daemonPolicy,
            new UploadStorageService(settings,jobRepo));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScanExecutionService(SettingsService settings, ScanJobRepository jobRepo, ObjectMapper mapper,
                               ClamavClientProvider clientProvider, QuarantineService quarantineService,
                               NotificationService notificationService, AuditService auditService,
                               info.trizub.clamav.webclient.metrics.ScanMetrics scanMetrics,
                               DaemonScanPolicy daemonPolicy, UploadStorageService uploads) {
        this.settings = settings;
        this.jobRepo = jobRepo;
        this.mapper = mapper;
        this.clientProvider = clientProvider;
        this.quarantineService = quarantineService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.scanMetrics = scanMetrics;
        this.daemonPolicy = daemonPolicy;
        this.uploads = uploads;
    }

    @PostConstruct
    public void init() {
        int threads = Math.max(1, settings.concurrentScans());
        executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("scan-worker-" + t.getId());
                    // IMPORTANT: do NOT use daemon threads for the executor.
                    // Daemon threads may be terminated abruptly depending on runtime/container lifecycle.
                    t.setDaemon(false);
                    return t;
                }
        );
        scanMetrics.bindExecutor((ThreadPoolExecutor) executor);
        log.info("Scan executor initialized with {} threads", threads);
    }

    public void enqueue(String jobId) {
        // A periodic recovery sweep must not multiply pending tasks in the in-memory queue.
        if (!locallyQueued.add(jobId)) return;
        log.debug("Enqueue scan job {}", jobId);
        try {
            executor.submit(() -> {
                try { runJob(jobId); }
                finally { locallyQueued.remove(jobId); }
            });
        } catch (RuntimeException ex) {
            locallyQueued.remove(jobId);
            throw ex;
        }
    }

    private void runJob(String jobId) {
        ScanJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Enqueued scan job {} but it does not exist in DB", jobId);
            return;
        }

        log.debug("Starting scan job {} type={} endpoint={}", jobId, job.getType(),
                job.getEndpoint() != null ? job.getEndpoint().getName() : "null");

        try {
            if (!markRunning(jobId)) {
                log.info("Job {} already claimed/completed; ignoring duplicate queue item", jobId);
                return;
            }
            log.debug("Job {} atomically claimed RUNNING", jobId);

            if (job.getEndpoint() == null || !job.getEndpoint().isEnabled())
                throw new IllegalArgumentException("Endpoint is disabled");

            ClamdClient client = clientProvider.clientFor(job.getEndpoint());

            if (job.getType() == ScanJobType.UPLOAD) {
                Path stored = Paths.get(job.getStoredPath()).toAbsolutePath().normalize();
                // Recheck queued work against refreshed configuration before any daemon command.
                daemonPolicy.requireUpload(Files.size(stored),job.getEndpoint());
                daemonPolicy.requirePath(stored,job.getEndpoint());

                if (settings.uploadPathScanEnabled()) {
                    try {
                        runNativeUploadPathScan(job, stored, client);
                    } catch (Exception pathScanError) {
                        String message = "Path-based upload scan failed for " + stored + ". "
                                + "Native clamdtop can show filenames only when clamd receives a SCAN/MULTISCAN path "
                                + "and the clamd process can read that exact path. Configure a secure read-only "
                                + "upload-only mount with matching file ownership, or enable INSTREAM fallback "
                                + "to keep old INSTREAM behavior. Details: " + pathScanError.getMessage();

                        if (!settings.uploadPathScanFallbackToInstream()) {
                            log.warn(message, pathScanError);
                            finishError(jobId, message);
                            notifyIfNeeded(jobId);
                            return;
                        }

                        log.warn("{} Falling back to INSTREAM because fallback is enabled.", message, pathScanError);
                        ScanResult result;
                        try (InputStream in = Files.newInputStream(stored)) {result=client.scan(in);}
                        handleResult(jobId, job.getType(), result, stored);
                    }
                } else {
                    ScanResult result;
                    try (InputStream in = Files.newInputStream(stored)) {result=client.scan(in);}
                    handleResult(jobId, job.getType(), result, stored);
                }
            } else if (job.getType() == ScanJobType.PATH || job.getType() == ScanJobType.WATCH) {
                Path submitted = Paths.get(job.getTarget());
                Path target = PathPolicy.requireAllowed(submitted, settings.allowedRoots());
                if (!submitted.isAbsolute() || !submitted.equals(target))
                    throw new IllegalArgumentException("Scan path changed after submission; submit a new scan");
                ScanResult result = daemonPolicy.scanPath(client,job.getEndpoint(),target);
                handleResult(jobId, job.getType(), result, null);
            } else {
                finishError(jobId, "Unsupported job type: " + job.getType());
                log.debug("Job {} finished ERROR (unsupported type)", jobId);
            }

            // Re-read to report final persisted state
            ScanJob finished = jobRepo.findById(jobId).orElse(null);
            if (finished != null) {
                log.debug("Finished scan job {} status={} verdict={}", jobId, finished.getStatus(), finished.getVerdict());
            }
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            try {
                finishError(jobId, e.getMessage());
                log.debug("Job {} finished ERROR: {}", jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("Job {} failed to persist ERROR state", jobId, inner);
            }
            notifyIfNeeded(jobId);
        } finally {uploads.cleanupFinished(jobId);}
    }

    @SuppressWarnings("unchecked")
    private void handleResult(String jobId, ScanJobType type, ScanResult result, Path uploadedFile) {
        if (result instanceof ScanResult.OK) {
            finishOk(jobId);
            notifyIfNeeded(jobId);
        } else if (result instanceof ScanResult.VirusFound) {
            ScanResult.VirusFound vf = (ScanResult.VirusFound) result;
            Map<String, Collection<String>> found = vf.getFoundViruses();

            Path quarantinePath=null;
            String quarantineWarning=null;
            if (uploadedFile != null) {
                var outcome=quarantineService.attempt(uploadedFile,jobId);
                quarantinePath=outcome.path();
                if(outcome.failed()) quarantineWarning=QuarantineService.UPLOAD_FAILURE;
            } else if (found != null) {
                for (String p : found.keySet()) {
                    try {
                        var outcome=quarantineService.attempt(Paths.get(p),null);
                        if(outcome.failed()) quarantineWarning=QuarantineService.SOURCE_FAILURE;
                    } catch (Exception ex) {quarantineWarning=QuarantineService.SOURCE_FAILURE;}
                }
            }

            finishFound(jobId,found,quarantinePath,quarantineWarning);
            log.debug("Job {} finished VIRUS_FOUND", jobId);
            notifyIfNeeded(jobId);
        } else {
            finishError(jobId, "Unknown scan result type: " + result);
            log.debug("Job {} finished ERROR (unknown result)", jobId);
            notifyIfNeeded(jobId);
        }
    }

    /**
     * Run uploaded files through clamd's native MULTISCAN command using the file path.
     *
     * This is what native clamdtop needs in order to display the filename. INSTREAM
     * sends bytes over TCP and clamd only sees a stream connection, so clamdtop can
     * only display instream(client-ip@port). MULTISCAN sends a real filesystem path,
     * so clamdtop can display MULTISCANFILE /app/data/uploads/original-name.
     */
    private void runNativeUploadPathScan(ScanJob job, Path stored, ClamdClient client) throws IOException {
        ClamdEndpoint endpoint = job.getEndpoint();
        if (endpoint == null) {
            throw new IOException("No clamd endpoint configured for job " + job.getId());
        }
        if (!Files.isRegularFile(stored)) {
            throw new IOException("Upload file is missing in the web container: " + stored);
        }

        handleResult(job.getId(), job.getType(), client.parallelScan(stored), stored);
        log.debug("Job {} completed native MULTISCAN through the bounded ClamD client", job.getId());
    }

    private void notifyIfNeeded(String jobId) {
        try {
            notificationService.notifyIfNeeded(jobRepo.findById(jobId).orElseThrow());
        } catch (Exception ignored) {
        }
    }

    public boolean markRunning(String id) {
        return jobRepo.claimQueued(id, ScanJobStatus.QUEUED,
                ScanJobStatus.RUNNING, Instant.now()) == 1;
    }

    @Transactional
    public void finishOk(String id) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        if (job.getStatus() == ScanJobStatus.FINISHED) return;
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.OK);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
        auditResultSafely(job);
    }

    @Transactional
    public void finishFound(String id, Object foundViruses) {
        finishFound(id,foundViruses,null,null);
    }

    private void finishFound(String id,Object foundViruses,Path quarantinePath,String quarantineWarning) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        if (job.getStatus() == ScanJobStatus.FINISHED) return;
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        if(quarantinePath!=null) job.setQuarantinePath(quarantinePath.toString());
        if(quarantineWarning!=null) job.setErrorMessage(quarantineWarning);
        try {
            job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
        } catch (Exception e) {
            job.setFoundVirusesJson(String.valueOf(foundViruses));
        }
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
        auditResultSafely(job);
    }

    @Transactional
    public void finishError(String id, String message) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        if (job.getStatus() == ScanJobStatus.FINISHED) return;
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
        auditResultSafely(job);
    }

    private void auditResultSafely(ScanJob job) {
        scanMetrics.recordCompletion(job);
        try { auditService.recordResult(job); }
        catch (Exception e) { log.error("Unable to audit scan result for job {}", job.getId(), e); }
    }

    @Transactional
    public void setQuarantinePath(String id, String quarantinePath) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setQuarantinePath(quarantinePath);
        jobRepo.save(job);
    }

}
