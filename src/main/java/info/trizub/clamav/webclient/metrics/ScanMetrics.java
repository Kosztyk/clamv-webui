package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/** Job labels are finite; only detection counters carry engine signature names, never file paths or identities. */
@Component
public class ScanMetrics {
    private final MeterRegistry registry;
    private final ScanJobRepository repository;
    private final ObjectMapper json;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger workerBusy = new AtomicInteger();
    private final AtomicInteger workerCapacity = new AtomicInteger();
    private final AtomicInteger queuedInMemory = new AtomicInteger();
    private final AtomicInteger databaseAvailable = new AtomicInteger();
    private volatile ThreadPoolExecutor pool;

    public ScanMetrics(MeterRegistry registry, ScanJobRepository repository, ObjectMapper json) {
        this.registry = registry;
        this.repository = repository;
        this.json = json;
        // Publish zeroes before the first scan so rate/distribution queries have a baseline.
        for (ScanJobType type : ScanJobType.values()) {
            for (ScanVerdict verdict : ScanVerdict.values())
                scanCounter(label(type), label(verdict));
            duration(label(type));
        }
        signatureCounter("unknown");
        Gauge.builder("clamav.scan.jobs.pending", pending, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("clamav.scan.jobs.running", running, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("clamav.scan.workers.busy", workerBusy, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("clamav.scan.workers.capacity", workerCapacity, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("clamav.scan.workers.queued", queuedInMemory, AtomicInteger::doubleValue).register(registry);
        Gauge.builder("clamav.scan.database.available", databaseAvailable, AtomicInteger::doubleValue).register(registry);
    }

    public void bindExecutor(ThreadPoolExecutor executor) { this.pool = executor; }

    @Scheduled(fixedDelayString = "${clamav.metrics.jobs-poll-ms:15000}")
    public void pollJobGauges() {
        ThreadPoolExecutor local = pool;
        if (local != null) {
            workerBusy.set(local.getActiveCount());
            workerCapacity.set(local.getMaximumPoolSize());
            queuedInMemory.set(local.getQueue().size());
        }
        try {
            pending.set(Math.toIntExact(Math.min(Integer.MAX_VALUE, repository.countByStatus(ScanJobStatus.QUEUED))));
            running.set(Math.toIntExact(Math.min(Integer.MAX_VALUE, repository.countByStatus(ScanJobStatus.RUNNING))));
            databaseAvailable.set(1);
        } catch (Exception e) {
            databaseAvailable.set(0);
            // Unknown counts must not masquerade as zero during a database outage.
            pending.set(-1);
            running.set(-1);
        }
    }

    /** Called only at the shared, successfully persisted terminal transition. */
    public void recordCompletion(ScanJob job) {
        String type = job.getType() == null ? "UNKNOWN" : job.getType().name();
        String verdict = job.getVerdict() == null ? "ERROR" : job.getVerdict().name();
        String canonicalType = type.toLowerCase(Locale.ROOT);
        scanCounter(canonicalType, verdict.toLowerCase(Locale.ROOT)).increment();
        String reason = "NONE";
        if (job.getVerdict() == ScanVerdict.ERROR) {
            String message = job.getErrorMessage() == null ? "" : job.getErrorMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("timeout")) reason = "TIMEOUT";
            else if (message.contains("connect") || message.contains("refused")) reason = "CONNECTION";
            else if (message.contains("path") || message.contains("permission") || message.contains("denied")) reason = "PATH_OR_PERMISSION";
            else if (message.contains("unsupported")) reason = "UNSUPPORTED";
            else reason = "OTHER";
        }
        Counter.builder("clamav.scan.completed").tags("type",type,"verdict",verdict).register(registry).increment();
        if (job.getVerdict() == ScanVerdict.VIRUS_FOUND) {
            Counter.builder("clamav.scan.detections").register(registry).increment();
            recordSignatures(job.getFoundVirusesJson());
        }
        if (job.getVerdict() == ScanVerdict.ERROR)
            Counter.builder("clamav.scan.failures").tag("reason",reason).register(registry).increment();
        if (job.getStartedAt() != null && job.getFinishedAt() != null && !job.getFinishedAt().isBefore(job.getStartedAt())) {
            duration(canonicalType).record(Duration.between(job.getStartedAt(),job.getFinishedAt()));
        }
    }

    private static String label(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    private Counter scanCounter(String type, String verdict) {
        return Counter.builder("clamav.scans").description("Completed scan jobs")
            .tags("type", type, "verdict", verdict).register(registry);
    }

    private Counter signatureCounter(String signature) {
        return Counter.builder("clamav.viruses.detected").description("File/signature detections from completed scans")
            .tag("signature", signature).register(registry);
    }

    private Timer duration(String type) {
        return Timer.builder("clamav.scan.duration").description("Scan execution duration, excluding queue time")
            .tag("type", type).publishPercentileHistogram()
            .maximumExpectedValue(Duration.ofMinutes(5))
            .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15),
                Duration.ofSeconds(60), Duration.ofSeconds(300)).register(registry);
    }

    private void recordSignatures(String result) {
        boolean recorded = false;
        try {
            // Persisted format is Map<file path, Collection<signature>>. Only values become labels.
            JsonNode files = result == null ? null : json.readTree(result);
            if (files != null && files.isObject()) for (JsonNode detections : files) {
                Set<String> seen = new HashSet<>();
                if (detections.isArray()) {
                    for (JsonNode detection : detections) {
                        if (detection.isTextual()) seen.add(detection.textValue().trim());
                    }
                } else if (detections.isTextual()) seen.add(detections.textValue().trim());
                for (String signature : seen) if (!signature.isBlank()) {
                    signatureCounter(signature).increment();
                    recorded = true;
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Older jobs may contain non-JSON results. A missing signature must not break completion.
        }
        if (!recorded) signatureCounter("unknown").increment();
    }
}
