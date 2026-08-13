package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.util.AtomicPropertiesFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final String SETTINGS_FILE = "conf/clamav-web-client.properties";

    // Keys
    private static final String ALLOWED_SCAN_ROOTS = "app.allowedScanRoots";
    private static final String UPLOAD_MAX_BYTES = "app.upload.maxBytes";
    private static final String CONCURRENT_SCANS = "app.concurrentScans";
    private static final String UPLOAD_DIR = "app.storage.uploadDir";
    private static final String UPLOAD_PATH_SCAN_ENABLED = "app.upload.pathScan.enabled";
    private static final String UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM = "app.upload.pathScan.fallbackToInstream";
    private static final String QUARANTINE_DIR = "app.storage.quarantineDir";
    private static final String QUARANTINE_ENABLED = "app.quarantine.enabled";
    private static final String WEBHOOK_ENABLED = "app.webhook.enabled";
    private static final String WEBHOOK_URL = "app.webhook.url";
    private static final String WATCH_ENABLED = "app.watch.enabled";
    private static final String WATCH_POLL_SECONDS = "app.watch.pollSeconds";

    // Environment variables used by Docker/container deployments.
    private static final String ENV_UPLOAD_PATH_SCAN_ENABLED = "CLAMAV_UPLOAD_PATH_SCAN_ENABLED";
    private static final String ENV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM = "CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM";
    private static final String ENV_CLAMAV_HOST = "CLAMAV_HOST";
    private static final String ENV_CLAMAV_PORT = "CLAMAV_PORT";

    // Legacy keys (kept for backward compatibility)
    private static final String CLAMAV_SERVICE_HOST_PROPERTY = "clamav.service.host";
    private static final String CLAMAV_SERVICE_PORT_PROPERTY = "clamav.service.port";
    private static final String CLAMAV_SERVICE_PLATFORM_PROPERTY = "clamav.service.platform";

    private Properties props;

    @PostConstruct
    public void init() {
        try {
            props = AtomicPropertiesFile.loadOrCreate(Paths.get(SETTINGS_FILE));

            // Persistent defaults.
            props.putIfAbsent(ALLOWED_SCAN_ROOTS, "/scandir");
            props.putIfAbsent(UPLOAD_MAX_BYTES, String.valueOf(2L * 1024 * 1024 * 1024)); // 2GiB
            props.putIfAbsent(CONCURRENT_SCANS, "2");
            props.putIfAbsent(UPLOAD_DIR, "./data/uploads");
            props.putIfAbsent(QUARANTINE_DIR, "./data/quarantine");
            props.putIfAbsent(QUARANTINE_ENABLED, "false");
            props.putIfAbsent(WEBHOOK_ENABLED, "false");
            props.putIfAbsent(WEBHOOK_URL, "");
            props.putIfAbsent(WATCH_ENABLED, "false");
            props.putIfAbsent(WATCH_POLL_SECONDS, "30");
            props.putIfAbsent(CLAMAV_SERVICE_PLATFORM_PROPERTY, "UNIX");

            /*
             * Container environment variables must override values that may already
             * exist in conf/clamav-web-client.properties.
             *
             * The old implementation used putIfAbsent(...), which meant that once
             * the properties file contained (for example)
             * app.upload.pathScan.enabled=true, setting
             * CLAMAV_UPLOAD_PATH_SCAN_ENABLED=false in Docker Compose had no effect.
             * That forced remote clamd deployments to keep attempting SCAN against a
             * pathname that exists only in the web container.
             */
            applyEnvironmentOverride(
                    UPLOAD_PATH_SCAN_ENABLED,
                    ENV_UPLOAD_PATH_SCAN_ENABLED,
                    "true"
            );
            applyEnvironmentOverride(
                    UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM,
                    ENV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM,
                    "false"
            );

            // Legacy endpoint defaults. These values seed a new default endpoint.
            // Existing endpoints stored in the database continue to be managed there.
            applyEnvironmentOverride(
                    CLAMAV_SERVICE_HOST_PROPERTY,
                    ENV_CLAMAV_HOST,
                    "localhost"
            );
            applyEnvironmentOverride(
                    CLAMAV_SERVICE_PORT_PROPERTY,
                    ENV_CLAMAV_PORT,
                    "3310"
            );

            persist();
        } catch (IOException e) {
            log.error("Failed to init settings: {}", e.getMessage(), e);
            props = new Properties();
        }
    }

    /**
     * Applies container configuration with the following precedence:
     * environment variable -> persisted property -> application default.
     */
    private void applyEnvironmentOverride(String propertyKey, String environmentVariable, String defaultValue) {
        String resolved = resolveEnvironmentOverride(
                System.getenv(environmentVariable),
                props.getProperty(propertyKey),
                defaultValue
        );
        props.setProperty(propertyKey, resolved);
    }

    // Package-private for focused regression tests.
    static String resolveEnvironmentOverride(String environmentValue, String persistedValue, String defaultValue) {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        if (persistedValue != null) {
            return persistedValue;
        }
        return defaultValue;
    }

    public synchronized void persist() {
        try {
            AtomicPropertiesFile.storeAtomic(Paths.get(SETTINGS_FILE), props);
        } catch (IOException e) {
            log.error("Failed to persist settings: {}", e.getMessage(), e);
        }
    }

    public synchronized Properties snapshot() {
        Properties p = new Properties();
        p.putAll(props);
        return p;
    }

    public synchronized void updateFromMap(Map<String, String> updates) {
        for (var e : updates.entrySet()) {
            if (e.getValue() == null) continue;
            props.setProperty(e.getKey(), e.getValue().trim());
        }
        persist();
    }

    public List<Path> allowedRoots() {
        String raw = props.getProperty(ALLOWED_SCAN_ROOTS, "/scandir");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    public long uploadMaxBytes() {
        try { return Long.parseLong(props.getProperty(UPLOAD_MAX_BYTES)); } catch (Exception e) { return 2L * 1024 * 1024 * 1024; }
    }

    public int concurrentScans() {
        try { return Integer.parseInt(props.getProperty(CONCURRENT_SCANS)); } catch (Exception e) { return 2; }
    }

    public Path uploadDir() {
        return Paths.get(props.getProperty(UPLOAD_DIR, "./data/uploads")).toAbsolutePath().normalize();
    }

    public boolean uploadPathScanEnabled() {
        return Boolean.parseBoolean(props.getProperty(UPLOAD_PATH_SCAN_ENABLED, "true"));
    }

    public boolean uploadPathScanFallbackToInstream() {
        return Boolean.parseBoolean(props.getProperty(UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM, "false"));
    }

    public Path quarantineDir() {
        return Paths.get(props.getProperty(QUARANTINE_DIR, "./data/quarantine")).toAbsolutePath().normalize();
    }

    public boolean quarantineEnabled() {
        return Boolean.parseBoolean(props.getProperty(QUARANTINE_ENABLED, "false"));
    }

    public boolean webhookEnabled() {
        return Boolean.parseBoolean(props.getProperty(WEBHOOK_ENABLED, "false"));
    }

    public String webhookUrl() {
        return props.getProperty(WEBHOOK_URL, "");
    }

    public boolean watchEnabled() {
        return Boolean.parseBoolean(props.getProperty(WATCH_ENABLED, "false"));
    }

    public int watchPollSeconds() {
        try { return Integer.parseInt(props.getProperty(WATCH_POLL_SECONDS, "30")); } catch (Exception e) { return 30; }
    }

    // Legacy getters
    public String legacyHost() { return props.getProperty(CLAMAV_SERVICE_HOST_PROPERTY, "localhost"); }
    public int legacyPort() { try { return Integer.parseInt(props.getProperty(CLAMAV_SERVICE_PORT_PROPERTY, "3310")); } catch (Exception e) { return 3310; } }
    public String legacyPlatform() { return props.getProperty(CLAMAV_SERVICE_PLATFORM_PROPERTY, "UNIX"); }
}
