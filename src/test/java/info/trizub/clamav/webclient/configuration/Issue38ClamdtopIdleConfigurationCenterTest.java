package info.trizub.clamav.webclient.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.web.NativeClamdtopWebSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Regression: issue #38 timeout must be manageable from Configuration Center, not Compose only. */
class Issue38ClamdtopIdleConfigurationCenterTest {
  private static final String KEY = "clamav.clamdtop.idle-timeout-seconds";
  private static final String ENV = "CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS";
  private static final String PACKAGED = "class path resource [application.properties]";
  @TempDir Path dir;

  private ConfigurationStore store() {
    return new ConfigurationStore(
        dir.resolve("configuration-center.properties"), dir.resolve("configuration-center.key"));
  }

  private MockEnvironment environment(ConfigurationStore store) {
    var env = new MockEnvironment();
    env.getPropertySources()
        .addLast(new MapPropertySource(PACKAGED, Map.of(KEY, "${" + ENV + ":180}")));
    env.getPropertySources().addBefore(PACKAGED, new ConfigurationSource(store));
    return env;
  }

  @Test
  void nativeMonitorFieldIsIndependentOfPrometheusAndHasBounds() {
    var field = ConfigurationCatalog.get(KEY);
    assertThat(field.label()).isEqualTo("Native ClamdTop idle timeout (seconds)");
    assertThat(field.category()).isEqualTo("Monitoring");
    assertThat(field.section()).isEqualTo("clamdtop");
    assertThat(field.type()).isEqualTo("number");
    assertThat(field.defaultValue()).isEqualTo("180");
    assertThat(field.environment()).containsExactly(ENV);
    assertThat(field.live()).isFalse();
    assertThat(field.minimum()).isEqualTo(1);
    assertThat(field.maximum()).isEqualTo(86_400);
    var section =
        ConfigurationCatalog.document().sections().stream()
            .filter(item -> item.id().equals("clamdtop"))
            .findFirst()
            .orElseThrow();
    assertThat(section.enableKey()).isEmpty();
    assertThat(section.category()).isEqualTo("Monitoring");
    for (String valid : new String[] {"1", "90", "180", "86400"})
      assertThat(ConfigurationCatalog.validate(field, valid)).isEqualTo(valid);
    for (String invalid : new String[] {"", "0", "-1", "86401", "1.5", "abc"})
      assertThatThrownBy(() -> ConfigurationCatalog.validate(field, invalid))
          .as("Rejected invalid ClamdTop timeout " + invalid)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field.label());
  }

  @Test
  void savesFromGuiRetainsOldActiveValueUntilRestart() {
    var env = environment(store());
    var engine = new ConfigurationEngine(env, store(), Map::of);
    assertThat(engine.locked(KEY)).isFalse();
    assertThat(env.getProperty(KEY)).isEqualTo("180");

    var review = engine.review("0", Map.of(KEY, "300"), Set.of());
    engine.apply(review, true, "admin");
    assertThat(engine.view().get("pendingRestart").toString()).contains(KEY);
    assertThat(env.getProperty(KEY)).isEqualTo("180");
    assertThat(store().saved(KEY)).contains("300");

    var restartEnv = environment(store());
    assertThat(restartEnv.getProperty(KEY)).isEqualTo("300");
    assertThat(new ConfigurationEngine(restartEnv, store(), Map::of).values(false).get(KEY))
        .isEqualTo("300");
  }

  @Test
  void composeOverridesSavedGuiSettingAndLocksItWithoutErasingIt() {
    var initial = store();
    initial.save("0", Map.of(KEY, "300"), Set.of(), true, "admin");
    var env = environment(store());
    env.getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource("deployment", Map.of(ENV, "600")));
    var engine = new ConfigurationEngine(env, store(), Map::of);
    assertThat(engine.locked(KEY)).isTrue();
    assertThat(engine.values(false).get(KEY)).isEqualTo("600");
    assertThat(env.getProperty(KEY)).isEqualTo("600");
    assertThatThrownBy(() -> engine.review("1", Map.of(KEY, "240"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deployment configuration");
    assertThat(store().saved(KEY)).contains("300");
    env.getPropertySources().remove("deployment");
    assertThat(environment(store()).getProperty(KEY)).isEqualTo("300");
  }

  @Test
  void actualSpringConstructorReceivesSavedSettingAtStartup() {
    var initial = store();
    initial.save("0", Map.of(KEY, "420"), Set.of(), true, "admin");
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addLast(new MapPropertySource(PACKAGED, Map.of(KEY, "${" + ENV + ":180}")));
      context
          .getEnvironment()
          .getPropertySources()
          .addBefore(PACKAGED, new ConfigurationSource(store()));
      context.registerBean(EndpointService.class, () -> mock(EndpointService.class));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(NativeClamdtopWebSocket.class);
      context.refresh();
      var handler = context.getBean(NativeClamdtopWebSocket.class);
      assertThat(ReflectionTestUtils.getField(handler, "idleTimeoutNanos"))
          .isEqualTo(TimeUnit.SECONDS.toNanos(420));
    }
  }
}
