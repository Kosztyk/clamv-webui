package info.trizub.clamav.webclient.web;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.EndpointService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import xyz.capybara.clamav.Platform;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:issue39-viewer-privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "clamav.bootstrap.admin.password=Issue39OnlyRecoveryPassword123456",
      "clamav.client.connect-timeout=100ms",
      "clamav.client.command-timeout=250ms",
      "spring.task.scheduling.enabled=false"
    })
@AutoConfigureMockMvc
class Issue39ViewerHttpPrivacyIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired EndpointService endpoints;
  @Autowired ScanJobRepository jobs;

  @Test
  void viewerHasOnlyNumericClamdMetricsAndNoTerminalPathsOrRecentTargets() throws Exception {
    var ep = endpoints.create("private-scanner.internal", "127.0.0.1", 1, Platform.UNIX, true);
    var job = new ScanJob();
    job.setId(UUID.randomUUID().toString());
    job.setType(ScanJobType.PATH);
    job.setTarget("/mnt/private/customer-files/company-secrets.txt");
    job.setSubmittedBy("admin");
    job.setEndpoint(ep);
    jobs.saveAndFlush(job);
    String id = ep.getId().toString();
    mvc.perform(get("/api/clamdtop").param("endpointId", id).with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endpointId").value(ep.getId()))
        .andExpect(jsonPath("$.stats").doesNotExist())
        .andExpect(jsonPath("$.endpoint").doesNotExist())
        .andExpect(jsonPath("$.parsed.commands").doesNotExist())
        .andExpect(content().string(not(containsString("private-scanner.internal"))));
    mvc.perform(get("/api/endpoints").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("private-scanner.internal"))));
    mvc.perform(get("/main").param("endpointId", id).with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("private-scanner.internal"))))
        .andExpect(content().string(not(containsString("company-secrets.txt"))));
    mvc.perform(get("/clamdtop").param("endpointId", id).with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ClamdTop — health summary")))
        .andExpect(content().string(not(containsString("native-clamdtop-terminal"))));
    mvc.perform(get("/dashboard").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("company-secrets.txt"))));
    mvc.perform(get("/ws/clamdtop").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/clamdtop").param("endpointId", id).with(user("operator").roles("OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endpoint.host").value("127.0.0.1"));
  }
}
