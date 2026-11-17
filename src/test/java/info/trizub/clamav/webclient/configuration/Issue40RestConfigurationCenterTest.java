package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.config.RestApiConfig;
import info.trizub.clamav.webclient.service.RestApiBridge;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class Issue40RestConfigurationCenterTest {
  private static final String KEY = "CLAMAV_REST_ALLOW_INSECURE";
  private static final String PACKAGED = "class path resource [application.properties]";
  @TempDir Path dir;

  private ConfigurationStore store() {
    return new ConfigurationStore(
        dir.resolve("configuration-center.properties"), dir.resolve("configuration-center.key"));
  }

  private MockEnvironment environment(ConfigurationStore store) {
    var env = new MockEnvironment();
    env.getPropertySources().addLast(new MapPropertySource(PACKAGED, Map.of(KEY, "false")));
    env.getPropertySources().addBefore(PACKAGED, new ConfigurationSource(store));
    return env;
  }

  @Test
  void securityOverrideIsExposedAsRestartRequiredDefaultFalse() {
    var field = ConfigurationCatalog.get("CLAMAV_REST_ALLOW_INSECURE");
    assertThat(field.section()).isEqualTo("rest");
    assertThat(field.defaultValue()).isEqualTo("false");
    assertThat(field.environment()).contains("CLAMAV_REST_ALLOW_INSECURE");
    assertThat(field.apply()).isEqualTo("restart");
    assertThat(field.sensitive()).isTrue();
  }

  @Test
  void savedValueIsRestartRequiredAndComposeOverridesAndLocksGui() {
    var store = store();
    var env = environment(store);
    var engine = new ConfigurationEngine(env, store, Map::of);
    assertThat(env.getProperty(KEY)).isEqualTo("false");
    var review = engine.review("0", Map.of(KEY, "true"), Set.of());
    engine.apply(review, true, "admin");
    assertThat(engine.view().get("pendingRestart").toString()).contains(KEY);
    assertThat(env.getProperty(KEY)).isEqualTo("false");
    assertThat(environment(store()).getProperty(KEY)).isEqualTo("true");

    var envWithOverride = environment(store());
    envWithOverride
        .getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource("deployment", Map.of(KEY, "false")));
    var locked = new ConfigurationEngine(envWithOverride, store(), Map::of);
    assertThat(locked.locked(KEY)).isTrue();
    assertThat(locked.values(false).get(KEY)).isEqualTo("false");
    assertThatThrownBy(() -> locked.review("1", Map.of(KEY, "true"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deployment configuration");
  }

  @Test
  void bothSpringClientsReadPersistedOptInAtStartup() {
    var store = store();
    store.save(
        "0",
        Map.of(
            KEY,
            "true",
            "CLAMAV_REST_URL",
            "http://scanner.internal:3000",
            "CLAMAV_REST_API_KEY",
            "synthetic-key"),
        Set.of(),
        true,
        "admin");
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addLast(new MapPropertySource(PACKAGED, Map.of(KEY, "false")));
      context
          .getEnvironment()
          .getPropertySources()
          .addBefore(PACKAGED, new ConfigurationSource(store()));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(RestApiBridge.class);
      context.registerBean(RestApiConfig.class);
      context.refresh();
      assertThat(context.getBean(RestApiBridge.class).configured()).isTrue();
      assertThat(context.getBean(RestApiConfig.class).enabled()).isTrue();
    }
  }

  @Test
  void rejectsUnsafeCombinationDuringConfigurationCenterReviewValidation() {
    Map<String, String> values = new HashMap<>(ConfigurationCatalog.defaults());
    values.put("CLAMAV_REST_URL", "http://192.0.2.144:3000");
    values.put("CLAMAV_REST_API_KEY", "synthetic-secret");
    assertThatThrownBy(
            () -> ConfigurationCatalog.validateCombination(values, Set.of("CLAMAV_REST_API_KEY")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CLAMAV_REST_ALLOW_INSECURE");
    values.put("CLAMAV_REST_ALLOW_INSECURE", "true");
    assertThatCode(
            () ->
                ConfigurationCatalog.validateCombination(
                    values, Set.of("CLAMAV_REST_ALLOW_INSECURE")))
        .doesNotThrowAnyException();
    values.put("CLAMAV_REST_ALLOW_INSECURE", "false");
    values.put("CLAMAV_REST_URL", "https://scanner.internal:443");
    assertThatCode(
            () -> ConfigurationCatalog.validateCombination(values, Set.of("CLAMAV_REST_URL")))
        .doesNotThrowAnyException();
  }
}
