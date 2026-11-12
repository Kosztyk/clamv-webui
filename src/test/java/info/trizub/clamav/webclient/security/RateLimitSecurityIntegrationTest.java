package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:bounded-rate-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=BoundedLimitIntegrationAdmin123456",
    "api.v1.scan-rate-limit-per-minute=2", "api.v1.scan-rate-limit-max-entries=32",
    "clamav.config.initial-delay-ms=3600000", "clamav.metrics.clamd-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class RateLimitSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApiCredentialService credentials;
    @Autowired UserService users;
    @Autowired EndpointService endpoints;
    @Autowired ScanRateLimitFilter filter;
    @MockitoBean ScanJobService jobs;
    @BeforeEach void stubJobs() {
        var job = new ScanJob(); job.setId("bounded-rate-job");
        when(jobs.createPathJob(anyString(), any(), anyString(), anyString())).thenReturn(job);
        when(jobs.createPathJob(anyString(), any(), anyString())).thenReturn(job);
        when(jobs.createUploadJobs(anyList(), any(), anyString(), anyString())).thenReturn(List.of(job));
    }
    String account() {
        String name = "bounded-" + UUID.randomUUID();
        users.createUser(name, "BoundedLimitUserPassword123", Set.of("ROLE_OPERATOR", "ROLE_VIEWER"), true);
        return name;
    }
    @Test void bearerBudgetUsesAuthenticatedTokenIdAfterAuthenticationAndIgnoresHeaderVariants() throws Exception {
        String token = credentials.issue("bounded", Set.of("scan:upload"), Set.of(), Set.of(), Instant.now().plusSeconds(3600)).secret();
        String[] headers = {"Bearer " + token, "bEaReR " + token + " ", "BEARER " + token};
        for (int i = 0; i < headers.length; i++) {
            mvc.perform(multipart("/api/v1/scans/uploads").file(new MockMultipartFile("files", "fixture.txt", "text/plain", new byte[]{1}))
                .secure(true).header("Authorization", headers[i]).with(request -> { request.setRemoteAddr(UUID.randomUUID().toString()); return request; }))
                .andExpect(status().is(i < 2 ? 202 : 429));
        }
        String other = credentials.issue("independent", Set.of("scan:upload"), Set.of(), Set.of(), Instant.now().plusSeconds(3600)).secret();
        mvc.perform(multipart("/api/v1/scans/uploads").file(new MockMultipartFile("files", "fixture.txt", "text/plain", new byte[]{1}))
            .secure(true).header("Authorization", "Bearer " + other)).andExpect(status().isAccepted());
    }
    @Test void basicBudgetUsesAccountAcrossAuthorizationVariantsAndSocketPeers() throws Exception {
        String name = account();
        String encoded = java.util.Base64.getEncoder().encodeToString((name + ":BoundedLimitUserPassword123").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) {
            String scheme = i == 0 ? "Basic " : i == 1 ? "bAsIc " : "BASIC ";
            mvc.perform(post("/api/v1/scans/paths").secure(true).with(csrf()).header("Authorization", scheme + encoded)
                .with(request -> { request.setRemoteAddr(UUID.randomUUID().toString()); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/scandir/fixture\"}"))
                .andExpect(status().is(i < 2 ? 202 : 429));
        }
    }
    @Test void browserAndBasicShareOneLocalAccountBudgetAcrossRoutes() throws Exception {
        String name = account();
        var loggedIn = mvc.perform(post("/login").with(csrf()).param("username", name).param("password", "BoundedLimitUserPassword123"))
            .andExpect(redirectedUrl("/dashboard")).andReturn();
        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        mvc.perform(post("/scan/path").session(session).with(csrf()).param("path", "/scandir/fixture")
            .param("endpointId", endpoints.defaultEndpointOrEnsure().getId().toString())).andExpect(redirectedUrl("/scan"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/scans/paths").secure(true).with(csrf()).with(httpBasic(name, "BoundedLimitUserPassword123"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/scandir/fixture\"}"))
                .andExpect(status().is(i == 0 ? 202 : 429));
        }
    }
    @Test void anonymousRequestsCannotCreateFreshBucketsByRotatingUnrecognizedHeaders() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/v1/scans/paths").with(csrf()).header("Authorization", "Random invalid-" + i)
                .with(request -> { request.setRemoteAddr("192.0.2.50"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/scandir/fixture\"}"))
                .andExpect(status().is(i < 2 ? 401 : 429));
        }
    }
    @Test void invalidBearerCredentialsAreRejectedBeforeAllocatingAnyScanBucket() throws Exception {
        int before = filter.trackedBuckets();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer invalid-" + i)
                .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/scandir/fixture\"}"))
                .andExpect(status().isUnauthorized());
        }
        assertThat(filter.trackedBuckets()).isEqualTo(before);
    }
    @Test void differentAuthenticatedAccountsDoNotShareANatBudget() throws Exception {
        for (int j = 0; j < 2; j++) {
            String name = account();
            for (int i = 0; i < 2; i++) {
                mvc.perform(post("/api/v1/scans/paths").secure(true).with(csrf()).with(httpBasic(name, "BoundedLimitUserPassword123"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/scandir/fixture\"}"))
                    .andExpect(status().isAccepted());
            }
        }
    }
}
