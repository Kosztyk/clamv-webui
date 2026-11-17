package info.trizub.clamav.webclient.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

/** Explicit, stable API contract: never serialize entities or internal filesystem paths. */
public final class ApiV1Dtos {
  private ApiV1Dtos() {}

  public record Endpoint(long id, String name, boolean enabled) {
    public static Endpoint from(ClamdEndpoint ep) {
      return new Endpoint(ep.getId(), ep.getName(), ep.isEnabled());
    }
  }

  public record Job(
      String id,
      String type,
      String status,
      String verdict,
      Long endpointId,
      String filename,
      Long sizeBytes,
      Instant submittedAt,
      Instant startedAt,
      Instant finishedAt,
      List<String> signatures,
      Long durationMillis,
      String quarantineStatus,
      JobError error) {
    public static Job from(ScanJob j) {
      return new Job(
          j.getId(),
          j.getType().name(),
          j.getStatus().name(),
          j.getVerdict() == null ? null : j.getVerdict().name(),
          j.getEndpoint() == null ? null : j.getEndpoint().getId(),
          j.getType() == ScanJobType.UPLOAD ? j.getTarget() : null,
          j.getSizeBytes(),
          j.getSubmittedAt(),
          j.getStartedAt(),
          j.getFinishedAt(),
          signatures(j),
          duration(j),
          quarantineStatus(j),
          JobError.from(j));
    }

    /**
     * Legacy /api/jobs uses only the bounded scalar preview. Preserve its previous response
     * semantics without loading diagnostic TEXT columns. The API v1 job-list endpoint uses the
     * three-argument overload below to add page-scoped signatures, errors and quarantine
     * information.
     */
    public static Job fromSummary(ScanJobSummary s) {
      Long millis = null;
      if (s.startedAt() != null
          && s.finishedAt() != null
          && !s.finishedAt().isBefore(s.startedAt())) {
        try {
          millis = Duration.between(s.startedAt(), s.finishedAt()).toMillis();
        } catch (ArithmeticException ignored) {
          /* keep absent */
        }
      }
      String q =
          s.status() != ScanJobStatus.FINISHED
              ? "PENDING"
              : s.verdict() == ScanVerdict.OK ? "NOT_APPLICABLE" : "UNKNOWN";
      JobError error =
          s.verdict() == ScanVerdict.ERROR
              ? new JobError("SCAN_FAILED", "The scan failed. Request the job by ID for details.")
              : null;
      return new Job(
          s.id(),
          s.type().name(),
          s.status().name(),
          s.verdict() == null ? null : s.verdict().name(),
          s.endpointId(),
          s.type() == ScanJobType.UPLOAD ? s.target() : null,
          s.sizeBytes(),
          s.submittedAt(),
          s.startedAt(),
          s.finishedAt(),
          List.of(),
          millis,
          q,
          error);
    }

    /**
     * Summary route uses a metadata-only primary query and page-bounded diagnostics only when a job
     * is infected or failed. All sanitization matches the detail DTO.
     */
    public static Job fromSummary(
        ScanJobSummary s, String recordedQuarantinePath, ScanJobListDiagnostics diagnostics) {
      Long millis = null;
      if (s.startedAt() != null
          && s.finishedAt() != null
          && !s.finishedAt().isBefore(s.startedAt())) {
        try {
          millis = Duration.between(s.startedAt(), s.finishedAt()).toMillis();
        } catch (ArithmeticException ignored) {
          /* keep absent */
        }
      }
      String errorMessage = diagnostics == null ? null : diagnostics.errorMessage();
      String viruses = diagnostics == null ? null : diagnostics.foundVirusesJson();
      String quarantinePath = recordedQuarantinePath;
      // A job may be concurrently updated while the two short queries run.
      // Prefer the bounded diagnostic row for its quarantine state when present.
      if (diagnostics != null) quarantinePath = diagnostics.quarantinePath();
      return new Job(
          s.id(),
          s.type().name(),
          s.status().name(),
          s.verdict() == null ? null : s.verdict().name(),
          s.endpointId(),
          s.type() == ScanJobType.UPLOAD ? s.target() : null,
          s.sizeBytes(),
          s.submittedAt(),
          s.startedAt(),
          s.finishedAt(),
          signatures(s.verdict(), viruses),
          millis,
          quarantineStatus(s.type(), s.status(), s.verdict(), errorMessage, quarantinePath),
          JobError.from(s.verdict(), errorMessage));
    }

    private static String quarantineStatus(ScanJob job) {
      return quarantineStatus(
          job.getType(),
          job.getStatus(),
          job.getVerdict(),
          job.getErrorMessage(),
          job.getQuarantinePath());
    }

    private static String quarantineStatus(
        ScanJobType type,
        ScanJobStatus status,
        ScanVerdict verdict,
        String errorMessage,
        String quarantinePath) {
      if (verdict == ScanVerdict.VIRUS_FOUND
          && info.trizub.clamav.webclient.service.QuarantineService.failureRecorded(errorMessage))
        return "FAILED";
      // A truncated detection set cannot certify quarantine of every
      // infected file even when the retained subset was moved successfully.
      if (verdict == ScanVerdict.VIRUS_FOUND && reportTruncated(errorMessage)) return "INCOMPLETE";
      // A recorded location is evidence of a completed quarantine operation, not a live disk check.
      if (quarantinePath != null && !quarantinePath.isBlank()) return "RECORDED";
      if (status != ScanJobStatus.FINISHED) return "PENDING";
      if (verdict == ScanVerdict.OK) return "NOT_APPLICABLE";
      if (verdict == ScanVerdict.VIRUS_FOUND && type == ScanJobType.UPLOAD) return "NOT_RECORDED";
      // Legacy PATH/WATCH results do not record each file's quarantine operation.
      return "UNKNOWN";
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    // Keep this wire-level marker in sync with DetectionReportLimiter.WARNING;
    // the API DTO must also compile in the standalone privacy audit fixture.
    private static final String DETECTION_TRUNCATION_MARKER =
        "ClamAV detection report exceeded safety limits; some infected paths or signatures were omitted and may require manual quarantine.";

    private static boolean reportTruncated(String message) {
      if (message == null) return false;
      for (String line : message.split("\\R"))
        if (line.equals(DETECTION_TRUNCATION_MARKER)) return true;
      return false;
    }

    private static List<String> signatures(ScanJob job) {
      return signatures(job.getVerdict(), job.getFoundVirusesJson());
    }

    private static List<String> signatures(ScanVerdict verdict, String raw) {
      if (raw == null || raw.length() > 1024 * 1024 || verdict != ScanVerdict.VIRUS_FOUND)
        return List.of();
      TreeSet<String> names = new TreeSet<>();
      try {
        JsonNode root = JSON.readTree(raw);
        if (!root.isObject()) return List.of();
        // Keys are filenames. Only explicit signature values enter the DTO.
        for (JsonNode value : root) {
          if (value.isArray()) for (JsonNode name : value) addSignature(names, name);
          else addSignature(names, value);
        }
      } catch (Exception ignored) {
        return List.of();
      }
      return List.copyOf(names);
    }

    private static void addSignature(TreeSet<String> names, JsonNode node) {
      if (!node.isTextual() || names.size() >= 1000) return;
      String name = node.asText().trim();
      if (!name.isEmpty()
          && name.length() <= 256
          && name.indexOf('/') < 0
          && name.indexOf('\\') < 0
          && name.chars().noneMatch(Character::isISOControl)) names.add(name);
    }

    private static Long duration(ScanJob job) {
      if (job.getStartedAt() == null
          || job.getFinishedAt() == null
          || job.getFinishedAt().isBefore(job.getStartedAt())) return null;
      try {
        return Duration.between(job.getStartedAt(), job.getFinishedAt()).toMillis();
      } catch (ArithmeticException ex) {
        return null;
      }
    }
  }

  /** Stable public diagnostics. Never reflect raw exception messages, daemon output or paths. */
  public record JobError(String code, String message) {
    private static JobError from(ScanJob job) {
      return from(job.getVerdict(), job.getErrorMessage());
    }

    private static JobError from(ScanVerdict verdict, String raw) {
      if (verdict == ScanVerdict.VIRUS_FOUND
          && info.trizub.clamav.webclient.service.QuarantineService.failureRecorded(raw))
        return new JobError(
            "QUARANTINE_FAILED",
            "Malware was detected, but quarantine could not be completed. An administrator can inspect this job.");
      if (verdict == ScanVerdict.VIRUS_FOUND && Job.reportTruncated(raw))
        return new JobError(
            "DETECTION_REPORT_TRUNCATED",
            "Malware was detected, but the report exceeded safe storage limits. Some infected files may require manual handling.");
      if (verdict != ScanVerdict.ERROR) return null;
      if ("Path is excluded by the daemon scan policy.".equals(raw))
        return new JobError("POLICY_REJECTED", "The target is excluded by the daemon scan policy.");
      if ("No eligible regular files remain after daemon scan-policy filtering.".equals(raw))
        return new JobError(
            "NO_ELIGIBLE_FILES", "No eligible regular files remain after policy filtering.");
      if ("Daemon configuration is unavailable or invalid; scanning the default endpoint is paused."
          .equals(raw))
        return new JobError(
            "CONFIGURATION_UNAVAILABLE", "The daemon configuration is unavailable or invalid.");
      if (raw != null
          && raw.length() < 256
          && raw.matches(
              "File exceeds (?:the effective upload|the daemon scan-size) limit of [0-9]+ bytes\\."))
        return new JobError("LIMIT_EXCEEDED", "The file exceeds the effective scan size limit.");
      return new JobError(
          "SCAN_FAILED",
          "The scan failed. An administrator can inspect server logs using this job ID.");
    }
  }

  public record Audit(
      long id,
      Instant at,
      String actor,
      String action,
      String outcome,
      String transport,
      Long endpointId,
      String jobId) {
    public static Audit from(AuditEvent event) {
      return new Audit(
          event.getId(),
          event.getAt(),
          event.getUsername(),
          event.getAction(),
          event.getOutcome(),
          event.getTransport(),
          event.getEndpointId(),
          event.getJobId());
    }
  }

  public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static <E, T> Page<T> from(
        org.springframework.data.domain.Page<E> result, java.util.function.Function<E, T> mapper) {
      return new Page<>(
          result.getContent().stream().map(mapper).toList(),
          result.getNumber(),
          result.getSize(),
          result.getTotalElements(),
          result.getTotalPages());
    }
  }

  public record Submitted(int created, List<String> jobIds) {}
}
