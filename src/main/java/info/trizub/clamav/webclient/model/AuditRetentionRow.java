package info.trizub.clamav.webclient.model;

import java.time.Instant;

/** Bounded retention projection: no audit details or user-agent fields. */
public record AuditRetentionRow(Long id, Instant at) {}
