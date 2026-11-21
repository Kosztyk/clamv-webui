package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** Regression: signing and privacy fields are first-class Configuration Center settings. */
class Issue39WebhookConfigurationCenterTest {
  private static final String SECRET = "app.webhook.signing-secret";
  private static final String PATHS = "app.webhook.include-paths";
  private static final String ERRORS = "app.webhook.include-error-details";
  private static final String IDENTITY = "app.webhook.include-identity";
  private static final String VALUE = "correct-hmac-webhook-secret-0123456789abcdef";
  private static final String PACKAGED = "class path resource [application.properties]";
  @TempDir Path dir;

  private ConfigurationStore store() {
    return new ConfigurationStore(
        dir.resolve("configuration-center.properties"), dir.resolve("configuration-center.key"));
  }

  private MockEnvironment environment(ConfigurationStore storage) {
    var env = new MockEnvironment();
    env.getPropertySources()
        .addLast(
            new MapPropertySource(
                PACKAGED,
                Map.of(
                    SECRET, "${CLAMAV_WEBHOOK_SIGNING_SECRET:}",
                    PATHS, "${CLAMAV_WEBHOOK_INCLUDE_PATHS:false}",
                    ERRORS, "${CLAMAV_WEBHOOK_INCLUDE_ERROR_DETAILS:false}",
                    IDENTITY, "${CLAMAV_WEBHOOK_INCLUDE_IDENTITY:false}")));
    env.getPropertySources().addBefore(PACKAGED, new ConfigurationSource(storage));
    return env;
  }

  @Test
  void catalogExposesWriteOnlySigningKeyAndPrivacyControls() {
    var key = ConfigurationCatalog.get(SECRET);
    assertThat(key.secret()).isTrue();
    assertThat(key.section()).isEqualTo("webhook");
    assertThat(key.environment()).containsExactly("CLAMAV_WEBHOOK_SIGNING_SECRET");
    assertThat(key.live()).isTrue();
    assertThat(ConfigurationCatalog.validate(key, VALUE)).isEqualTo(VALUE);
    assertThatThrownBy(() -> ConfigurationCatalog.validate(key, "too-short"))
        .isInstanceOf(IllegalArgumentException.class);
    for (var entry :
        Map.of(
                PATHS,
                "CLAMAV_WEBHOOK_INCLUDE_PATHS",
                ERRORS,
                "CLAMAV_WEBHOOK_INCLUDE_ERROR_DETAILS",
                IDENTITY,
                "CLAMAV_WEBHOOK_INCLUDE_IDENTITY")
            .entrySet()) {
      var setting = ConfigurationCatalog.get(entry.getKey());
      assertThat(setting.type()).isEqualTo("boolean");
      assertThat(setting.defaultValue()).isEqualTo("false");
      assertThat(setting.environment()).containsExactly(entry.getValue());
      assertThat(setting.live()).isTrue();
    }
  }

  @Test
  void savedSecretIsEncryptedAtRestAndImmediatelyAvailableToRunningSettings() throws Exception {
    var storage = store();
    var env = environment(storage);
    var engine = new ConfigurationEngine(env, storage, Map::of);
    var change = engine.review("0", Map.of(SECRET, VALUE, PATHS, "true"), Set.of());
    engine.apply(change, true, "admin");
    assertThat(storage.saved(SECRET)).contains(VALUE);
    assertThat(storage.active(SECRET)).contains(VALUE);
    assertThat(env.getProperty(SECRET)).isEqualTo(VALUE);
    assertThat(env.getProperty(PATHS)).isEqualTo("true");
    assertThat(Files.readString(storage.file())).doesNotContain(VALUE);
    assertThat(Files.readString(storage.file())).contains("app.webhook.signing-secret");
    var restarted = store();
    assertThat(restarted.saved(SECRET)).contains(VALUE);
  }

  @Test
  void dockerEnvironmentOverridesAndLocksGuiFieldsWithoutDeletingSavedPreferences() {
    var storage = store();
    storage.save("0", Map.of(SECRET, VALUE, PATHS, "true"), Set.of(), true, "admin");
    var env = environment(store());
    env.getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "deployment",
                Map.of(
                    "CLAMAV_WEBHOOK_SIGNING_SECRET",
                        "alternate-docker-webhook-secret-0123456789abcdef",
                    "CLAMAV_WEBHOOK_INCLUDE_PATHS", "false")));
    var engine = new ConfigurationEngine(env, store(), Map::of);
    assertThat(engine.locked(SECRET)).isTrue();
    assertThat(engine.locked(PATHS)).isTrue();
    assertThat(engine.values(false).get(PATHS)).isEqualTo("false");
    assertThatThrownBy(() -> engine.review("1", Map.of(PATHS, "true"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deployment configuration");
    assertThat(storage.saved(PATHS)).contains("true");
    assertThat(storage.saved(SECRET)).contains(VALUE);
  }
}
