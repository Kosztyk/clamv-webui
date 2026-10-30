package info.trizub.clamav.webclient.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:cli-api-support;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=CliSupportLocalAdmin456789",
    "clamav.metrics.clamd-initial-delay-ms=3600000", "clamav.config.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class ApiV1CliSupportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApiCredentialService credentials;
    @Autowired EndpointService endpoints;
    @Autowired ObjectMapper json;
    @MockitoBean ClamdTopService top;
    @MockitoBean ScanJobService jobs;
    @MockitoBean ClamavClientProvider clients;

    private String token(Set<String> scopes) {
        return credentials.issue("cli test",scopes,Set.of(),Set.of(),Instant.now().plusSeconds(3600)).secret();
    }
    @Test void authenticationProbeWorksForAnyValidScopeAndRejectsInvalidOrPlaintextCredentials() throws Exception {
        String token=token(Set.of("scan:upload"));
        mvc.perform(get("/api/v1/auth").secure(true).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.authentication").value("token"));
        mvc.perform(get("/api/v1/auth").secure(true).header("Authorization","Bearer bad-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth").secure(true).with(httpBasic("admin","CliSupportLocalAdmin456789")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.principal").value("admin"));
    }
    @Test void basicScanWritesRequireAndAcceptARealSessionCsrfToken() throws Exception {
        var job=new ScanJob();job.setId("cli-basic-job");
        when(jobs.createPathJob(anyString(),any(),anyString(),anyString())).thenReturn(job);
        String body="{\"path\":\"/scandir/example\"}";
        mvc.perform(post("/api/v1/scans/paths").secure(true).with(httpBasic("admin","CliSupportLocalAdmin456789"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var csrf=mvc.perform(get("/api/v1/csrf").secure(true).with(httpBasic("admin","CliSupportLocalAdmin456789")))
            .andExpect(status().isOk()).andReturn();
        var value=json.readTree(csrf.getResponse().getContentAsString());
        mvc.perform(post("/api/v1/scans/paths").secure(true).with(httpBasic("admin","CliSupportLocalAdmin456789"))
            .session((org.springframework.mock.web.MockHttpSession)csrf.getRequest().getSession(false))
            .header(value.get("headerName").asText(),value.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value("cli-basic-job"));
    }
    @Test void bearerUploadsRemainCsrfExemptAndRoleAndScopeChecksStillApply() throws Exception {
        var job=new ScanJob();job.setId("cli-token-job");
        when(jobs.createUploadJobs(anyList(),any(),anyString(),anyString())).thenReturn(List.of(job));
        String token=token(Set.of("scan:upload"));
        mvc.perform(multipart("/api/v1/scans/uploads").file(new MockMultipartFile("files","sample.txt","text/plain",new byte[]{1}))
            .secure(true).header("Authorization","Bearer "+token)).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/audit").secure(true).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void metricsExposePoolStatisticsThroughAnAllowlistWithoutDaemonCommandsOrPaths() throws Exception {
        when(top.snapshot(any())).thenReturn(Map.of("ok",true,"parsed",Map.of(
            "threads",Map.of("live",5,"idle",3,"busy",2,"max",10,"raw","/private/thread"),
            "queue",Map.of("items",2,"raw","/private/queue"),"pools",1,
            "memory",Map.of("pools_used","1.500M","pools_total","2.000M","heap","N/A","used","/private/leak","command","MULTISCAN /private"),
            "commands",List.of("MULTISCAN /private/file"))));
        String token=token(Set.of("health:read"));
        String body=mvc.perform(get("/api/v1/metrics/clamd").secure(true).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pools").value(1))
            .andExpect(jsonPath("$.memory.pools_used").value("1.500M"))
            .andExpect(jsonPath("$.threads.busy").value(2)).andExpect(jsonPath("$.queue.items").value(2))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/private","MULTISCAN","command");
    }
    @Test void jobDetailsReturnSignaturesAndDurationWhileRetainingEndpointRestrictions() throws Exception {
        var job=new ScanJob();job.setId("cli-details");job.setType(ScanJobType.PATH);job.setStatus(ScanJobStatus.FINISHED);
        job.setEndpoint(endpoints.defaultEndpointOrEnsure());job.setVerdict(ScanVerdict.VIRUS_FOUND);
        job.setStartedAt(Instant.now().minusSeconds(2));job.setFinishedAt(job.getStartedAt().plusMillis(1500));
        job.setFoundVirusesJson("{\"/private/file\":[\"Win.Test.EICAR_HDB-1\"]}");job.setErrorMessage("/private/error");
        job.setQuarantinePath("/private/quarantine");
        when(jobs.getOrNull("cli-details")).thenReturn(job);
        String token=token(Set.of("jobs:read"));
        String body=mvc.perform(get("/api/v1/jobs/cli-details").secure(true).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signatures[0]").value("Win.Test.EICAR_HDB-1"))
            .andExpect(jsonPath("$.quarantineStatus").value("RECORDED"))
            .andExpect(jsonPath("$.error").doesNotExist())
            .andExpect(jsonPath("$.durationMillis").value(1500)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/private","errorMessage","storedPath");
        mvc.perform(get("/api/v1/jobs/cli-details").secure(true).header("Authorization","Bearer "+token(Set.of("health:read"))))
            .andExpect(status().isForbidden());
        var other=endpoints.create("cli-other","localhost",3311,xyz.capybara.clamav.Platform.UNIX,true);
        String restricted=credentials.issue("other endpoint",Set.of("jobs:read"),Set.of(other.getId()),Set.of(),Instant.now().plusSeconds(60)).secret();
        mvc.perform(get("/api/v1/jobs/cli-details").secure(true).header("Authorization","Bearer "+restricted)).andExpect(status().isForbidden());
    }
    @Test void failedJobsReturnSafeErrorSummariesAndNoPrivateDiagnosticText() throws Exception {
        var job=new ScanJob();job.setId("mcp-error");job.setType(ScanJobType.PATH);job.setStatus(ScanJobStatus.FINISHED);
        job.setEndpoint(endpoints.defaultEndpointOrEnsure());job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage("raw error /private/key token=secret");when(jobs.getOrNull("mcp-error")).thenReturn(job);
        String body=mvc.perform(get("/api/v1/jobs/mcp-error").secure(true).header("Authorization","Bearer "+token(Set.of("jobs:read"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value("SCAN_FAILED"))
            .andExpect(jsonPath("$.quarantineStatus").value("UNKNOWN")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/private","token=secret","raw error");
    }
    @Test void healthReportsParsedDatabaseTimestampAndKeepsUnknownValuesUnknown() throws Exception {
        ClamdClient client=mock(ClamdClient.class);when(clients.clientFor(any())).thenReturn(client);
        when(client.version()).thenReturn("ClamAV 1.5.4/27920/Fri Oct 30 10:00:00 2020");
        String token=token(Set.of("health:read"));
        mvc.perform(get("/api/v1/health/clamd").secure(true).header("Authorization","Bearer "+token))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.connectivity").value("UP"))
            .andExpect(jsonPath("$.signatureDatabaseTimestamp").value("2020-10-30T10:00:00Z"))
            .andExpect(jsonPath("$.signatureDatabaseFreshness").value("STALE"));
        when(client.version()).thenReturn("ClamAV 1.5.4/27920/not-a-timestamp");
        mvc.perform(get("/api/v1/health/clamd").secure(true).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signatureDatabaseTimestamp").doesNotExist())
            .andExpect(jsonPath("$.signatureDatabaseFreshness").value("UNKNOWN"));
    }
}
