package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.*;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:configuration-center-ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=ConfigurationCenterRecoveryAdmin456",
    "clamav.config.initial-delay-ms=3600000","clamav.metrics.clamd-initial-delay-ms=3600000",
    "CLAMAV_UPLOAD_PATH_SCAN_ENABLED=false","clamav.configuration.file=target/test-config/configuration-center-ui-${random.uuid}/center.properties"})
@AutoConfigureMockMvc @org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
class ConfigurationCenterUiTest {
    static final Path folder;
    static {try {folder=Path.of("target/test-config/configuration-center-ui").toAbsolutePath();Files.createDirectories(folder);try(var files=Files.list(folder)) {for(Path path:files.toList()) Files.delete(path);}} catch(Exception e) {throw new ExceptionInInitializerError(e);} }
    @org.junit.jupiter.api.BeforeAll static void initializePathsBeforeBootEnvironment() {assertThat(folder).isDirectory();}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("clamav.integrations.settings-file",()->folder.resolve("integrations.properties").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConfigurationCenterService service;
    @Autowired SettingsService settings;
    @Autowired info.trizub.clamav.webclient.config.ClamdConfigurationObserver observer;
    @Autowired EndpointService endpoints;
    MockHttpServletRequestBuilder postJson(String action,Object body,MockHttpSession session) throws Exception {
        var request=post("/admin/configuration-center/api/"+action).secure(true).with(csrf()).with(user("administrator").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return session==null?request:request.session(session);
    }
    MvcResult review(Map<String,String> changes,MockHttpSession session) throws Exception {
        return mvc.perform(postJson("review",Map.of("version",service.engine().store().snapshot().revision(),"changes",changes),session))
                .andExpect(status().isOk()).andReturn();
    }
    String id(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsByteArray()).get("reviewId").asText();}
    @Test void duplicateEndpointNamesAreRejectedBeforePersistingACentralDraft() throws Exception {
        var extra=endpoints.create("reserved-secondary", "localhost",3311,xyz.capybara.clamav.Platform.UNIX,true);
        String revision=service.engine().store().snapshot().revision();
        try {
            mvc.perform(postJson("review",Map.of("version",revision,"changes",Map.of("center.clamd.name","reserved-secondary")),new MockHttpSession()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("already used")));
            assertThat(service.engine().store().snapshot().revision()).isEqualTo(revision);
            assertThat(endpoints.defaultEndpoint().getId()).isNotEqualTo(extra.getId());
        } finally { endpoints.delete(extra.getId()); }
    }
    @Test void pageAndApiAreAdminOnlyAndMutationsRequireCsrf() throws Exception {
        for(String path:List.of("/admin/configuration-center","/admin/configuration-center/api/view")) {
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/configuration-center/api/review").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        mvc.perform(get("/admin/configuration-center").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(containsString("Configuration center")));
    }
    @Test void applicationUsesAnIsolatedStoreAndShowsDeploymentControlledFalse() throws Exception {
        assertThat(service.engine().store().file().startsWith(Path.of("target/test-config").toAbsolutePath())).isTrue();
        assertThat(service.engine().store().directory().getFileName().toString()).startsWith("configuration-center-ui-");
        assertThat(settings.uploadPathScanEnabled()).isFalse();
        assertThat(service.engine().locked("app.upload.pathScan.enabled")).isTrue();
        mvc.perform(postJson("review",Map.of("version",service.engine().store().snapshot().revision(),"changes",Map.of("app.upload.pathScan.enabled","true")),null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("deployment")));
    }
    @Test void scanPolicyAppliesLiveWithServerBoundReviewAndCannotBeReplayed() throws Exception {
        var session=new MockHttpSession();String changed=settings.allowedRoots().equals(List.of(Path.of("/center-scans")))?"/center-scans-2":"/center-scans";
        var reviewed=review(Map.of("app.allowedScanRoots",changed),session);String id=id(reviewed);
        mvc.perform(postJson("apply",Map.of("reviewId",id),new MockHttpSession())).andExpect(status().isBadRequest());
        mvc.perform(postJson("apply",Map.of("reviewId",id),session)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(settings.allowedRoots()).containsExactly(Path.of(changed));
        mvc.perform(postJson("apply",Map.of("reviewId",id),session)).andExpect(status().isBadRequest());
    }
    @Test void sensitiveReviewRequiresLocalAdministratorAndLeavesDatabaseConnectionActive() throws Exception {
        var session=new MockHttpSession();String username="staged_db_user";
        var reviewed=review(Map.of("spring.datasource.username",username),session);String id=id(reviewed);
        mvc.perform(postJson("apply",Map.of("reviewId",id,"acknowledgeDatabase",true,"verificationUsername","admin","verificationPassword","wrong"),session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("verification failed")));
        mvc.perform(postJson("apply",Map.of("reviewId",id,"acknowledgeDatabase",true,"verificationUsername","admin","verificationPassword","ConfigurationCenterRecoveryAdmin456"),session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(service.engine().values(true)).containsEntry("spring.datasource.username",username);
        assertThat(service.engine().values(false)).containsEntry("spring.datasource.username","sa");
        String html=mvc.perform(get("/admin/configuration-center/api/view").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("ConfigurationCenterRecoveryAdmin456").contains("pendingRestart");
        mvc.perform(postJson("review",Map.of("version",service.engine().store().snapshot().revision(),"changes",Map.of("CLAMAV_REST_API_KEY","private-api-secret")),null).secure(false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("HTTPS")));
    }
    @Test void encryptedMetricsCredentialAppliesLiveAndSurvivesServiceRecreation() throws Exception {
        var session=new MockHttpSession();String token="configuration-center-scrape-token-0123456789";
        String id=id(review(Map.of("clamav.metrics.enabled","true","clamav.metrics.scrape-token",token),session));
        mvc.perform(postJson("apply",Map.of("reviewId",id,"verificationUsername","admin","verificationPassword","ConfigurationCenterRecoveryAdmin456"),session))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer invalid")) .andExpect(status().isForbidden());
        assertThat(Files.readString(service.engine().store().file())).doesNotContain(token);
        assertThat(new ConfigurationStore(service.engine().store().file(),service.engine().store().directory().resolve("configuration-center.key")).saved("clamav.metrics.scrape-token")).contains(token);
        mvc.perform(get("/admin/configuration-center/api/view").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString(token))));
    }
    @Test void protectedRecoveryPageIsAvailableWithoutMainLoginAndNeedsSeparateToken() throws Exception {
        mvc.perform(get("/setup/")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(containsString("Unlock setup &amp; recovery")));
        mvc.perform(post("/setup/api/view").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/setup/api/view").header("Authorization","Bearer "+service.engine().store().recoveryToken())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canStart").value(false));
    }
    @Test void administratorCanConfigureCredentialsDirectlyOverHttpWithExplicitConfirmation() throws Exception {
        var session=new MockHttpSession();String secret="direct-http-rest-api-private-key";
        var reviewed=mvc.perform(postJson("review",Map.of("version",service.engine().store().snapshot().revision(),
                "acknowledgeHttp",true,"changes",Map.of("CLAMAV_REST_API_KEY",secret)),session).secure(false))
                .andExpect(status().isOk()).andReturn();
        String id=id(reviewed);
        mvc.perform(postJson("apply",Map.of("reviewId",id,"verificationUsername","admin",
                "verificationPassword","ConfigurationCenterRecoveryAdmin456"),session).secure(false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("HTTP")));
        mvc.perform(postJson("apply",Map.of("reviewId",id,"acknowledgeHttp",true,"verificationUsername","admin",
                "verificationPassword","wrong"),session).secure(false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("verification failed")));
        mvc.perform(postJson("apply",Map.of("reviewId",id,"acknowledgeHttp",true,"verificationUsername","admin",
                "verificationPassword","ConfigurationCenterRecoveryAdmin456"),session).secure(false))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString(secret))));
        assertThat(service.engine().store().saved("CLAMAV_REST_API_KEY")).contains(secret);
        assertThat(Files.readString(service.engine().store().file())).doesNotContain(secret);
    }
    @Test void directHttpAcknowledgementDoesNotGrantNonAdminsConfigurationAccess() throws Exception {
        mvc.perform(post("/admin/configuration-center/api/review").with(csrf()).with(user("viewer").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeHttp\":true,\"changes\":{}}"))
                .andExpect(status().isForbidden());
    }
    @Test void centralWebhookReviewMasksSecretsAndExplicitClearAlsoDisablesDelivery() throws Exception {
        String destination="https://receiver.example.invalid/hook/private-token";
        var session=new MockHttpSession();
        var reviewed=review(Map.of("app.webhook.enabled","true","app.webhook.url",destination),session);
        assertThat(reviewed.getResponse().getContentAsString()).doesNotContain(destination);
        mvc.perform(postJson("apply",Map.of("reviewId",id(reviewed),"verificationUsername","admin",
                "verificationPassword","ConfigurationCenterRecoveryAdmin456"),session)).andExpect(status().isOk());
        assertThat(settings.webhookUrl()).isEqualTo(destination);assertThat(settings.webhookEnabled()).isTrue();
        assertThat(Files.readString(service.engine().store().file())).doesNotContain(destination);
        var cleared=mvc.perform(postJson("review",Map.of("version",service.engine().store().snapshot().revision(),
                "changes",Map.of(),"clearSecrets",List.of("app.webhook.url")),session)).andExpect(status().isOk()).andReturn();
        mvc.perform(postJson("apply",Map.of("reviewId",id(cleared),"verificationUsername","admin",
                "verificationPassword","ConfigurationCenterRecoveryAdmin456"),session)).andExpect(status().isOk());
        assertThat(settings.webhookUrl()).isBlank();assertThat(settings.webhookEnabled()).isFalse();
    }

    @Test void centralObservationControlsRefreshTheLiveSnapshotImmediately() throws Exception {
        var session=new MockHttpSession();
        var reviewed=review(Map.of("center.observer.enabled","false","clamav.config.poll-ms","5000"),session);
        mvc.perform(postJson("apply",Map.of("reviewId",id(reviewed)),session)).andExpect(status().isOk());
        assertThat(observer.observationEnabled()).isFalse();assertThat(observer.pollMillis()).isEqualTo(5000);
        assertThat(observer.clamd().status()).isEqualTo("PAUSED");
        var restored=review(Map.of("center.observer.enabled","true"),session);
        mvc.perform(postJson("apply",Map.of("reviewId",id(restored)),session)).andExpect(status().isOk());
        assertThat(observer.observationEnabled()).isTrue();assertThat(observer.clamd().status()).isNotEqualTo("PAUSED");
    }

}
