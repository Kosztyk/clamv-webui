package info.trizub.clamav.webclient.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.service.RestApiBridge;
import info.trizub.clamav.webclient.service.RestJobHistoryCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression tests for the separated API Scan / API Reports interface. The external REST API is
 * mocked explicitly; running these tests never contacts a LAN service.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:rest-monitor-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.task.scheduling.enabled=false",
      "clamav.bootstrap.admin.password=RestMonitorTestOnlyPassword123456"
    })
@AutoConfigureMockMvc
class RestMonitorIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean RestApiBridge externalApi;
  @Autowired RestJobHistoryCache historyCache;

  @BeforeEach
  void externalServiceFixture() throws Exception {
    historyCache.invalidate();
    when(externalApi.configured()).thenReturn(true);
    // Mockito's numeric default is zero; the actual bridge enforces a
    // positive history cap, so mimic its normal 500-job setting.
    when(externalApi.reportMaxJobs()).thenReturn(500);
    when(externalApi.read(eq("summary"), anyMap()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                200,
                json.readTree(
                    "{\"success\":true,\"data\":{\"totals\":{\"files_scanned\":5},\"queue\":{\"running\":1}}}")));
    when(externalApi.read(eq("jobs"), anyMap()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                200,
                json.readTree(
                    "{\"success\":true,\"data\":{\"jobs\":[{\"job_id\":\"a1b2\",\"status\":\"completed\",\"verdict\":\"infected\"}]}}")));
    when(externalApi.retryWebhook(anyString()))
        .thenReturn(new RestApiBridge.ApiResponse(202, json.readTree("{\"accepted\":true}")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersAllIntegratedPagesWithoutExposingCredentials() throws Exception {
    mvc.perform(get("/dashboard"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("rest-api.js")))
        .andExpect(content().string(containsString("API Scan")))
        .andExpect(content().string(containsString("API Reports")));
    mvc.perform(get("/api-scan"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-rest-upload")))
        .andExpect(content().string(containsString("data-rest-webhooks")));
    mvc.perform(get("/api-reports"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-rest-filters")))
        .andExpect(content().string(containsString("data-rest-export")))
        .andExpect(content().string(not(containsString("secret-not-for-browser"))));
    mvc.perform(get("/js/rest-api.js")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void viewersSeeSummaryButCannotReadFileHistoryOrSubmit() throws Exception {
    mvc.perform(get("/rest-monitor/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.stats.totals.files_scanned").value(5));
    for (String endpoint :
        new String[] {"/rest-monitor/jobs", "/rest-monitor/history", "/api-scan", "/api-reports"})
      mvc.perform(get(endpoint)).andExpect(status().isForbidden());
    mvc.perform(post("/rest-monitor/upload").with(csrf())).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void retryRequiresCsrfAndOperatorRole() throws Exception {
    String uri = "/rest-monitor/jobs/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/webhook/retry";
    mvc.perform(post(uri)).andExpect(status().isForbidden());
    mvc.perform(post(uri).with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true));
    verify(externalApi, times(1)).retryWebhook("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void rejectsUnknownFiltersAndInvalidDates() throws Exception {
    mvc.perform(get("/rest-monitor/jobs").param("url", "http://other"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/rest-monitor/jobs").param("from", "invalid-date"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/rest-monitor/jobs")
                .param("from", "2026-10-29T13:00:00Z")
                .param("to", "2026-10-28T13:00:00Z"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/rest-monitor/jobs").param("page", "0")).andExpect(status().isBadRequest());
    mvc.perform(get("/rest-monitor/jobs").param("page", "1").param("page_size", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs").isArray());
    verify(externalApi, times(1)).read(eq("jobs"), anyMap());
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void nestedRestEnvelopeIsUnwrappedIntoVisibleJobs() throws Exception {
    mvc.perform(get("/rest-monitor/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.totals.files_scanned").value(5));
    mvc.perform(get("/rest-monitor/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].job_id").value("a1b2"))
        .andExpect(jsonPath("$.jobs[0].verdict").value("infected"))
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void paginationAndAnalyticsReuseHistorySnapshotWithoutRefetching() throws Exception {
    when(externalApi.read(eq("history"), anyMap()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                200,
                json.readTree(
                    "{\"jobs\":[{\"job_id\":\"a1b2\",\"status\":\"completed\"},"
                        + "{\"job_id\":\"b3c4\",\"status\":\"completed\"}]}")));
    mvc.perform(get("/rest-monitor/history").param("page", "1").param("page_size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].job_id").value("a1b2"));
    mvc.perform(get("/rest-monitor/history").param("page", "2").param("page_size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].job_id").value("b3c4"));
    mvc.perform(get("/rest-monitor/analytics")).andExpect(status().isOk());
    mvc.perform(get("/rest-monitor/export").param("format", "csv")).andExpect(status().isOk());
    verify(externalApi, times(1)).read(eq("history"), eq(java.util.Map.of()));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void csvExportProtectsFormulasWithoutCorruptingIdsOrOrdinaryFields() throws Exception {
    when(externalApi.read(eq("history"), anyMap()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                200,
                json.readTree(
                    """
                {"jobs":[
                    {"job_id":"-5","status":"completed","mode":"async","request_id":"==x"},
                    {"job_id":"+a","status":"clean","mode":"sync","request_id":"|cmd"},
                    {"job_id":"plain-id","status":"normal","mode":"async","request_id":"abc-5"},
                    {"job_id":"%value","status":"normal","mode":"sync","request_id":"hello,\\"world\\""}
                ]}
                """)));
    mvc.perform(get("/rest-monitor/export").param("format", "csv"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"'-5\"")))
        .andExpect(content().string(containsString("\"'==x\"")))
        .andExpect(content().string(containsString("\"'+a\"")))
        .andExpect(content().string(containsString("\"'|cmd\"")))
        .andExpect(content().string(containsString("\"'%value\"")))
        .andExpect(content().string(containsString("\"plain-id\"")))
        .andExpect(content().string(containsString("\"abc-5\"")))
        .andExpect(content().string(not(containsString("\"'5\""))));
    // JSON is deliberately lossless: consumers should use this format for raw values.
    mvc.perform(get("/rest-monitor/export").param("format", "json"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"job_id\":\"-5\"")))
        .andExpect(content().string(containsString("\"request_id\":\"==x\"")));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void acceptedAsyncScanExposesTheIdForBrowserPolling() throws Exception {
    when(externalApi.upload(anyList(), any()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                202,
                json.readTree(
                    "{\"success\":true,\"data\":{\"job_id\":\"a1b2\",\"status\":\"queued\"}}")));
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "files", "eicar.txt", "text/plain", "TEST".getBytes());
    mvc.perform(multipart("/rest-monitor/upload").file(file).with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.job_id").value("a1b2"))
        .andExpect(jsonPath("$.status").value("queued"));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void upstreamErrorBodiesAreNotRelayedEvenIfBridgeReturnedThem() throws Exception {
    when(externalApi.read(eq("summary"), anyMap()))
        .thenReturn(
            new RestApiBridge.ApiResponse(
                503,
                json.readTree(
                    "{\"secret\":\"private-upstream-key\",\"path\":\"/srv/private/file\",\"error\":\"stacktrace\"}")));
    mvc.perform(get("/rest-monitor/summary"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"))
        .andExpect(content().string(not(containsString("private-upstream-key"))))
        .andExpect(content().string(not(containsString("/srv/private/file"))));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void viewerCannotRetryWebhookEvenWithCsrf() throws Exception {
    mvc.perform(post("/rest-monitor/jobs/test123/webhook/retry").with(csrf()))
        .andExpect(status().isForbidden());
    verify(externalApi, never()).retryWebhook(anyString());
  }
}
