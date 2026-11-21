package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded Prometheus metrics: job labels are enum-derived and signature names are admitted into a
 * fixed-size, process-lifetime set. There is intentionally no LRU eviction: removing old meters and
 * admitting new names would still create unbounded Prometheus time-series churn in a long-lived
 * scrape history.
 */
@Component
public class ScanMetrics {
  private static final int DEFAULT_MAX_SIGNATURES = 100;
  private static final int HARD_MAX_SIGNATURES = 500;
  private static final int MAX_SIGNATURE_LABEL_LENGTH = 160;
  private static final String OTHER = "other";
  private static final String UNKNOWN = "unknown";
  private static final List<String> FAILURE_REASONS =
      List.of("TIMEOUT", "CONNECTION", "PATH_OR_PERMISSION", "UNSUPPORTED", "OTHER");

  private final ScanJobRepository repository;
  private final ObjectMapper json;
  private final Map<String, Counter> completionCounters = new HashMap<>();
  private final Map<String, Timer> durationTimers = new HashMap<>();
  private final Map<String, Counter> failureCounters = new HashMap<>();
  private final Counter detectionsCounter;
  private final Counter dispatchDeferredCounter;
  private final Counter unknownSignatureCounter;
  private final Counter otherSignatureCounter;
  private final MeterRegistry registry;
  private final int maxDistinctSignatures;
  // This map is accessed only while holding its own monitor; it never exceeds the configured bound.
  private final Map<String, Counter> admittedSignatures = new HashMap<>();
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicInteger running = new AtomicInteger();
  private final AtomicInteger workerBusy = new AtomicInteger();
  private final AtomicInteger workerCapacity = new AtomicInteger();
  private final AtomicInteger queuedInMemory = new AtomicInteger();
  private final AtomicInteger databaseAvailable = new AtomicInteger();
  private volatile ThreadPoolExecutor pool;

  @Autowired
  public ScanMetrics(
      MeterRegistry registry,
      ScanJobRepository repository,
      ObjectMapper json,
      @Value("${clamav.metrics.signatures.max-distinct:100}") int maxDistinctSignatures) {
    if (maxDistinctSignatures < 1 || maxDistinctSignatures > HARD_MAX_SIGNATURES)
      throw new IllegalArgumentException(
          "Signature metrics max-distinct must be between 1 and " + HARD_MAX_SIGNATURES);
    this.registry = registry;
    this.repository = repository;
    this.json = json;
    this.maxDistinctSignatures = maxDistinctSignatures;

    // Register each finite job metric once. Never create a meter builder per completed job.
    for (String type : List.of("upload", "path", "watch", UNKNOWN)) {
      for (String verdict : List.of("ok", "virus_found", "error")) {
        completionCounters.put(
            type + ':' + verdict,
            Counter.builder("clamav.scans")
                .description("Completed scan jobs")
                .tags("type", type, "verdict", verdict)
                .register(registry));
      }
      durationTimers.put(
          type,
          Timer.builder("clamav.scan.duration")
              .description("Scan execution duration, excluding queue time")
              .tag("type", type)
              .publishPercentileHistogram()
              .maximumExpectedValue(Duration.ofHours(24))
              .serviceLevelObjectives(
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(5),
                  Duration.ofSeconds(15),
                  Duration.ofSeconds(60),
                  Duration.ofSeconds(300),
                  Duration.ofHours(1),
                  Duration.ofHours(6),
                  Duration.ofHours(12),
                  Duration.ofHours(24))
              .register(registry));
    }
    for (String reason : FAILURE_REASONS) {
      failureCounters.put(
          reason, Counter.builder("clamav.scan.failures").tag("reason", reason).register(registry));
    }
    detectionsCounter = Counter.builder("clamav.scan.detections").register(registry);
    dispatchDeferredCounter =
        Counter.builder("clamav.scan.dispatch.deferred")
            .description("Durably queued scan IDs awaiting free worker capacity")
            .register(registry);
    unknownSignatureCounter = newSignatureCounter(UNKNOWN);
    otherSignatureCounter = newSignatureCounter(OTHER);
    Gauge.builder("clamav.scan.jobs.pending", pending, AtomicInteger::doubleValue)
        .register(registry);
    Gauge.builder("clamav.scan.jobs.running", running, AtomicInteger::doubleValue)
        .register(registry);
    Gauge.builder("clamav.scan.workers.busy", workerBusy, AtomicInteger::doubleValue)
        .register(registry);
    Gauge.builder("clamav.scan.workers.capacity", workerCapacity, AtomicInteger::doubleValue)
        .register(registry);
    Gauge.builder("clamav.scan.workers.queued", queuedInMemory, AtomicInteger::doubleValue)
        .register(registry);
    Gauge.builder("clamav.scan.database.available", databaseAvailable, AtomicInteger::doubleValue)
        .register(registry);
  }

  /** Preserve callers that construct metrics outside Spring and use the production default. */
  ScanMetrics(MeterRegistry registry, ScanJobRepository repository, ObjectMapper json) {
    this(registry, repository, json, DEFAULT_MAX_SIGNATURES);
  }

  public void bindExecutor(ThreadPoolExecutor executor) {
    this.pool = executor;
  }

  public void recordDispatchDeferred() {
    dispatchDeferredCounter.increment();
  }

  @Scheduled(fixedDelayString = "${clamav.metrics.jobs-poll-ms:15000}")
  public void pollJobGauges() {
    ThreadPoolExecutor local = pool;
    if (local != null) {
      workerBusy.set(local.getActiveCount());
      workerCapacity.set(local.getMaximumPoolSize());
      queuedInMemory.set(local.getQueue().size());
    }
    try {
      pending.set(
          Math.toIntExact(
              Math.min(Integer.MAX_VALUE, repository.countByStatus(ScanJobStatus.QUEUED))));
      running.set(
          Math.toIntExact(
              Math.min(Integer.MAX_VALUE, repository.countByStatus(ScanJobStatus.RUNNING))));
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
    String type = job.getType() == null ? UNKNOWN : label(job.getType());
    String verdict = job.getVerdict() == null ? "error" : label(job.getVerdict());
    // clamav.scans is the sole completion counter; the differently labelled
    // clamav.scan.completed metric is retired to prevent double accounting.
    completionCounters.get(type + ':' + verdict).increment();
    if (job.getVerdict() == ScanVerdict.VIRUS_FOUND) {
      detectionsCounter.increment();
      recordSignatures(job.getFoundVirusesJson());
    }
    if (job.getVerdict() == ScanVerdict.ERROR) {
      String message =
          job.getErrorMessage() == null ? "" : job.getErrorMessage().toLowerCase(Locale.ROOT);
      String reason;
      if (message.contains("timeout") || message.contains("timed out")) reason = "TIMEOUT";
      else if (message.contains("connect") || message.contains("refused")) reason = "CONNECTION";
      else if (message.contains("path")
          || message.contains("permission")
          || message.contains("denied")) reason = "PATH_OR_PERMISSION";
      else if (message.contains("unsupported")) reason = "UNSUPPORTED";
      else reason = "OTHER";
      failureCounters.get(reason).increment();
    }
    if (job.getStartedAt() != null
        && job.getFinishedAt() != null
        && !job.getFinishedAt().isBefore(job.getStartedAt())) {
      durationTimers.get(type).record(Duration.between(job.getStartedAt(), job.getFinishedAt()));
    }
  }

  private static String label(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private Counter newSignatureCounter(String signature) {
    return Counter.builder("clamav.viruses.detected")
        .description(
            "File/signature detections from completed scans, capped distinct signature labels")
        .tag("signature", signature)
        .register(registry);
  }

  /**
   * Admission is synchronized so that concurrent scans cannot exceed the distinct label budget.
   * Once full, all new or oversized names contribute to 'other'. Exact signature names remain
   * available in persisted job details / the API.
   */
  private Counter signatureCounter(String signature) {
    if (UNKNOWN.equals(signature)) return unknownSignatureCounter;
    if (OTHER.equals(signature) || signature.length() > MAX_SIGNATURE_LABEL_LENGTH)
      return otherSignatureCounter;
    synchronized (admittedSignatures) {
      Counter existing = admittedSignatures.get(signature);
      if (existing != null) return existing;
      if (admittedSignatures.size() >= maxDistinctSignatures) return otherSignatureCounter;
      Counter registered = newSignatureCounter(signature);
      admittedSignatures.put(signature, registered);
      return registered;
    }
  }

  private void recordSignatures(String result) {
    boolean recorded = false;
    try {
      // Persisted format is Map<file path, Collection<signature>>. Only values become labels.
      JsonNode files = result == null ? null : json.readTree(result);
      if (files != null && files.isObject())
        for (JsonNode detections : files) {
          Set<String> seen = new HashSet<>();
          if (detections.isArray()) {
            for (JsonNode detection : detections) {
              if (detection.isTextual()) seen.add(detection.textValue().trim());
            }
          } else if (detections.isTextual()) seen.add(detections.textValue().trim());
          for (String signature : seen)
            if (!signature.isBlank()) {
              signatureCounter(signature).increment();
              recorded = true;
            }
        }
    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
      // Older jobs may contain non-JSON results. Missing metadata never breaks completion.
    }
    if (!recorded) unknownSignatureCounter.increment();
  }
}
