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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.core.env.Environment environment;

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
            if(environment!=null) info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).ifPresent(store->{
                String previous=props.getProperty(WEBHOOK_URL,"");
                if(!previous.isBlank() && store.saved(WEBHOOK_URL).isEmpty())
                    store.save(store.snapshot().revision(),Map.of(WEBHOOK_URL,previous),Set.of(),false,"legacy-secret-migration");
                if(store.saved(WEBHOOK_URL).isPresent()) props.remove(WEBHOOK_URL);
            });

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
        // Keep the saved GUI value underneath an override. Resolve deployments at read
        // time, rather than persisting their values into the operator's settings.
        props.putIfAbsent(propertyKey,defaultValue);
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
        for(var field:info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all())
            if(field.policy() || field.key().startsWith("clamav.service."))
                p.setProperty(field.key(),resolved(field.key(),field.defaultValue()));
        return p;
    }

    public synchronized void updateFromMap(Map<String, String> updates) {
        Map<String,String> centralized=new LinkedHashMap<>();Set<String> clear=new LinkedHashSet<>();
        var store=environment==null?Optional.<info.trizub.clamav.webclient.configuration.ConfigurationStore>empty():
            info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment);
        // Validate the entire request before changing either persistence layer.
        for(var e:updates.entrySet()) {
            if(e.getValue()==null) continue;
            var field=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.find(e.getKey());
            if(field.isPresent()) {
                info.trizub.clamav.webclient.configuration.ConfigurationCatalog.validate(field.get(),e.getValue());
                if(environment!=null) {
                    var controlled=info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field.get());
                    if(controlled.isPresent() && !controlled.get().value().equals(e.getValue().trim()))
                        throw new IllegalArgumentException(field.get().label()+" is controlled by deployment configuration");
                }
            }
        }
        Properties next=new Properties();next.putAll(props);
        for (var e : updates.entrySet()) {
            if (e.getValue() == null) continue;
            var field=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.find(e.getKey());
            if(environment!=null && field.isPresent()) {
                var controlled=info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field.get());
                if(controlled.isPresent()) {
                    if(!controlled.get().value().equals(e.getValue().trim())) throw new IllegalArgumentException(field.get().label()+" is controlled by deployment configuration");
                    continue;
                }
            }
            if(store.isPresent() && field.isPresent() && !field.get().live() && store.get().saved(e.getKey()).isPresent()) {
                if(!resolved(e.getKey(),field.get().defaultValue()).equals(e.getValue().trim())) throw new IllegalArgumentException(field.get().label()+" is managed in Configuration Center");
                continue; // Do not erase a staged startup value with the running legacy form.
            }
            if(store.isPresent() && field.isPresent() && (field.get().secret() || store.get().saved(e.getKey()).isPresent())) {
                if(field.get().secret() && e.getValue().isBlank()) clear.add(e.getKey());
                else centralized.put(e.getKey(),e.getValue());
                next.remove(e.getKey());continue;
            }
            next.setProperty(e.getKey(), e.getValue().trim());
        }
        if(store.isPresent() && (!centralized.isEmpty() || !clear.isEmpty()))
            store.get().save(store.get().snapshot().revision(),centralized,clear,false,"legacy-settings");
        props=next;
        persist();
    }
    public Set<String> readOnlyKeys() {
        Set<String> keys=new LinkedHashSet<>();
        if(environment==null) return keys;
        var store=info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment);
        for(var field:info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all())
            if(info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field).isPresent()
                    || !field.live() && store.flatMap(s->s.saved(field.key())).isPresent()) keys.add(field.key());
        return keys;
    }

    private String resolved(String key,String fallback) {
        if(environment!=null) {
            var field=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.find(key);
            if(field.isPresent()) {
                var controlled=info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field.get());
                if(controlled.isPresent()) return controlled.get().value();
                var stored=info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).flatMap(store->store.active(key));
                if(stored.isPresent()) return stored.get();
            }
        } else {
            String alias=switch(key) {
                case "app.upload.pathScan.enabled" -> ENV_UPLOAD_PATH_SCAN_ENABLED;
                case "app.upload.pathScan.fallbackToInstream" -> ENV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM;
                case "clamav.service.host" -> ENV_CLAMAV_HOST;
                case "clamav.service.port" -> ENV_CLAMAV_PORT;
                default -> "";
            };
            String value=alias.isBlank()?null:System.getenv(alias);
            if(value!=null && !value.isBlank()) return value;
        }
        return props.getProperty(key,fallback);
    }

    public List<Path> allowedRoots() {
        String raw = resolved(ALLOWED_SCAN_ROOTS, "/scandir");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    public long uploadMaxBytes() {
        try { return Long.parseLong(resolved(UPLOAD_MAX_BYTES,"2147483648")); } catch (Exception e) { return 2L * 1024 * 1024 * 1024; }
    }

    public int concurrentScans() {
        try { return Integer.parseInt(resolved(CONCURRENT_SCANS,"2")); } catch (Exception e) { return 2; }
    }

    public Path uploadDir() {
        return Paths.get(resolved(UPLOAD_DIR, "./data/uploads")).toAbsolutePath().normalize();
    }

    public boolean uploadPathScanEnabled() {
        return Boolean.parseBoolean(resolved(UPLOAD_PATH_SCAN_ENABLED, "true"));
    }

    public boolean uploadPathScanFallbackToInstream() {
        return Boolean.parseBoolean(resolved(UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM, "false"));
    }

    public Path quarantineDir() {
        return Paths.get(resolved(QUARANTINE_DIR, "./data/quarantine")).toAbsolutePath().normalize();
    }

    public boolean quarantineEnabled() {
        return Boolean.parseBoolean(resolved(QUARANTINE_ENABLED, "false"));
    }

    public boolean webhookEnabled() {
        return Boolean.parseBoolean(resolved(WEBHOOK_ENABLED, "false"));
    }

    public String webhookUrl() {
        return resolved(WEBHOOK_URL, "");
    }

    public boolean watchEnabled() {
        return Boolean.parseBoolean(resolved(WATCH_ENABLED, "false"));
    }

    public int watchPollSeconds() {
        try { return Integer.parseInt(resolved(WATCH_POLL_SECONDS, "30")); } catch (Exception e) { return 30; }
    }

    // Legacy getters
    public String legacyHost() { return resolved(CLAMAV_SERVICE_HOST_PROPERTY, "localhost"); }
    public int legacyPort() { try { return Integer.parseInt(resolved(CLAMAV_SERVICE_PORT_PROPERTY, "3310")); } catch (Exception e) { return 3310; } }
    public String legacyPlatform() { return resolved(CLAMAV_SERVICE_PLATFORM_PROPERTY, "UNIX"); }
}
