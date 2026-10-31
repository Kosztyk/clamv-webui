package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.service.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:phase0;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "clamav.bootstrap.admin.password=Phase0IntegrationTestPasswordOnly123",
    "spring.h2.console.enabled=false", "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class UiSmokeTest {
    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository audits;
    @Autowired SettingsService settings;

    @Test void loginPageRendersWithoutAuthentication() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));
    }

    @Test @WithMockUser(roles={"ADMIN", "OPERATOR", "VIEWER"})
    void pagesActuallyRender() throws Exception {
        for (String url : new String[]{"/dashboard", "/scan", "/jobs", "/admin/settings", "/clamdtop", "/admin/audit"}) {
            mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app-main")));
        }
    }

    @Test @WithMockUser(username="kosz", roles={"ADMIN"})
    void originalTopbarControlsRenderWithDarkThemeLabel() throws Exception {
        mvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Select language")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Українська")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("User:")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("kosz")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Dark Theme")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"themeToggle\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Logout")));
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void localBrowserLogoutRedirectsInsteadOfReturningNoContent() throws Exception {
        mvc.perform(post("/logout").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test @WithMockUser(roles={"VIEWER"})
    void nonAdminCannotReadOrMutateAdmin() throws Exception {
        mvc.perform(get("/admin/settings")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings").with(csrf())
            .param("app.watch.pollSeconds", "30")).andExpect(status().isForbidden());
        mvc.perform(get("/dashboard")).andExpect(status().isOk());
        mvc.perform(get("/api/clamdtop")).andExpect(status().isOk());
        mvc.perform(get("/api/jobs")).andExpect(status().isForbidden());
        mvc.perform(get("/api-reports")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles={"OPERATOR"})
    void legacyBrowserApiStateChangesRequireCsrf() throws Exception {
        mvc.perform(post("/api/scan/path").contentType(MediaType.APPLICATION_JSON)
            .content("{\"path\":\"/not-an-allowed-path\"}"))
            .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles={"ADMIN", "OPERATOR", "VIEWER"})
    void adminUpdatePersistsAnAuditEvent() throws Exception {
        long before = audits.count();
        mvc.perform(post("/admin/settings").with(csrf())
            .param("app.watch.pollSeconds", "30"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/settings"));
        assertThat(audits.count()).isGreaterThan(before);
        assertThat(audits.findTop200ByOrderByAtDesc()).anyMatch(e -> "ADMIN_SETTINGS_UPDATE".equals(e.getAction()));
    }

    @Test @WithMockUser(roles={"OPERATOR"})
    void invalidServerPathIsRejectedAndAudited() throws Exception {
        long before = audits.count();
        mvc.perform(post("/api/scan/path").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"path\":\"/path-that-should-not-exist-in-phase0-smoke\"}"))
            .andExpect(status().isBadRequest());
        assertThat(audits.count()).isGreaterThan(before);
        assertThat(audits.findTop200ByOrderByAtDesc())
            .anyMatch(e -> "SCAN_PATH".equals(e.getAction()) && "FAILED".equals(e.getOutcome()));
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void webhookConfigurationHasMaskedWriteOnlyFieldAndPreservesSavedUrl() throws Exception {
        String original = settings.webhookUrl();
        boolean wasEnabled = settings.webhookEnabled();
        String sentinel = "https://discord.com/api/webhooks/999999999999/testing-nonfunctional-token";
        try {
            settings.updateFromMap(Map.of("app.webhook.url", sentinel, "app.webhook.enabled", "true"));
            mvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Webhook URL")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A webhook URL is already saved")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sentinel))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"app.webhook.url\"")));
            // Posting blank must not overwrite the original URL.
            mvc.perform(post("/admin/settings").with(csrf())
                    .param("app.webhook.url", "")
                    .param("app.webhook.enabled", "true"))
                .andExpect(status().is3xxRedirection());
            assertThat(settings.webhookUrl()).isEqualTo(sentinel);
            assertThat(settings.webhookEnabled()).isTrue();
            mvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sentinel))));
        } finally {
            settings.updateFromMap(Map.of("app.webhook.url", original,
                                          "app.webhook.enabled", String.valueOf(wasEnabled)));
        }
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void deletingWebhookClearsSavedSecretAndDisablesNotifications() throws Exception {
        String original = settings.webhookUrl();
        boolean wasEnabled = settings.webhookEnabled();
        try {
            settings.updateFromMap(Map.of("app.webhook.url", "https://example.org/webhook/sentinel", "app.webhook.enabled", "true"));
            mvc.perform(post("/admin/settings").with(csrf())
                    .param("app.webhook.clear", "true")
                    .param("app.webhook.enabled", "true"))
                .andExpect(status().is3xxRedirection());
            assertThat(settings.webhookUrl()).isBlank();
            assertThat(settings.webhookEnabled()).isFalse();
        } finally {
            settings.updateFromMap(Map.of("app.webhook.url", original,
                                          "app.webhook.enabled", String.valueOf(wasEnabled)));
        }
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void webhookValidationRejectsInvalidUrlAndIncompleteConfiguration() throws Exception {
        String original = settings.webhookUrl();
        boolean wasEnabled = settings.webhookEnabled();
        try {
            settings.updateFromMap(Map.of("app.webhook.url", "", "app.webhook.enabled", "false"));
            mvc.perform(post("/admin/settings").with(csrf())
                    .param("app.webhook.enabled", "true")
                    .param("app.webhook.url", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMsg"));
            assertThat(settings.webhookEnabled()).isFalse();
            mvc.perform(post("/admin/settings").with(csrf())
                    .param("app.webhook.enabled", "true")
                    .param("app.webhook.url", "http://discord.com/api/webhooks/123/should-be-https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMsg"));
            assertThat(settings.webhookUrl()).isBlank();
        } finally {
            settings.updateFromMap(Map.of("app.webhook.url", original,
                                          "app.webhook.enabled", String.valueOf(wasEnabled)));
        }
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void nativeClamdtopRetainsStandardSidebarAndExistingPtyTerminal() throws Exception {
        mvc.perform(get("/clamdtop")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("native-clamdtop-menu"))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("native-clamdtop-terminal")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("native-clamdtop-connect")));
    }

    @Test @WithMockUser(roles={"ADMIN"})
    void privateWebhookCannotBeEnabledByChangingAFormParameter() throws Exception {
        String original = settings.webhookUrl();
        boolean enabled = settings.webhookEnabled();
        try {
            settings.updateFromMap(Map.of("app.webhook.url", "", "app.webhook.enabled", "false"));
            for (String url : new String[]{"http://192.168.68.112/hook", "http://169.254.169.254/latest/meta-data", "http://[::1]/hook"}) {
                mvc.perform(post("/admin/settings").with(csrf())
                        .param("app.webhook.url", url).param("app.webhook.enabled", "true")
                        .param("clamav.webhook.allow-private-addresses", "true"))
                    .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMsg"));
                assertThat(settings.webhookUrl()).isBlank();
                assertThat(settings.webhookEnabled()).isFalse();
            }
            mvc.perform(get("/admin/settings")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Private and local network destinations are blocked")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Notifications run in the background")));
        } finally {
            settings.updateFromMap(Map.of("app.webhook.url", original, "app.webhook.enabled", String.valueOf(enabled)));
        }
    }

    @Test void authenticationIsRequiredForDashboard() throws Exception {
        mvc.perform(get("/dashboard").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test void loginRedirectRetainsApplicationContextPath() throws Exception {
        mvc.perform(get("/clamav/dashboard").contextPath("/clamav").accept(MediaType.TEXT_HTML))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/clamav/login"));
    }

    @Test void apiAuthenticationFailureRetainsJsonWithApplicationContextPath() throws Exception {
        mvc.perform(get("/clamav/api/v1/endpoints").contextPath("/clamav"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
