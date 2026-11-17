package info.trizub.clamav.webclient.model;

/**
 * API list diagnostics only for infected/failed jobs on the current page. Never includes internal
 * stored paths, and is not returned directly to clients.
 */
public record ScanJobListDiagnostics(
    String id, String foundVirusesJson, String errorMessage, String quarantinePath) {}
