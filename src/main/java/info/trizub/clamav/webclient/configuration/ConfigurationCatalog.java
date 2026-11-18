package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.identity.AuthenticationSettingsService;
import info.trizub.clamav.webclient.service.RestTransportPolicy;
import info.trizub.clamav.webclient.service.WebhookAddressPolicy;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import org.springframework.boot.convert.DurationStyle;

public final class ConfigurationCatalog {
  public record Section(
      String id,
      String category,
      String title,
      String icon,
      String enableKey,
      String enabledValue,
      String disabledValue,
      String description,
      String link) {}

  public record Document(
      List<String> categories, List<Section> sections, List<ConfigurationSetting> fields) {}

  private static final Document DOCUMENT;
  private static final Map<String, ConfigurationSetting> FIELDS;

  static {
    try (InputStream in =
        ConfigurationCatalog.class.getResourceAsStream("/configuration-center/catalog.json")) {
      DOCUMENT = new ObjectMapper().readValue(in, Document.class);
      Map<String, ConfigurationSetting> map = new LinkedHashMap<>();
      for (var field : DOCUMENT.fields()) {
        if (map.put(field.key(), field) != null)
          throw new IllegalStateException("Duplicate configuration key");
      }
      FIELDS = Collections.unmodifiableMap(map);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private ConfigurationCatalog() {}

  public static Document document() {
    return DOCUMENT;
  }

  public static Collection<ConfigurationSetting> all() {
    return FIELDS.values();
  }

  public static ConfigurationSetting get(String key) {
    ConfigurationSetting result = FIELDS.get(key);
    if (result == null) throw new IllegalArgumentException("Unsupported configuration setting");
    return result;
  }

  public static Optional<ConfigurationSetting> find(String key) {
    return Optional.ofNullable(FIELDS.get(key));
  }

  public static Map<String, String> defaults() {
    Map<String, String> result = new LinkedHashMap<>();
    all().forEach(field -> result.put(field.key(), field.defaultValue()));
    return result;
  }

  static boolean isProxyAllowlist(String key) {
    return key.startsWith("server.tomcat.remoteip.") && key.endsWith("-proxies");
  }

  public static String validate(ConfigurationSetting field, String submitted) {
    if (submitted == null
        || submitted.length() > (field.secret() ? 4096 : 2048)
        || submitted.indexOf('\0') >= 0
        || submitted.indexOf('\r') >= 0
        || submitted.indexOf('\n') >= 0)
      throw invalid(field, "invalid length or control characters");
    String value = field.secret() ? submitted : submitted.trim();
    try {
      if (isProxyAllowlist(field.key()) && !"(?!)".equals(value)) {
        for (String network : value.split(",", -1)) {
          String cidr = network.trim();
          if (!cidr.matches("(?:[0-9.]+|[0-9A-Fa-f]*:[0-9A-Fa-f:.]*)/[0-9]{1,3}"))
            throw invalid(field, "use numeric CIDRs or (?!) to trust nobody");
          new org.apache.catalina.util.NetMask(cidr);
        }
      }
      if (field.key().equals("app.webhook.signing-secret")
          && !value.isBlank()
          && !info.trizub.clamav.webclient.service.WebhookSignature.validSecret(value))
        throw invalid(field, "use at least 32 UTF-8 bytes (maximum 4096)");
      if (field.secret()
          && field.section().matches("ldap|oidc")
          && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024)
        throw invalid(field, "credential exceeds 1024 bytes");
      if (field.key().equals("center.clamd.name") && !value.matches("[A-Za-z0-9 _.\\-]{1,64}"))
        throw invalid(field, "use 1–64 letters, numbers, spaces or punctuation");
      if (field.key().equals("clamav.service.host")
          && (value.isBlank()
              || value.length() > 255
              || !value.startsWith("/") && !value.matches("[A-Za-z0-9_.:\\[\\]\\-]+")))
        throw invalid(field, "use a hostname, IP or absolute socket path");
      if (Set.of("CLAMAV_REST_FORM_KEY", "CLAMAV_REST_WEBHOOK_FIELD").contains(field.key())
          && !value.matches("[A-Za-z][A-Za-z0-9_-]{0,50}"))
        throw invalid(
            field, "use 1–51 letters, numbers, underscores or hyphens, starting with a letter");
      if (Set.of("app.storage.uploadDir", "app.storage.quarantineDir").contains(field.key()))
        validateStorageDirectory(field, value);
      if (field.key().equals("spring.flyway.baseline-version")
          && !value.matches("[0-9]+(?:[._][0-9]+)*"))
        throw invalid(field, "use a numeric migration version");
      if (field.key().equals("clamav.public-url")
          && !value.isBlank()
          && !value.startsWith("https://"))
        throw invalid(field, "use an HTTPS application base URL");
      if (field.key().equals("server.servlet.context-path")
          && !value.isEmpty()
          && !value.matches("/[A-Za-z0-9_/-]*[A-Za-z0-9_-]"))
        throw invalid(field, "use an absolute prefix without a trailing slash");
      if (field.key().equals("management.server.port")
          && !value.isBlank()
          && (Integer.parseInt(value) < 1 || Integer.parseInt(value) > 65535))
        throw invalid(field, "use a valid port");
      if (field.key().startsWith("CLAMAV_REST_")
          && field.key().endsWith("_PATH")
          && (!value.startsWith("/")
              || value.startsWith("//")
              || value.indexOf('?') >= 0
              || value.indexOf('#') >= 0
              || value.indexOf(':') >= 0
              || value.indexOf('\\') >= 0
              || value.contains("..")))
        throw invalid(field, "use an absolute API path without traversal or a query");
      if (field.key().startsWith("CLAMAV_REST_") && field.key().endsWith("_PATH"))
        URI.create(value);
      if (field.key().equals("spring.servlet.multipart.location"))
        validateMultipartDirectory(field, value);
      if (Set.of(
              "spring.servlet.multipart.max-file-size", "spring.servlet.multipart.max-request-size")
          .contains(field.key())) {
        long bytes = org.springframework.util.unit.DataSize.parse(value).toBytes();
        if (bytes < 1 || bytes > 2147483648L)
          throw invalid(field, "use a size from 1 byte to 2048MB");
      }
      if (field.key().equals("clamav.identity.ldap.directory-id"))
        info.trizub.clamav.webclient.identity.LdapDirectoryIdentityPolicy.validate(value);
      if (field.key().equals("clamav.identity.oidc.scopes"))
        value = String.join(",", info.trizub.clamav.webclient.identity.OidcScopes.parse(value));
      switch (field.type()) {
        case "boolean" -> {
          if (!Set.of("true", "false").contains(value))
            throw invalid(field, "choose enabled or disabled");
        }
        case "number" -> {
          long number = Long.parseLong(value);
          if (number < field.minimum() || number > field.maximum())
            throw invalid(field, "outside the supported range");
          value = Long.toString(number);
        }
        case "duration" -> {
          Duration duration = DurationStyle.detectAndParse(value);
          if (field.key().equals("clamav.security.session.max-age")) {
            if (duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofHours(24)) > 0)
              throw invalid(field, "use 1s to 24h");
          } else if (field.key().equals("server.servlet.session.timeout")) {
            if (duration.compareTo(Duration.ofMinutes(1)) < 0
                || duration.compareTo(Duration.ofHours(24)) > 0)
              throw invalid(field, "use 1m to 24h");
          } else if (duration.toMillis() < 1 || duration.toMillis() > 300_000)
            throw invalid(field, "use 1ms to 5m");
        }
        case "select" -> {
          if (!field.choices().contains(value)) throw invalid(field, "unsupported option");
        }
        case "url" -> {
          if (!value.isEmpty()) checkHttp(field, value);
        }
        case "path" -> {
          if (!value.isEmpty()) Path.of(value);
        }
        case "jdbc" -> checkJdbc(value);
        case "timezone" -> ZoneId.of(value);
        default -> {}
      }
    } catch (IllegalArgumentException e) {
      // Preserve field-specific explanations instead of replacing them with
      // an unhelpful generic message. Never include submitted raw values.
      if (e.getMessage() != null && e.getMessage().startsWith(field.label() + ": ")) throw e;
      throw invalid(field, "invalid value");
    }
    return value;
  }

  /** The Tomcat spool must never be inside scanner-readable upload or quarantine trees. */
  private static void validateMultipartDirectory(ConfigurationSetting field, String value) {
    if (value.isEmpty()) return; // Explicitly use the servlet container's OS temp directory.
    Path path = Path.of(value);
    if (!path.isAbsolute() || !path.normalize().equals(path))
      throw invalid(field, "use an absolute, normalized directory path, or leave blank");
    // Reject symlinks before testing whether the destination is a directory.
    // Otherwise an existing symlink reports a misleading "not a directory"
    // error and clients cannot distinguish the security rejection.
    for (Path parent = path; parent != null; parent = parent.getParent())
      if (Files.isSymbolicLink(parent))
        throw invalid(field, "directory and its parents must not be symbolic links");
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(path))
      throw invalid(
          field, "directory must already exist and be writable inside the Web UI container");
    try {
      Set<java.nio.file.attribute.PosixFilePermission> modes = Files.getPosixFilePermissions(path);
      if (modes.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
          || modes.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
          || modes.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE)
          || modes.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
          || modes.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
          || modes.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE))
        throw invalid(field, "use a private directory (0700 permissions), owned by the app user");
    } catch (UnsupportedOperationException e) {
      // Non-POSIX platforms still require an existing, writable, non-symlink path.
    } catch (java.io.IOException e) {
      throw invalid(field, "cannot inspect temporary directory permissions");
    }
  }

  private static void validateStorageDirectory(ConfigurationSetting field, String value) {
    if (value.isBlank()) throw invalid(field, "choose a storage directory");
    // Preserve the two shipped legacy defaults, which resolve under the app's
    // working directory. All *custom* paths must be absolute so that Docker
    // mount mismatches and parent-directory surprises are detected on review.
    if (value.equals(field.defaultValue())) return;
    Path path = Path.of(value);
    if (!path.isAbsolute() || !path.normalize().equals(path))
      throw invalid(field, "use an absolute, normalized directory path (or the built-in default)");
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(path))
      throw invalid(
          field, "directory must already exist and be writable inside the Web UI container");
  }

  private static IllegalArgumentException invalid(ConfigurationSetting field, String reason) {
    // Deliberately omit the submitted value, including token-bearing URLs.
    return new IllegalArgumentException(field.label() + ": " + reason);
  }

  private static void checkHttp(ConfigurationSetting field, String value) {
    URI uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null)
      throw invalid(field, "use an HTTP(S) address without credentials or a query");
  }

  private static boolean hasNetworkUserInfo(String url) {
    int start = url.indexOf("://") + 3;
    int end = url.indexOf('/', start);
    int at = url.indexOf('@', start);
    return at >= 0 && (end < 0 || at < end);
  }

  public static void checkJdbc(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    boolean network =
        lower.startsWith("jdbc:postgresql://")
            || lower.startsWith("jdbc:mysql://")
            || lower.startsWith("jdbc:mariadb://");
    if (!(network
        || lower.startsWith("jdbc:h2:file:")
        || lower.matches("jdbc:h2:mem:[a-z0-9_-]+(?:;.*)?")))
      throw new IllegalArgumentException("Database URL: unsupported JDBC connection");
    if (lower.matches(
            ".*(?:[;?&])(?:init|runscript|autoconvert|auto_server|password|user|trace_level_file|trace_level_system_out)\\s*=.*")
        || lower.contains("\\n")
        || lower.contains("\\r")
        || (network && hasNetworkUserInfo(lower)))
      throw new IllegalArgumentException(
          "Database URL: use dedicated credential fields and safe connection options");
    if (lower.startsWith("jdbc:h2:"))
      for (String option : url.split(";", -1)) {
        if (option.startsWith("jdbc:")) continue;
        String[] pair = option.split("=", 2);
        if (pair.length != 2
            || !Set.of(
                    "MODE",
                    "DB_CLOSE_DELAY",
                    "DB_CLOSE_ON_EXIT",
                    "IFEXISTS",
                    "LOCK_TIMEOUT",
                    "ACCESS_MODE_DATA")
                .contains(pair[0].toUpperCase(Locale.ROOT))
            || !pair[1].matches("[A-Za-z0-9_-]{1,32}"))
          throw new IllegalArgumentException("Database URL: unsupported H2 connection option");
      }
    if (lower.startsWith("jdbc:h2:file:")) {
      // #36: file-backed H2 must close when the pool and JVM terminate.
      // DB_CLOSE_DELAY=-1 persists in H2 files. Prevent disabling closure
      // indefinitely; the packaged default explicitly resets the delay to 0.
      for (String option : url.split(";", -1)) {
        String[] pair = option.split("=", 2);
        if (pair.length == 2
            && pair[0].equalsIgnoreCase("DB_CLOSE_DELAY")
            && pair[1].startsWith("-"))
          throw new IllegalArgumentException(
              "Database URL: file-backed H2 must close after the last connection");
        if (pair.length == 2
            && pair[0].equalsIgnoreCase("DB_CLOSE_ON_EXIT")
            && pair[1].equalsIgnoreCase("FALSE"))
          throw new IllegalArgumentException("Database URL: file-backed H2 must close on JVM exit");
      }
      String filename = url.substring(13).split(";", 2)[0];
      if (!Path.of(filename)
          .toAbsolutePath()
          .normalize()
          .startsWith(Path.of("data").toAbsolutePath().normalize()))
        throw new IllegalArgumentException(
            "Database URL: H2 storage must be under the application data directory");
    }
  }

  public static Map<String, String> provider(String name, Map<String, String> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var field : all())
      if (field.section().equals(name) && !field.secret() && !field.legacy().isBlank())
        result.put(field.legacy(), values.getOrDefault(field.key(), field.defaultValue()));
    return result;
  }

  public static String jdbcDriver(String url) {
    checkJdbc(url);
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("jdbc:h2:")
        ? "org.h2.Driver"
        : lower.startsWith("jdbc:postgresql:")
            ? "org.postgresql.Driver"
            : lower.startsWith("jdbc:mariadb:")
                ? "org.mariadb.jdbc.Driver"
                : "com.mysql.cj.jdbc.Driver";
  }

  public static void validateCombination(Map<String, String> values, Set<String> changed) {
    if (changed.contains("spring.servlet.multipart.max-file-size")
        || changed.contains("spring.servlet.multipart.max-request-size")) {
      long perFile =
          org.springframework.util.unit.DataSize.parse(
                  values.get("spring.servlet.multipart.max-file-size"))
              .toBytes();
      long request =
          org.springframework.util.unit.DataSize.parse(
                  values.get("spring.servlet.multipart.max-request-size"))
              .toBytes();
      // Multipart framing (headers, boundary and other fields) is part of
      // the request body. Always leave at least 64 KiB beyond one file.
      if (request - perFile < 65_536L)
        throw new IllegalArgumentException(
            "HTTP multipart request ceiling must exceed the file ceiling by at least 64 KiB");
    }
    if (changed.contains("spring.servlet.multipart.location")
        || changed.contains("app.storage.uploadDir")
        || changed.contains("app.storage.quarantineDir")) {
      String spool = values.getOrDefault("spring.servlet.multipart.location", "");
      if (!spool.isBlank()) {
        Path temporary = Path.of(spool).toAbsolutePath().normalize();
        for (String key : List.of("app.storage.uploadDir", "app.storage.quarantineDir")) {
          Path protectedDir = Path.of(values.get(key)).toAbsolutePath().normalize();
          if (temporary.startsWith(protectedDir) || protectedDir.startsWith(temporary))
            throw new IllegalArgumentException(
                "Multipart temporary directory must not overlap uploads or quarantine");
        }
      }
    }
    if (changed.contains("app.storage.uploadDir")
        || changed.contains("app.storage.quarantineDir")) {
      Path uploads = Path.of(values.get("app.storage.uploadDir")).toAbsolutePath().normalize();
      Path quarantine =
          Path.of(values.get("app.storage.quarantineDir")).toAbsolutePath().normalize();
      if (quarantine.startsWith(uploads) || uploads.startsWith(quarantine))
        throw new IllegalArgumentException(
            "Upload and quarantine directories must be separate, non-overlapping paths");
    }
    if (changed.stream().anyMatch(k -> k.startsWith("spring.datasource."))) {
      String url = values.get("spring.datasource.url"),
          driver = values.getOrDefault("spring.datasource.driver-class-name", "");
      checkJdbc(url);
      String expected = jdbcDriver(url);
      if (!driver.isBlank() && !driver.equals(expected))
        throw new IllegalArgumentException(
            "Database driver must match the selected JDBC URL; leave it blank for automatic detection");
    }
    if (changed.contains("javax.net.ssl.trustStore")
        && !values.get("javax.net.ssl.trustStore").isBlank()
        && (!java.nio.file.Files.isRegularFile(Path.of(values.get("javax.net.ssl.trustStore")))
            || !java.nio.file.Files.isReadable(Path.of(values.get("javax.net.ssl.trustStore")))))
      throw new IllegalArgumentException(
          "Truststore must already exist and be readable inside this container");
    if (changed.stream().anyMatch(k -> k.startsWith("clamav.identity."))) {
      for (String provider : List.of("ldap", "oidc")) {
        var fields = provider(provider, values);
        String secret =
            values.getOrDefault(
                provider.equals("ldap")
                    ? "clamav.identity.ldap.manager-password"
                    : "clamav.identity.oidc.client-secret",
                "");
        AuthenticationSettingsService.validate(provider, fields, secret);
      }
    }
    if (changed.stream()
        .anyMatch(k -> k.startsWith("clamav.webhook.") || k.startsWith("app.webhook."))) {
      var policy = new WebhookNotificationProperties();
      policy.setAllowPrivateAddresses(
          Boolean.parseBoolean(values.get("clamav.webhook.allow-private-addresses")));
      if ("true".equals(values.get("app.webhook.enabled"))) {
        if (!info.trizub.clamav.webclient.service.WebhookSignature.validSecret(
            values.getOrDefault("app.webhook.signing-secret", "")))
          throw new IllegalArgumentException(
              "Webhook notifications require a signing secret of at least 32 bytes");
        String destination = values.getOrDefault("app.webhook.url", "");
        if (destination.isBlank())
          throw new IllegalArgumentException("Webhook URL is required before enabling delivery");
        new WebhookAddressPolicy(policy).validateUrl(destination);
      }
      Duration dns = DurationStyle.detectAndParse(values.get("clamav.webhook.dns-timeout"));
      Duration total = DurationStyle.detectAndParse(values.get("clamav.webhook.delivery-timeout"));
      if (dns.compareTo(total) > 0)
        throw new IllegalArgumentException(
            "DNS timeout must not exceed the total delivery timeout");
    }
    if (changed.stream()
        .anyMatch(key -> key.startsWith("CLAMAV_REST_") || key.equals("clamav.rest.enabled"))) {
      // Reject an unsafe combination during Review/Apply, not only on next startup.
      RestTransportPolicy.validate(
          values.getOrDefault("CLAMAV_REST_URL", ""),
          values.getOrDefault("CLAMAV_REST_API_KEY", ""),
          Boolean.parseBoolean(values.getOrDefault("CLAMAV_REST_ALLOW_INSECURE", "false")));
    }
    if ("true".equals(values.get("clamav.rest.enabled"))
        && values.getOrDefault("CLAMAV_REST_URL", "").isBlank()
        && changed.contains("clamav.rest.enabled"))
      throw new IllegalArgumentException("External REST URL is required before enabling it");
    if ("true".equals(values.get("spring.flyway.baseline-on-migrate"))
        && !"1".equals(values.get("spring.flyway.baseline-version")))
      throw new IllegalArgumentException(
          "Legacy baselining must use version 1 after a reviewed database backup");
    if ("none".equals(values.get("server.servlet.session.cookie.same-site"))
        && !"true".equals(values.get("server.servlet.session.cookie.secure")))
      throw new IllegalArgumentException("SameSite=None requires secure session cookies");
  }
}
