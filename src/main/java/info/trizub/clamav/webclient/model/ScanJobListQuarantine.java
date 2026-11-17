package info.trizub.clamav.webclient.model;

/** Small API-only projection; internal path must never be returned in responses. */
public record ScanJobListQuarantine(String id, String quarantinePath) {}
