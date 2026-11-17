package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;

import info.trizub.clamav.webclient.service.ClamdTopService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The clamdtop cache TTL is editable in Configuration Center unless set by deployment. */
class Issue31CacheTtlConfigurationCenterTest {
  private static final String KEY = "clamav.metrics.clamdtop-cache-ttl-ms";
  @TempDir Path dir;

  private ConfigurationStore store() {
    return new ConfigurationStore(dir.resolve("center.properties"), dir.resolve("center.key"));
  }

  private MockEnvironment environment(ConfigurationStore store) {
    var env = new MockEnvironment();
    env.getPropertySources()
        .addLast(
            new MapPropertySource(
                "class path resource [application.properties]",
                Map.of(KEY, "${CLAMAV_CLAMDTOP_CACHE_TTL_MS:2000}")));
    env.getPropertySources()
        .addBefore("class path resource [application.properties]", new ConfigurationSource(store));
    return env;
  }

  @Test
  void monitoringFieldIsBoundedAndRestartRequired() {
    var field = ConfigurationCatalog.get(KEY);
    assertThat(field.category()).isEqualTo("Monitoring");
    assertThat(field.section()).isEqualTo("metrics");
    assertThat(field.type()).isEqualTo("number");
    assertThat(field.defaultValue()).isEqualTo("2000");
    assertThat(field.environment()).containsExactly("CLAMAV_CLAMDTOP_CACHE_TTL_MS");
    assertThat(field.live()).isFalse();
    assertThat(field.minimum()).isEqualTo(1000);
    assertThat(field.maximum()).isEqualTo(3000);
    for (String accepted : new String[] {"1000", "2000", "3000"})
      assertThat(ConfigurationCatalog.validate(field, accepted)).isEqualTo(accepted);
    for (String rejected : new String[] {"0", "999", "3001", "-1", "1.5", "abc", ""})
      assertThatThrownBy(() -> ConfigurationCatalog.validate(field, rejected))
          .as(rejected)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field.label());
  }

  @Test
  void guiSavePersistsThroughRestartAndDeploymentOverrideLocksTheField() {
    var persisted = store();
    var env = environment(persisted);
    var engine = new ConfigurationEngine(env, persisted, Map::of);
    assertThat(engine.locked(KEY)).isFalse();
    assertThat(env.getProperty(KEY)).isEqualTo("2000");

    var review = engine.review("0", Map.of(KEY, "2500"), Set.of());
    engine.apply(review, true, "admin");
    assertThat(engine.view().get("pendingRestart").toString()).contains(KEY);
    assertThat(env.getProperty(KEY)).isEqualTo("2000"); // Active value is startup-bound.
    assertThat(ClamdTopService.resolveCacheTtlMillis(env)).isEqualTo(2000);

    var restartEnv = environment(store());
    assertThat(restartEnv.getProperty(KEY)).isEqualTo("2500");
    assertThat(ClamdTopService.resolveCacheTtlMillis(restartEnv)).isEqualTo(2500);
    var restartedEngine = new ConfigurationEngine(restartEnv, store(), Map::of);
    assertThat(restartedEngine.values(false).get(KEY)).isEqualTo("2500");

    restartEnv
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "deployment", Map.of("CLAMAV_CLAMDTOP_CACHE_TTL_MS", "1800")));
    assertThat(restartedEngine.locked(KEY)).isTrue();
    assertThat(ClamdTopService.resolveCacheTtlMillis(restartEnv)).isEqualTo(1800);
    assertThatThrownBy(() -> restartedEngine.review("1", Map.of(KEY, "2200"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deployment configuration");
  }
}
