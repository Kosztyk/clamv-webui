package info.trizub.clamav.webclient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.service.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:phase0;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "clamav.bootstrap.admin.password=Phase0IntegrationTestPasswordOnly123",
      "spring.h2.console.enabled=false",
      "spring.task.scheduling.enabled=false"
    })
@AutoConfigureMockMvc
class UiSmokeTest {
  @Autowired MockMvc mvc;
  @Autowired AuditEventRepository audits;
  @Autowired SettingsService settings;

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void issue26InvalidAdminFormsReturnVisibleMessagesInsteadOfHttp500() throws Exception {
    mvc.perform(
            post("/admin/users/create")
                .with(csrf())
                .param("username", "a".repeat(65))
                .param("password", "long-enough-password-123")
                .param("role", "VIEWER"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/users"))
        .andExpect(flash().attributeExists("errorMsg"));
    mvc.perform(
            post("/admin/users/create")
                .with(csrf())
                .param("username", "valid-new-user")
                .param("password", "x".repeat(73))
                .param("role", "VIEWER"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attributeExists("errorMsg"));
    for (String[] invalid :
        new String[][] {
          {"scanner", "daemon", "abc", "UNIX"},
          {"scanner", "daemon", "65536", "UNIX"},
          {"scanner", "/run/clamd.sock", "3310", "UNIX"},
          {"scanner", "daemon", "3310", "invalid-platform"}
        }) {
      mvc.perform(
              post("/admin/endpoints/create")
                  .with(csrf())
                  .param("name", invalid[0])
                  .param("host", invalid[1])
                  .param("port", invalid[2])
                  .param("platform", invalid[3]))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/admin/endpoints"))
          .andExpect(flash().attributeExists("errorMsg"));
    }
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void tenKilobyteUserAgentDoesNotBreakSuccessfulAdminOperation() throws Exception {
    String name = "audit-uagent-" + java.util.UUID.randomUUID();
    mvc.perform(
            post("/admin/endpoints/create")
                .with(csrf())
                .header("User-Agent", "Z".repeat(10_240))
                .param("name", name)
                .param("host", "127.0.0.1")
                .param("port", "3310")
                .param("platform", "UNIX"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/endpoints"));
    assertThat(audits.findTop200ByOrderByAtDesc())
        .anyMatch(
            event ->
                "ENDPOINT_CREATE".equals(event.getAction())
                    && "SUCCESS".equals(event.getOutcome())
                    && event.getDetails().startsWith(name + "@")
                    && event.getUserAgent() != null
                    && event.getUserAgent().length() == 255);
  }

  @Test
  void loginPageRendersWithoutAuthentication() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));
  }

  @Test
  @WithMockUser(roles = {"ADMIN", "OPERATOR", "VIEWER"})
  void pagesActuallyRender() throws Exception {
    for (String url :
        new String[] {
          "/dashboard", "/scan", "/jobs", "/admin/configuration-center", "/clamdtop", "/admin/audit"
        }) {
      mvc.perform(get(url))
          .andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("app-main")));
    }
  }

  @Test
  @WithMockUser(
      username = "kosz",
      roles = {"ADMIN"})
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

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void localBrowserLogoutRedirectsInsteadOfReturningNoContent() throws Exception {
    mvc.perform(post("/logout").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?logout"));
  }

  @Test
  @WithMockUser(roles = {"VIEWER"})
  void nonAdminCannotReadOrMutateAdmin() throws Exception {
    mvc.perform(get("/admin/settings")).andExpect(status().isForbidden());
    mvc.perform(post("/admin/settings").with(csrf()).param("app.watch.pollSeconds", "30"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/dashboard")).andExpect(status().isOk());
    mvc.perform(get("/api/clamdtop")).andExpect(status().isOk());
    mvc.perform(get("/api/jobs")).andExpect(status().isForbidden());
    mvc.perform(get("/api-reports")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"OPERATOR"})
  void legacyBrowserApiStateChangesRequireCsrf() throws Exception {
    mvc.perform(
            post("/api/scan/path")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/not-an-allowed-path\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"ADMIN", "OPERATOR", "VIEWER"})
  void retiredSettingsCannotBypassReviewOrProduceASuccessfulUpdate() throws Exception {
    var before = settings.snapshot();
    long events = audits.count();
    mvc.perform(post("/admin/settings").with(csrf()).param("app.watch.pollSeconds", "37"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.saved").value(false));
    assertThat(settings.snapshot()).isEqualTo(before);
    assertThat(audits.count()).isEqualTo(events);
    mvc.perform(get("/admin/settings"))
        .andExpect(redirectedUrl("/admin/configuration-center?category=scanning"));
  }

  @Test
  @WithMockUser(roles = {"OPERATOR"})
  void invalidServerPathIsRejectedAndAudited() throws Exception {
    long before = audits.count();
    mvc.perform(
            post("/api/scan/path")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"/path-that-should-not-exist-in-phase0-smoke\"}"))
        .andExpect(status().isBadRequest());
    assertThat(audits.count()).isGreaterThan(before);
    assertThat(audits.findTop200ByOrderByAtDesc())
        .anyMatch(e -> "SCAN_PATH".equals(e.getAction()) && "FAILED".equals(e.getOutcome()));
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void retiredWebhookFormsCannotRevealOrReplaceSavedSecrets() throws Exception {
    String original = settings.webhookUrl();
    boolean enabled = settings.webhookEnabled();
    for (var params :
        java.util.List.of(
            Map.of("app.webhook.url", "do-not-echo-this-secret", "app.webhook.enabled", "true"),
            Map.of("app.webhook.clear", "true"),
            Map.of("app.webhook.url", ""))) {
      var builder = post("/admin/settings").with(csrf());
      params.forEach(builder::param);
      mvc.perform(builder)
          .andExpect(status().isConflict())
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("do-not-echo-this-secret"))));
    }
    assertThat(settings.webhookUrl()).isEqualTo(original);
    assertThat(settings.webhookEnabled()).isEqualTo(enabled);
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void nativeClamdtopRetainsStandardSidebarAndExistingPtyTerminal() throws Exception {
    mvc.perform(get("/clamdtop"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("native-clamdtop-menu"))))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("native-clamdtop-terminal")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("native-clamdtop-connect")));
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void centralWebhookPolicyRejectsPrivateAndMetadataTargetsWithoutNetworkAccess() throws Exception {
    var values = info.trizub.clamav.webclient.configuration.ConfigurationCatalog.defaults();
    values.put("app.webhook.enabled", "true");
    values.put("app.webhook.signing-secret", "test-signature-key-for-ssrf-check-0123456789abcdef");
    for (String url :
        java.util.List.of(
            "http://10.77.88.10/hook",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/hook")) {
      values.put("app.webhook.url", url);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  info.trizub.clamav.webclient.configuration.ConfigurationCatalog
                      .validateCombination(values, java.util.Set.of("app.webhook.url")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void authenticationIsRequiredForDashboard() throws Exception {
    mvc.perform(get("/dashboard").accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  void loginRedirectRetainsApplicationContextPath() throws Exception {
    mvc.perform(get("/clamav/dashboard").contextPath("/clamav").accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/clamav/login"));
  }

  @Test
  void apiAuthenticationFailureRetainsJsonWithApplicationContextPath() throws Exception {
    mvc.perform(get("/clamav/api/v1/endpoints").contextPath("/clamav"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("unauthorized"));
  }
}
