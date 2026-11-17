package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class Issue30MultipartConfigurationCenterTest {
  @TempDir Path dir;
  private static final String KEY = "spring.servlet.multipart.location";

  ConfigurationStore store() {
    return new ConfigurationStore(dir.resolve("config.properties"), dir.resolve("config.key"));
  }

  Path privateDir(String name) throws Exception {
    Path p = Files.createDirectory(dir.resolve(name));
    try {
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    return p;
  }

  @Test
  void advancedFieldIsStartupBoundAndHasCorrectDeploymentAlias() {
    var field = ConfigurationCatalog.get(KEY);
    assertThat(field.section()).isEqualTo("scanning");
    assertThat(field.advanced()).isTrue();
    assertThat(field.live()).isFalse();
    assertThat(field.environment()).containsExactly("SPRING_SERVLET_MULTIPART_LOCATION");
    assertThat(ConfigurationCatalog.validate(field, "")).isEmpty();
    assertThat(
            ConfigurationCatalog.validate(
                ConfigurationCatalog.get("spring.servlet.multipart.max-request-size"), "16MB"))
        .isEqualTo("16MB");
  }

  @Test
  void multipartLimitsRequireRequestHeadroom() {
    var values = new HashMap<>(ConfigurationCatalog.defaults());
    assertThat(values.get("spring.servlet.multipart.max-file-size")).isEqualTo("2047MB");
    ConfigurationCatalog.validateCombination(
        values, Set.of("spring.servlet.multipart.max-file-size"));
    values.put("spring.servlet.multipart.max-file-size", "2048MB");
    assertThatThrownBy(
            () ->
                ConfigurationCatalog.validateCombination(
                    values, Set.of("spring.servlet.multipart.max-file-size")))
        .hasMessageContaining("64 KiB");
    values.put("spring.servlet.multipart.max-file-size", "2MB");
    values.put("spring.servlet.multipart.max-request-size", "2MB");
    assertThatThrownBy(
            () ->
                ConfigurationCatalog.validateCombination(
                    values, Set.of("spring.servlet.multipart.max-request-size")))
        .hasMessageContaining("64 KiB");
    values.put("spring.servlet.multipart.max-request-size", "3MB");
    ConfigurationCatalog.validateCombination(
        values, Set.of("spring.servlet.multipart.max-request-size"));
  }

  @Test
  void customDirectoryRequiresExistingPrivateNonSymlinkDirectory() throws Exception {
    var field = ConfigurationCatalog.get(KEY);
    Path valid = privateDir("valid");
    assertThat(ConfigurationCatalog.validate(field, valid.toString())).isEqualTo(valid.toString());
    for (String path :
        List.of("relative/path", "/tmp/../tmp/multipart", dir.resolve("missing").toString()))
      assertThatThrownBy(() -> ConfigurationCatalog.validate(field, path))
          .hasMessageContaining("Multipart temporary directory");
    Path alias = dir.resolve("alias");
    Files.createSymbolicLink(alias, valid);
    assertThatThrownBy(() -> ConfigurationCatalog.validate(field, alias.toString()))
        .hasMessageContaining("symbolic links");
    Path publicDir = Files.createDirectory(dir.resolve("public"));
    try {
      Files.setPosixFilePermissions(publicDir, PosixFilePermissions.fromString("rwxr-xr-x"));
      assertThatThrownBy(() -> ConfigurationCatalog.validate(field, publicDir.toString()))
          .hasMessageContaining("0700");
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @Test
  void spoolCannotOverlapUploadsOrQuarantine() throws Exception {
    Path uploads = privateDir("uploads"), quarantine = privateDir("quarantine");
    Path spool = privateDir("spool");
    Map<String, String> values = new HashMap<>(ConfigurationCatalog.defaults());
    values.put("app.storage.uploadDir", uploads.toString());
    values.put("app.storage.quarantineDir", quarantine.toString());
    values.put(KEY, spool.toString());
    ConfigurationCatalog.validateCombination(values, Set.of(KEY));
    values.put(KEY, uploads.toString());
    assertThatThrownBy(() -> ConfigurationCatalog.validateCombination(values, Set.of(KEY)))
        .hasMessageContaining("must not overlap uploads or quarantine");
  }

  @Test
  void dockerFallbackDoesNotLockUiAndSavedPathBindsAtRestart() throws Exception {
    var original = store();
    var env = new MockEnvironment();
    env.getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("CLAMAV_CONTAINER_MULTIPART_DEFAULT", "/app/data/tmp/multipart")));
    env.getPropertySources()
        .addLast(
            new MapPropertySource(
                "class path resource [application.properties]",
                Map.of(
                    KEY,
                    "${SPRING_SERVLET_MULTIPART_LOCATION:${CLAMAV_CONTAINER_MULTIPART_DEFAULT:}}")));
    env.getPropertySources()
        .addBefore(
            "class path resource [application.properties]", new ConfigurationSource(original));
    var engine = new ConfigurationEngine(env, original, Map::of);
    assertThat(engine.locked(KEY)).isFalse();
    assertThat(env.getProperty(KEY)).isEqualTo("/app/data/tmp/multipart");
    var custom = privateDir("custom");
    var review = engine.review("0", Map.of(KEY, custom.toString()), Set.of());
    engine.apply(review, true, "test");
    assertThat(engine.view().get("pendingRestart").toString()).contains(KEY);
    // A restart reopens the persistent configuration store.
    var restarted = new MockEnvironment();
    restarted
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("CLAMAV_CONTAINER_MULTIPART_DEFAULT", "/app/data/tmp/multipart")));
    restarted
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "class path resource [application.properties]",
                Map.of(
                    KEY,
                    "${SPRING_SERVLET_MULTIPART_LOCATION:${CLAMAV_CONTAINER_MULTIPART_DEFAULT:}}")));
    restarted
        .getPropertySources()
        .addBefore(
            "class path resource [application.properties]", new ConfigurationSource(store()));
    assertThat(restarted.getProperty(KEY)).isEqualTo(custom.toString());
    restarted
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "deployment", Map.of("SPRING_SERVLET_MULTIPART_LOCATION", "/custom/override")));
    assertThat(ConfigurationSource.override(restarted, ConfigurationCatalog.get(KEY))).isPresent();
    assertThat(restarted.getProperty(KEY)).isEqualTo("/custom/override");
  }
}
