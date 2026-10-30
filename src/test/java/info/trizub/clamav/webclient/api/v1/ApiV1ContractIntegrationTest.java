package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.service.*;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase1-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=update", "spring.h2.console.enabled=false",
    "spring.task.scheduling.enabled=false",
    "clamav.bootstrap.admin.password=Phase1ContractTestOnlyPass123456"
})
@AutoConfigureMockMvc
class ApiV1ContractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApiCredentialService credentials;
    @Autowired EndpointService endpoints;
    @Autowired AuditService audits;
    @Autowired AuditEventRepository repo;

    @Test @WithMockUser(roles={"ADMIN"}) void contractIsServedAndLegacyHealthStillExists() throws Exception {
        mvc.perform(get("/api/v1/openapi.yaml").secure(true)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.0.3")));
        mvc.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/api/v1/jobs").secure(true).param("size", "1000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(get("/api/v1/jobs").secure(true).param("status", "NOT_A_STATUS"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/health/ready").secure(true)).andExpect(status().isOk())
            .andExpect(jsonPath("$.component").value("database"));
    }
    @Test void untrustedTokenCannotSeeAuditOrJobs() throws Exception {
        var t = credentials.issue("health only", Set.of("health:read"), Set.of(), Set.of(), Instant.now().plusSeconds(120));
        mvc.perform(get("/api/v1/audit").secure(true).header("Authorization", "Bearer " + t.secret()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/jobs").secure(true).header("Authorization", "Bearer " + t.secret()))
            .andExpect(status().isForbidden());
    }
    @Test void workerOutcomeRecordsActorTransportEndpointAndVerdict() {
        endpoints.ensureDefaultEndpoint();
        ScanJob job = new ScanJob(); job.setId("phase1-audit-only-job");
        job.setType(ScanJobType.UPLOAD); job.setStatus(ScanJobStatus.FINISHED);
        job.setSubmittedBy("token:55"); job.setSubmittedTransport("API_TOKEN");
        job.setVerdict(ScanVerdict.VIRUS_FOUND); job.setEndpoint(endpoints.defaultEndpoint());
        long before = repo.count(); audits.recordResult(job);
        assertThat(repo.count()).isGreaterThan(before);
        assertThat(repo.findTop200ByOrderByAtDesc()).anyMatch(e ->
            "phase1-audit-only-job".equals(e.getJobId()) && "SCAN_RESULT".equals(e.getAction()) &&
            "API_TOKEN".equals(e.getTransport()) && "VIRUS_FOUND".equals(e.getOutcome()) &&
            e.getEndpointId() != null);
    }
}
