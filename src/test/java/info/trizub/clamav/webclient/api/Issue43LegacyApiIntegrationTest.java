package info.trizub.clamav.webclient.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.EndpointService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import xyz.capybara.clamav.Platform;

/**
 * Regression suite for issue #43, exercising real MVC, endpoint resolution and audit persistence.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:issue43-legacy-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=update",
      "clamav.bootstrap.admin.password=Issue43IsolatedAdminPassword123456",
      "clamav.metrics.clamd-initial-delay-ms=3600000",
      "clamav.config.initial-delay-ms=3600000"
    })
@AutoConfigureMockMvc
class Issue43LegacyApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired EndpointService endpoints;
  @Autowired ScanJobRepository jobs;
  @Autowired AuditEventRepository audit;

  private ClamdEndpoint disabled() {
    return endpoints.create(
        "issue43-disabled-" + UUID.randomUUID(), "127.0.0.1", 3310, Platform.UNIX, false);
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void unknownEndpointReturnsJson404ForPathUploadAndMetricsWithoutEnqueueing() throws Exception {
    long before = jobs.count();
    String missing = "9223372036854775807";
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/anything\",\"endpointId\":" + missing + "}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("not_found"))
        .andExpect(jsonPath("$.message").value("Not Found"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(
            multipart("/api/scan/upload")
                .file(new MockMultipartFile("files", "test.txt", "text/plain", "abc".getBytes()))
                .param("endpointId", missing)
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
    mvc.perform(get("/api/clamdtop").param("endpointId", missing))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
    mvc.perform(get("/api/v1/metrics/clamd").param("endpointId", missing))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
    assertThat(jobs.count()).isEqualTo(before);
    assertThat(audit.findTop200ByOrderByAtDesc())
        .anyMatch(
            e ->
                "SCAN_PATH".equals(e.getAction())
                    && "FAILED".equals(e.getOutcome())
                    && e.getJobId() == null);
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void disabledEndpointReturnsJson400ForLegacyAndV1BeforeSubmission() throws Exception {
    ClamdEndpoint ep = disabled();
    String id = ep.getId().toString();
    long before = jobs.count();
    String body = "{\"path\":\"/anything\",\"endpointId\":" + id + "}";
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(
            multipart("/api/scan/upload")
                .file(new MockMultipartFile("files", "test.txt", "text/plain", "abc".getBytes()))
                .param("endpointId", id)
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(
            post("/api/v1/scans/paths")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(
            multipart("/api/v1/scans/uploads")
                .file(new MockMultipartFile("files", "test.txt", "text/plain", "abc".getBytes()))
                .param("endpointId", id)
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    assertThat(jobs.count()).isEqualTo(before);
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void commonClientErrorsReturnSafeJsonInsteadOf500OrRawException() throws Exception {
    mvc.perform(get("/api/jobs/issue43-no-such-job"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not valid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("JsonParseException"))));
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"   \",\"endpointId\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/tmp/private\",\"endpointId\":\"bad-id\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/tmp/private"))));
    mvc.perform(
            multipart("/api/scan/upload")
                .file(new MockMultipartFile("files", "bad.txt", "text/plain", new byte[] {1}))
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_request"));
  }
}
