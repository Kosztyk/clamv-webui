package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.repo.ApiCredentialRepository;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.model.ApiCredential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase1-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=update", "spring.h2.console.enabled=false",
    "spring.task.scheduling.enabled=false",
    "clamav.bootstrap.admin.password=Phase1SecurityTestOnlyPass123456"
})
@AutoConfigureMockMvc
class ApiV1SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApiCredentialService credentials;
    @Autowired ApiCredentialRepository repository;
    @Autowired EndpointService endpoints;

    private String token(Set<String> scopes, Set<Long> endpointIds, Set<String> roots) {
        return credentials.issue("integration test", scopes, endpointIds, roots, Instant.now().plusSeconds(3600)).secret();
    }
    @Test void tokenHashIsNeverPersistedAsCleartextAndRevokeIsImmediate() throws Exception {
        var created = credentials.issue("one-time test", Set.of("health:read"), Set.of(), Set.of(), Instant.now().plusSeconds(3600));
        String secret = created.secret();
        ApiCredential db = repository.findById(created.credential().getId()).orElseThrow();
        assertThat(db.getTokenHash()).isNotEqualTo(secret).hasSize(64);
        assertThat(db.getTokenHash()).isEqualTo(ApiCredentialService.sha256(secret));
        mvc.perform(get("/api/v1/health/ready").secure(true).header("Authorization", "Bearer " + secret))
            .andExpect(status().isOk()).andExpect(jsonPath("$.component").value("database"));
        assertThat(credentials.revoke(db.getId())).isTrue();
        mvc.perform(get("/api/v1/health/ready").secure(true).header("Authorization", "Bearer " + secret))
            .andExpect(status().isUnauthorized());
    }
    @Test void tokenScopesAndTlsAreEnforced() throws Exception {
        String secret = token(Set.of("health:read"), Set.of(), Set.of());
        mvc.perform(get("/api/v1/jobs").secure(true).header("Authorization", "Bearer " + secret))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/health/live").secure(true).header("Authorization", "Bearer " + secret))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/health/live").header("Authorization", "Bearer " + secret))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/health").header("Authorization", "Basic YWRtaW46YWRtaW4="))
            .andExpect(status().isForbidden());
    }
    @Test void endpointAndPathRestrictionsAreEnforced() throws Exception {
        endpoints.ensureDefaultEndpoint();
        Long id = endpoints.defaultEndpoint().getId();
        // Unknown endpoint grants cannot be issued at all.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            credentials.issue("invalid", Set.of("health:read"), Set.of(Long.MAX_VALUE), Set.of(),
                Instant.now().plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        String read = token(Set.of("scan:path", "endpoints:read", "health:read"), Set.of(id), Set.of());
        mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + read)
            .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/etc/passwd\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/endpoints").secure(true).header("Authorization", "Bearer " + read))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    }
    @Test @WithMockUser(roles={"ADMIN"}) void adminCanCreateAndRevokeTokenWithCsrf() throws Exception {
        String request = "{\"label\":\"cli\",\"scopes\":[\"jobs:read\",\"health:read\"]}";
        mvc.perform(post("/api/v1/tokens").secure(true).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/v1/tokens").secure(true).with(csrf())
              .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.secret").exists())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("tokenHash");
    }
    @Test @WithMockUser(roles={"VIEWER"}) void viewerCannotReadJobHistoryAndAdminAudit() throws Exception {
        mvc.perform(get("/api/v1/jobs").secure(true)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").secure(true)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/health/ready").secure(true)).andExpect(status().isOk());
    }
    @Test void malformedCredentialsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/health/ready").secure(true).header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized());
    }
}
