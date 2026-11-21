package info.trizub.clamav.webclient.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Issue #37: liveness is intentionally anonymous; never expose other actuator internals. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:issue37-liveness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "clamav.bootstrap.admin.password=Issue37LocalTestPasswordOnly456",
      "spring.task.scheduling.enabled=false",
      "management.endpoint.health.probes.enabled=true"
    })
@AutoConfigureMockMvc
class Issue37LivenessSecurityTest {
  @Autowired MockMvc mvc;

  @Test
  void anonymousLivenessDoesNotExposeInternalComponents() throws Exception {
    String body =
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains("\"status\":\"UP\"")
        .doesNotContain("db", "diskSpace", "clamav", "components", "details");
  }

  @Test
  void otherActuatorEndpointsStillRequireCredentials() throws Exception {
    assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus())
        .isNotEqualTo(200);
    assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus())
        .isNotEqualTo(200);
    assertThat(mvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus())
        .isNotEqualTo(200);
    // Existing fail-closed scrape token is not weakened by the liveness exception.
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
  }

  @Test
  void framesAreDeniedAndH2ConsoleIsAbsent() throws Exception {
    mvc.perform(get("/login")).andExpect(header().string("X-Frame-Options", "DENY"));
    // The H2 console dependency is removed, including on authenticated admin requests.
    mvc.perform(get("/h2").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
  }
}
