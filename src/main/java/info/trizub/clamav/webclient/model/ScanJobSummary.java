package info.trizub.clamav.webclient.model;

import java.time.Instant;

/**
 * Scalar-only list projection. A history view must not load large scan diagnostics, quarantined
 * file paths, internal stored paths, or full endpoint entities. For full details use
 * ScanJobService.getOrNull(id) instead.
 */
public record ScanJobSummary(
    String id,
    ScanJobType type,
    ScanJobStatus status,
    ScanVerdict verdict,
    String target,
    String submittedBy,
    String submittedTransport,
    Instant submittedAt,
    Long endpointId,
    String endpointName,
    Long sizeBytes,
    Instant startedAt,
    Instant finishedAt) {}
