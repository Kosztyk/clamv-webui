package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.identity.AuthenticationSettingsService;
import info.trizub.clamav.webclient.service.WebhookAddressPolicy;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import org.springframework.boot.convert.DurationStyle;

public final class ConfigurationCatalog {
    public record Section(String id, String category, String title, String icon, String enableKey,
                          String enabledValue, String disabledValue, String description, String link) { }
    public record Document(List<String> categories, List<Section> sections, List<ConfigurationSetting> fields) { }
    private static final Document DOCUMENT;
    private static final Map<String, ConfigurationSetting> FIELDS;
    static {
        try (InputStream in = ConfigurationCatalog.class.getResourceAsStream("/configuration-center/catalog.json")) {
            DOCUMENT = new ObjectMapper().readValue(in, Document.class);
            Map<String,ConfigurationSetting> map = new LinkedHashMap<>();
            for (var field : DOCUMENT.fields()) {
                if (map.put(field.key(), field) != null) throw new IllegalStateException("Duplicate configuration key");
            }
            FIELDS = Collections.unmodifiableMap(map);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private ConfigurationCatalog() { }
    public static Document document() { return DOCUMENT; }
    public static Collection<ConfigurationSetting> all() { return FIELDS.values(); }
    public static ConfigurationSetting get(String key) {
        ConfigurationSetting result = FIELDS.get(key);
        if (result == null) throw new IllegalArgumentException("Unsupported configuration setting");
        return result;
    }
    public static Optional<ConfigurationSetting> find(String key) { return Optional.ofNullable(FIELDS.get(key)); }
    public static Map<String,String> defaults() {
        Map<String,String> result = new LinkedHashMap<>();
        all().forEach(field -> result.put(field.key(), field.defaultValue()));
        return result;
    }
    public static String validate(ConfigurationSetting field, String submitted) {
        if (submitted == null || submitted.length() > (field.secret() ? 4096 : 2048)
                || submitted.indexOf('\0') >= 0 || submitted.indexOf('\r') >= 0 || submitted.indexOf('\n') >= 0)
            throw invalid(field, "invalid length or control characters");
        String value = field.secret() ? submitted : submitted.trim();
        try {
            if(field.secret() && field.section().matches("ldap|oidc") && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024) throw invalid(field,"credential exceeds 1024 bytes");
            if(field.key().equals("center.clamd.name") && !value.matches("[A-Za-z0-9 _.\\-]{1,64}")) throw invalid(field,"use 1–64 letters, numbers, spaces or punctuation");
            if(field.key().equals("clamav.service.host") && (value.isBlank() || value.length()>255
                    || !value.startsWith("/") && !value.matches("[A-Za-z0-9_.:\\[\\]\\-]+"))) throw invalid(field,"use a hostname, IP or absolute socket path");
            if(Set.of("CLAMAV_REST_FORM_KEY","CLAMAV_REST_WEBHOOK_FIELD").contains(field.key())
                    && !value.matches("[A-Za-z][A-Za-z0-9_-]{0,50}"))
                throw invalid(field,"use 1–51 letters, numbers, underscores or hyphens, starting with a letter");
            if(Set.of("app.storage.uploadDir","app.storage.quarantineDir").contains(field.key()) && value.isBlank())
                throw invalid(field,"choose a storage directory");
            if(field.key().equals("spring.flyway.baseline-version") && !value.matches("[0-9]+(?:[._][0-9]+)*"))
                throw invalid(field,"use a numeric migration version");
            if(field.key().equals("clamav.public-url") && !value.isBlank() && !value.startsWith("https://")) throw invalid(field,"use an HTTPS application base URL");
            if(field.key().equals("server.servlet.context-path") && !value.isEmpty() && !value.matches("/[A-Za-z0-9_/-]*[A-Za-z0-9_-]")) throw invalid(field,"use an absolute prefix without a trailing slash");
            if(field.key().equals("management.server.port") && !value.isBlank() && (Integer.parseInt(value)<1 || Integer.parseInt(value)>65535)) throw invalid(field,"use a valid port");
            if(field.key().startsWith("CLAMAV_REST_") && field.key().endsWith("_PATH") && (!value.startsWith("/") || value.startsWith("//") || value.matches(".*[?#:\\\\].*") || value.contains(".."))) throw invalid(field,"use an absolute API path without traversal or a query");
            if(field.key().startsWith("CLAMAV_REST_") && field.key().endsWith("_PATH")) URI.create(value);
            if(field.key().startsWith("spring.servlet.multipart.")) {
                long bytes=org.springframework.util.unit.DataSize.parse(value).toBytes();
                if(bytes<1 || bytes>2147483648L) throw invalid(field,"use a size from 1 byte to 2048MB");
            }
            if(field.key().equals("clamav.identity.oidc.scopes"))
                value=String.join(",",info.trizub.clamav.webclient.identity.OidcScopes.parse(value));
            switch (field.type()) {
                case "boolean" -> { if (!Set.of("true","false").contains(value)) throw invalid(field,"choose enabled or disabled"); }
                case "number" -> {
                    long number = Long.parseLong(value);
                    if (number < field.minimum() || number > field.maximum()) throw invalid(field,"outside the supported range");
                    value=Long.toString(number);
                }
                case "duration" -> {
                    Duration duration = DurationStyle.detectAndParse(value);
                    if (duration.toMillis() < 1 || duration.toMillis() > 300_000) throw invalid(field,"use 1ms to 5m");
                }
                case "select" -> { if (!field.choices().contains(value)) throw invalid(field,"unsupported option"); }
                case "url" -> { if (!value.isEmpty()) checkHttp(field,value); }
                case "path" -> { if (!value.isEmpty()) Path.of(value); }
                case "jdbc" -> checkJdbc(value);
                case "timezone" -> ZoneId.of(value);
                default -> { }
            }
        } catch (IllegalArgumentException e) { throw invalid(field,"invalid value"); }
        return value;
    }
    private static IllegalArgumentException invalid(ConfigurationSetting field,String reason) {
        // Deliberately omit the submitted value, including token-bearing URLs.
        return new IllegalArgumentException(field.label()+": "+reason);
    }
    private static void checkHttp(ConfigurationSetting field,String value) {
        URI uri=URI.create(value);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null) throw invalid(field,"use an HTTP(S) address without credentials or a query");
    }
    public static void checkJdbc(String url) {
        String lower=url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("jdbc:postgresql://") || lower.startsWith("jdbc:mysql://")
                || lower.startsWith("jdbc:mariadb://") || lower.startsWith("jdbc:h2:file:")
                || lower.matches("jdbc:h2:mem:[a-z0-9_-]+(?:;.*)?")))
            throw new IllegalArgumentException("Database URL: unsupported JDBC connection");
        if (lower.matches(".*(?:[;?&])(?:init|runscript|autoconvert|auto_server|password|user|trace_level_file|trace_level_system_out)\\s*=.*")
                || lower.contains("\\n") || lower.contains("\\r") || lower.matches("jdbc:(?:postgresql|mysql|mariadb)://[^/]*@.*"))
            throw new IllegalArgumentException("Database URL: use dedicated credential fields and safe connection options");
        if(lower.startsWith("jdbc:h2:")) for(String option:url.split(";",-1)) {
            if(option.startsWith("jdbc:")) continue;
            String[] pair=option.split("=",2);
            if(pair.length!=2 || !Set.of("MODE","DB_CLOSE_DELAY","DB_CLOSE_ON_EXIT","IFEXISTS","LOCK_TIMEOUT","ACCESS_MODE_DATA").contains(pair[0].toUpperCase(Locale.ROOT))
                    || !pair[1].matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Database URL: unsupported H2 connection option");
        }
        if (lower.startsWith("jdbc:h2:file:")) {
            String filename=url.substring(13).split(";",2)[0];
            if (!Path.of(filename).toAbsolutePath().normalize().startsWith(Path.of("data").toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Database URL: H2 storage must be under the application data directory");
        }
    }
    public static Map<String,String> provider(String name,Map<String,String> values) {
        Map<String,String> result = new LinkedHashMap<>();
        for (var field:all()) if (field.section().equals(name) && !field.secret() && !field.legacy().isBlank())
            result.put(field.legacy(),values.getOrDefault(field.key(),field.defaultValue()));
        return result;
    }
    public static String jdbcDriver(String url) {
        checkJdbc(url);
        String lower=url.toLowerCase(Locale.ROOT);
        return lower.startsWith("jdbc:h2:")?"org.h2.Driver":lower.startsWith("jdbc:postgresql:")?"org.postgresql.Driver":lower.startsWith("jdbc:mariadb:")?"org.mariadb.jdbc.Driver":"com.mysql.cj.jdbc.Driver";
    }
    public static void validateCombination(Map<String,String> values,Set<String> changed) {
        if (changed.stream().anyMatch(k->k.startsWith("spring.datasource."))) {
            String url=values.get("spring.datasource.url"),driver=values.getOrDefault("spring.datasource.driver-class-name","");
            checkJdbc(url);
            String expected=jdbcDriver(url);
            if(!driver.isBlank() && !driver.equals(expected)) throw new IllegalArgumentException("Database driver must match the selected JDBC URL; leave it blank for automatic detection");
        }
        if(changed.contains("javax.net.ssl.trustStore") && !values.get("javax.net.ssl.trustStore").isBlank()
                && (!java.nio.file.Files.isRegularFile(Path.of(values.get("javax.net.ssl.trustStore"))) || !java.nio.file.Files.isReadable(Path.of(values.get("javax.net.ssl.trustStore")))))
            throw new IllegalArgumentException("Truststore must already exist and be readable inside this container");
        if (changed.stream().anyMatch(k->k.startsWith("clamav.identity."))) {
            for (String provider:List.of("ldap","oidc")) {
                var fields=provider(provider,values);
                String secret=values.getOrDefault(provider.equals("ldap")?"clamav.identity.ldap.manager-password":"clamav.identity.oidc.client-secret","");
                AuthenticationSettingsService.validate(provider,fields,secret);
            }
        }
        if (changed.stream().anyMatch(k->k.startsWith("clamav.webhook.") || k.startsWith("app.webhook."))) {
            var policy=new WebhookNotificationProperties();
            policy.setAllowPrivateAddresses(Boolean.parseBoolean(values.get("clamav.webhook.allow-private-addresses")));
            if ("true".equals(values.get("app.webhook.enabled"))) {
                String destination=values.getOrDefault("app.webhook.url","");
                if (destination.isBlank()) throw new IllegalArgumentException("Webhook URL is required before enabling delivery");
                new WebhookAddressPolicy(policy).validateUrl(destination);
            }
            Duration dns=DurationStyle.detectAndParse(values.get("clamav.webhook.dns-timeout"));
            Duration total=DurationStyle.detectAndParse(values.get("clamav.webhook.delivery-timeout"));
            if (dns.compareTo(total)>0) throw new IllegalArgumentException("DNS timeout must not exceed the total delivery timeout");
        }
        if ("true".equals(values.get("clamav.rest.enabled")) && values.getOrDefault("CLAMAV_REST_URL","").isBlank()
                && changed.contains("clamav.rest.enabled")) throw new IllegalArgumentException("External REST URL is required before enabling it");
        if ("true".equals(values.get("spring.flyway.baseline-on-migrate")) && !"1".equals(values.get("spring.flyway.baseline-version")))
            throw new IllegalArgumentException("Legacy baselining must use version 1 after a reviewed database backup");
        if ("none".equals(values.get("server.servlet.session.cookie.same-site"))
                && !"true".equals(values.get("server.servlet.session.cookie.secure")))
            throw new IllegalArgumentException("SameSite=None requires secure session cookies");
    }
}
