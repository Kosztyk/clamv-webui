package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.model.*;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/** Explicit, stable API contract: never serialize entities or internal filesystem paths. */
public final class ApiV1Dtos {
    private ApiV1Dtos() {}
    public record Endpoint(long id, String name, boolean enabled) {
        public static Endpoint from(ClamdEndpoint ep) { return new Endpoint(ep.getId(), ep.getName(), ep.isEnabled()); }
    }
    public record Job(String id, String type, String status, String verdict, Long endpointId,
                      String filename, Long sizeBytes, Instant submittedAt, Instant startedAt, Instant finishedAt,
                      List<String> signatures, Long durationMillis, String quarantineStatus, JobError error) {
        public static Job from(ScanJob j) {
            return new Job(j.getId(), j.getType().name(), j.getStatus().name(),
                    j.getVerdict() == null ? null : j.getVerdict().name(),
                    j.getEndpoint() == null ? null : j.getEndpoint().getId(),
                    j.getType() == ScanJobType.UPLOAD ? j.getTarget() : null,
                    j.getSizeBytes(), j.getSubmittedAt(), j.getStartedAt(), j.getFinishedAt(), signatures(j), duration(j),
                    quarantineStatus(j), JobError.from(j));
        }
        private static String quarantineStatus(ScanJob job) {
            if(job.getVerdict()==ScanVerdict.VIRUS_FOUND && info.trizub.clamav.webclient.service.QuarantineService.failureRecorded(job.getErrorMessage())) return "FAILED";
            // A recorded location is evidence of a completed quarantine operation, not a live disk check.
            if (job.getQuarantinePath()!=null && !job.getQuarantinePath().isBlank()) return "RECORDED";
            if (job.getStatus()!=ScanJobStatus.FINISHED) return "PENDING";
            if (job.getVerdict()==ScanVerdict.OK) return "NOT_APPLICABLE";
            if (job.getVerdict()==ScanVerdict.VIRUS_FOUND && job.getType()==ScanJobType.UPLOAD) return "NOT_RECORDED";
            // Legacy PATH/WATCH results do not record each file's quarantine operation.
            return "UNKNOWN";
        }
        private static final ObjectMapper JSON = new ObjectMapper();
        private static List<String> signatures(ScanJob job) {
            String raw=job.getFoundVirusesJson();
            if (raw==null || raw.length()>1024*1024 || job.getVerdict()!=ScanVerdict.VIRUS_FOUND) return List.of();
            TreeSet<String> names=new TreeSet<>();
            try {
                JsonNode root=JSON.readTree(raw);
                if (!root.isObject()) return List.of();
                // Keys are filenames. Only explicit signature values enter the DTO.
                for (JsonNode value:root) {
                    if (value.isArray()) for (JsonNode name:value) addSignature(names,name);
                    else addSignature(names,value);
                }
            } catch (Exception ignored) { return List.of(); }
            return List.copyOf(names);
        }
        private static void addSignature(TreeSet<String> names, JsonNode node) {
            if (!node.isTextual() || names.size()>=1000) return;
            String name=node.asText().trim();
            if (!name.isEmpty() && name.length()<=256 && name.indexOf('/')<0 && name.indexOf('\\')<0
                    && name.chars().noneMatch(Character::isISOControl)) names.add(name);
        }
        private static Long duration(ScanJob job) {
            if (job.getStartedAt()==null || job.getFinishedAt()==null || job.getFinishedAt().isBefore(job.getStartedAt())) return null;
            try { return Duration.between(job.getStartedAt(),job.getFinishedAt()).toMillis(); }
            catch (ArithmeticException ex) { return null; }
        }
    }
    /** Stable public diagnostics. Never reflect raw exception messages, daemon output or paths. */
    public record JobError(String code, String message) {
        private static JobError from(ScanJob job) {
            if(job.getVerdict()==ScanVerdict.VIRUS_FOUND && info.trizub.clamav.webclient.service.QuarantineService.failureRecorded(job.getErrorMessage()))
                return new JobError("QUARANTINE_FAILED","Malware was detected, but quarantine could not be completed. An administrator can inspect this job.");
            if (job.getVerdict()!=ScanVerdict.ERROR) return null;
            String raw=job.getErrorMessage();
            if ("Path is excluded by the daemon scan policy.".equals(raw))
                return new JobError("POLICY_REJECTED", "The target is excluded by the daemon scan policy.");
            if ("No eligible regular files remain after daemon scan-policy filtering.".equals(raw))
                return new JobError("NO_ELIGIBLE_FILES", "No eligible regular files remain after policy filtering.");
            if ("Daemon configuration is unavailable or invalid; scanning the default endpoint is paused.".equals(raw))
                return new JobError("CONFIGURATION_UNAVAILABLE", "The daemon configuration is unavailable or invalid.");
            if (raw!=null && raw.length()<256 && raw.matches("File exceeds (?:the effective upload|the daemon scan-size) limit of [0-9]+ bytes\\."))
                return new JobError("LIMIT_EXCEEDED", "The file exceeds the effective scan size limit.");
            return new JobError("SCAN_FAILED", "The scan failed. An administrator can inspect server logs using this job ID.");
        }
    }
    public record Audit(long id, Instant at, String actor, String action, String outcome,
                        String transport, Long endpointId, String jobId) {
        public static Audit from(AuditEvent event) {
            return new Audit(event.getId(), event.getAt(), event.getUsername(), event.getAction(),
                             event.getOutcome(), event.getTransport(), event.getEndpointId(), event.getJobId());
        }
    }
    public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
        public static <E, T> Page<T> from(org.springframework.data.domain.Page<E> result,
            java.util.function.Function<E, T> mapper) {
            return new Page<>(result.getContent().stream().map(mapper).toList(),
                    result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
        }
    }
    public record Submitted(int created, List<String> jobIds) {}
}
