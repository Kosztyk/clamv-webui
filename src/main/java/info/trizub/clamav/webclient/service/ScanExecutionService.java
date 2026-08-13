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
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class ScanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScanExecutionService.class);
    private static final int CLAMD_CONNECT_TIMEOUT_MS = 10_000;
    private static final int CLAMD_READ_TIMEOUT_MS = (int) TimeUnit.HOURS.toMillis(24);

    private final SettingsService settings;
    private final ScanJobRepository jobRepo;
    private final ObjectMapper mapper;
    private final ClamavClientProvider clientProvider;
    private final QuarantineService quarantineService;
    private final NotificationService notificationService;

    private ExecutorService executor;

    public ScanExecutionService(SettingsService settings,
                               ScanJobRepository jobRepo,
                               ObjectMapper mapper,
                               ClamavClientProvider clientProvider,
                               QuarantineService quarantineService,
                               NotificationService notificationService) {
        this.settings = settings;
        this.jobRepo = jobRepo;
        this.mapper = mapper;
        this.clientProvider = clientProvider;
        this.quarantineService = quarantineService;
        this.notificationService = notificationService;
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
        log.info("Scan executor initialized with {} threads", threads);
    }

    public void enqueue(String jobId) {
        log.debug("Enqueue scan job {}", jobId);
        executor.submit(() -> runJob(jobId));
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
            markRunning(jobId);
            log.debug("Job {} marked RUNNING", jobId);

            ClamavClient client = clientProvider.clientFor(job.getEndpoint());

            if (job.getType() == ScanJobType.UPLOAD) {
                Path stored = Paths.get(job.getStoredPath()).toAbsolutePath().normalize();

                if (settings.uploadPathScanEnabled()) {
                    try {
                        runNativeUploadPathScan(job, stored);
                    } catch (Exception pathScanError) {
                        String message = "Path-based upload scan failed for " + stored + ". "
                                + "Native clamdtop can show filenames only when clamd receives a SCAN/MULTISCAN path "
                                + "and the clamd container can read that exact path. Mount the upload directory into the "
                                + "clamd container at the same path, or enable app.upload.pathScan.fallbackToInstream=true "
                                + "to keep old INSTREAM behavior. Details: " + pathScanError.getMessage();

                        if (!settings.uploadPathScanFallbackToInstream()) {
                            log.warn(message, pathScanError);
                            finishError(jobId, message);
                            notifyIfNeeded(jobId);
                            return;
                        }

                        log.warn("{} Falling back to INSTREAM because fallback is enabled.", message, pathScanError);
                        try (InputStream in = Files.newInputStream(stored)) {
                            ScanResult result = client.scan(in);
                            handleResult(jobId, job.getType(), result, stored);
                        }
                    }
                } else {
                    try (InputStream in = Files.newInputStream(stored)) {
                        ScanResult result = client.scan(in);
                        handleResult(jobId, job.getType(), result, stored);
                    }
                }
            } else if (job.getType() == ScanJobType.PATH || job.getType() == ScanJobType.WATCH) {
                Path target = Paths.get(job.getTarget());
                ScanResult result = client.parallelScan(target);
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
        }
    }

    @SuppressWarnings("unchecked")
    private void handleResult(String jobId, ScanJobType type, ScanResult result, Path uploadedFile) {
        if (result instanceof ScanResult.OK) {
            finishOk(jobId);
        } else if (result instanceof ScanResult.VirusFound) {
            ScanResult.VirusFound vf = (ScanResult.VirusFound) result;
            Map<String, Collection<String>> found = vf.getFoundViruses();

            // Quarantine logic
            if (uploadedFile != null) {
                Path q = quarantineService.quarantine(uploadedFile);
                if (q != null) setQuarantinePath(jobId, q.toString());
            } else if (found != null) {
                for (String p : found.keySet()) {
                    try {
                        Path file = Paths.get(p);
                        if (Files.isRegularFile(file)) {
                            quarantineService.quarantine(file);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            finishFound(jobId, found);
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
    private void runNativeUploadPathScan(ScanJob job, Path stored) throws IOException {
        ClamdEndpoint endpoint = job.getEndpoint();
        if (endpoint == null) {
            throw new IOException("No clamd endpoint configured for job " + job.getId());
        }
        if (!Files.isRegularFile(stored)) {
            throw new IOException("Upload file is missing in the web container: " + stored);
        }

        String response = sendClamdPathCommand(endpoint, "MULTISCAN", stored.toString());
        NativePathScanResult parsed = parseNativePathScanResponse(response);

        if (!parsed.errors().isEmpty()) {
            throw new IOException("clamd rejected path scan: " + String.join(" | ", parsed.errors())
                    + "; raw response: " + abbreviate(response, 2000));
        }

        if (!parsed.found().isEmpty()) {
            Path q = quarantineService.quarantine(stored);
            if (q != null) {
                setQuarantinePath(job.getId(), q.toString());
            }
            finishFound(job.getId(), parsed.found());
            log.debug("Job {} finished VIRUS_FOUND through direct native MULTISCAN path scan", job.getId());
            notifyIfNeeded(job.getId());
            return;
        }

        finishOk(job.getId());
        log.debug("Job {} finished OK through direct native MULTISCAN path scan", job.getId());
    }

    private String sendClamdPathCommand(ClamdEndpoint endpoint, String command, String path) throws IOException {
        String host = Optional.ofNullable(endpoint.getHost()).orElse("localhost").trim();
        if (host.isBlank()) {
            host = "localhost";
        }

        String wireCommand = "n" + command + " " + path + "\n";

        if (host.startsWith("/")) {
            try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(host))) {
                channel.write(ByteBuffer.wrap(wireCommand.getBytes(StandardCharsets.UTF_8)));
                channel.shutdownOutput();
                try (InputStream input = Channels.newInputStream(channel)) {
                    return readAll(input);
                }
            }
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, endpoint.getPort()), CLAMD_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CLAMD_READ_TIMEOUT_MS);
            try (OutputStream output = socket.getOutputStream(); InputStream input = socket.getInputStream()) {
                output.write(wireCommand.getBytes(StandardCharsets.UTF_8));
                output.flush();
                socket.shutdownOutput();
                return readAll(input);
            }
        }
    }

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        return output.toString(StandardCharsets.UTF_8).replace('\0', '\n');
    }

    private NativePathScanResult parseNativePathScanResponse(String output) {
        Map<String, Collection<String>> found = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        if (output == null || output.isBlank()) {
            errors.add("empty response from clamd");
            return new NativePathScanResult(found, errors);
        }

        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }

            if (line.endsWith(" FOUND")) {
                int sep = line.lastIndexOf(": ");
                if (sep <= 0) {
                    errors.add(line);
                    continue;
                }
                String file = line.substring(0, sep).trim();
                String signature = line.substring(sep + 2, line.length() - " FOUND".length()).trim();
                found.computeIfAbsent(file, k -> new ArrayList<>()).add(signature);
                continue;
            }

            if (line.endsWith(" ERROR")) {
                errors.add(line);
                continue;
            }

            if (line.endsWith(" OK")) {
                continue;
            }

            // Keep unexpected native clamd responses visible instead of silently falling back to INSTREAM.
            errors.add(line);
        }

        return new NativePathScanResult(found, errors);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (compact.length() <= max) {
            return compact;
        }
        return compact.substring(0, max) + "...";
    }

    private void notifyIfNeeded(String jobId) {
        try {
            notificationService.notifyIfNeeded(jobRepo.findById(jobId).orElseThrow());
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void markRunning(String id) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishOk(String id) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.OK);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishFound(String id, Object foundViruses) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        try {
            job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
        } catch (Exception e) {
            job.setFoundVirusesJson(String.valueOf(foundViruses));
        }
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishError(String id, String message) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void setQuarantinePath(String id, String quarantinePath) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setQuarantinePath(quarantinePath);
        jobRepo.save(job);
    }

    private record NativePathScanResult(Map<String, Collection<String>> found, List<String> errors) { }
}
