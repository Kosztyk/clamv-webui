package info.trizub.clamav.webclient.model;

import java.time.Instant;

/** Bounded retention projection: no TEXT scan results or endpoint hydration. */
public record ScanJobRetentionRow(
    String id, Instant finishedAt, String storedPath, String quarantinePath) {}
