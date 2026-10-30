package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Provider settings are truly active at runtime; admin-only, CSRF protected and encrypted. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase31authui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=Phase31IndependentEmergencyAdmin456",
    "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class Phase31AuthenticationUiTest {
    @Autowired MockMvc mvc;
    @Autowired AuthenticationSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuthenticationProviderProber prober;

    @Test void uiConfigUsesEncryptedSecretsAndActivatesOidcDynamically() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("2.0.0")))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/oauth2/authorization/clamav"))));
        mvc.perform(get("/admin/settings/authentication").with(user("reader").roles("VIEWER")))
          .andExpect(status().isForbidden());
        mvc.perform(get("/admin/settings/authentication").with(user("admin").roles("ADMIN")))
          .andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("LDAP / Active Directory")))
          .andExpect(content().string(org.hamcrest.Matchers.containsString("OIDC / Single Sign-On")));
        mvc.perform(post("/admin/settings/authentication/oidc/save").secure(true).with(user("admin").roles("ADMIN"))
          .param("enabled","true").param("issuer","https://auth.example.invalid/application/o/clamav/")
          .param("clientId","client-42").param("secret","phase31SecretVisibleOnlyInSubmission")
          .param("groupsClaim","groups").param("viewer","ClamAV Viewers")
          .param("operator","ClamAV Operators").param("admin","ClamAV Administrators")
          .param("logoutMode","local").param("buttonLabel","Sign in with Authentik"))
          .andExpect(status().isForbidden()); // CSRF must not be bypassed
        mvc.perform(post("/admin/settings/authentication/oidc/save").secure(true).with(user("admin").roles("ADMIN")).with(csrf())
          .param("enabled","true").param("issuer","https://auth.example.invalid/application/o/clamav/")
          .param("clientId","client-42").param("secret","phase31SecretVisibleOnlyInSubmission")
          .param("groupsClaim","groups").param("viewer","ClamAV Viewers")
          .param("operator","ClamAV Operators").param("admin","ClamAV Administrators")
          .param("logoutMode","local").param("buttonLabel","Sign in with Authentik"))
          .andExpect(status().is3xxRedirection());
        assertThat(settings.properties().getOidc().isEnabled()).isTrue();
        String encrypted=jdbc.queryForObject("select encrypted_secret from auth_provider_settings where provider='oidc'",String.class);
        assertThat(encrypted).startsWith("gcm1:").doesNotContain("phase31SecretVisibleOnlyInSubmission");
        assertThat(settings.effectiveSecret("oidc")).isEqualTo("phase31SecretVisibleOnlyInSubmission");
        settings.load(); // Simulates a clean re-read from the persistent database
        assertThat(settings.properties().getOidc().isEnabled()).isTrue();
        mvc.perform(get("/login")).andExpect(content().string(
              org.hamcrest.Matchers.containsString("/oauth2/authorization/clamav")));
        // No disabling local login or provisioning a local OIDC account.
    }
    @Test void providerFormsCollapseWhenDisabledAndExpandWhenEnabled() throws Exception {
        String html = mvc.perform(get("/admin/settings/authentication")
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Local recovery is never collapsed. Provider toggles remain usable regardless of status.
        assertThat(html).contains("id=\"local\"")
                .contains("data-auth-provider=\"ldap\"")
                .contains("data-auth-provider=\"oidc\"")
                .contains("authentication-management.js");
        for (String provider : java.util.List.of("ldap", "oidc")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "<form\\b[^>]*\\bid=\"" + provider + "\"[^>]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            assertThat(matcher.find()).as("Form should exist for %s", provider).isTrue();
            String formTag = matcher.group();
            assertThat(formTag).contains("auth-provider-panel");
            boolean enabled = "true".equals(settings.values(provider).get("enabled"));
            assertThat(formTag.matches("(?s).*\\bhidden(?:\\s|=|>).*"))
                    .as("Hidden state must match the effective setting for %s", provider)
                    .isEqualTo(!enabled);
        }
    }

    @Test void authenticationButtonsRenderExplicitSaveAndTestPostTargets() throws Exception {
        String html = mvc.perform(get("/admin/settings/authentication")
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String provider : java.util.List.of("ldap", "oidc")) {
            String prefix = "/admin/settings/authentication/" + provider;
            assertThat(html).contains("action=\"" + prefix + "/save\"")
                    .contains("formaction=\"" + prefix + "/save\"")
                    .contains("formaction=\"" + prefix + "/test\"");
            // A POST to the GET-only page must not be used by Save. The real
            // /save endpoint must return provider JSON, not HTTP 405.
            mvc.perform(post(prefix + "/save")
                    .with(user("admin").roles("ADMIN")).with(csrf())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                    .param("secret", "synthetic-http-blocked-secret"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HTTPS")));
        }
    }

    @Test void uiNeverEchoesSecretsAndRejectsHttpSecretSubmission() throws Exception {
        mvc.perform(post("/admin/settings/authentication/ldap/save").with(user("admin").roles("ADMIN")).with(csrf())
          .param("secret","leaking-bind-password"))
          .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/settings/authentication").with(user("admin").roles("ADMIN")))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("leaking-bind-password"))));
    }

    @Test void ajaxProviderTestReturnsJsonWithoutSavingOrRedirecting() throws Exception {
        String original = jdbc.query("select settings_json from auth_provider_settings where provider='ldap'",
                rs -> rs.next() ? rs.getString(1) : null);
        mvc.perform(post("/admin/settings/authentication/ldap/test").secure(true)
             .with(user("admin").roles("ADMIN")).with(csrf())
             .header("X-Requested-With", "XMLHttpRequest").accept(org.springframework.http.MediaType.APPLICATION_JSON)
             .param("enabled","true").param("url","ldaps://directory.example.invalid:636")
             .param("baseDn","dc=example,dc=invalid").param("managerDn","cn=bind,dc=example,dc=invalid")
             .param("userSearchFilter","(uid={0})").param("groupSearchBase","ou=groups")
             .param("viewer","clamav-viewers").param("secret","temporary-bind-secret"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
           .andExpect(jsonPath("$.saved").value(false))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOT saved yet")));
        String after = jdbc.query("select settings_json from auth_provider_settings where provider='ldap'",
                rs -> rs.next() ? rs.getString(1) : null);
        assertThat(after).isEqualTo(original);
    }

    @Test void ajaxLdapSaveEncryptsCredentialsAndRetainsValuesAcrossReload() throws Exception {
        mvc.perform(post("/admin/settings/authentication/ldap/save").secure(true)
             .with(user("admin").roles("ADMIN")).with(csrf())
             .header("X-Requested-With", "XMLHttpRequest").accept(org.springframework.http.MediaType.APPLICATION_JSON)
             .param("enabled","true").param("url","ldaps://directory.example.invalid:636")
             .param("baseDn","dc=example,dc=invalid").param("managerDn","cn=bind,dc=example,dc=invalid")
             .param("userSearchBase","ou=people").param("userSearchFilter","(uid={0})")
             .param("groupSearchBase","ou=groups").param("groupMemberAttribute","member")
             .param("groupNameAttribute","cn").param("viewer","clamav-viewers")
             .param("maxNestedDepth","3").param("connectTimeoutMillis","5000")
             .param("readTimeoutMillis","5000").param("referrals","ignore")
             .param("secret","temporary-encrypted-bind-secret"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
           .andExpect(jsonPath("$.saved").value(true))
           .andExpect(jsonPath("$.enabled").value("true"))
           .andExpect(jsonPath("$.secretConfigured").value(true));
        String encrypted=jdbc.queryForObject("select encrypted_secret from auth_provider_settings where provider='ldap'",String.class);
        assertThat(encrypted).startsWith("gcm1:").doesNotContain("temporary-encrypted-bind-secret");
        settings.load();
        assertThat(settings.values("ldap")).containsEntry("managerDn","cn=bind,dc=example,dc=invalid")
                .containsEntry("enabled","true").containsEntry("viewer","clamav-viewers");
        assertThat(settings.hasSecret("ldap")).isTrue();
        mvc.perform(get("/admin/settings/authentication").with(user("admin").roles("ADMIN")))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("cn=bind,dc=example,dc=invalid")))
           .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("temporary-encrypted-bind-secret"))));
    }

    @Test void invalidAjaxLdapConfigurationReturnsFieldErrorsWithoutRedirectOrSecretEcho() throws Exception {
        mvc.perform(post("/admin/settings/authentication/ldap/save").secure(true)
             .with(user("admin").roles("ADMIN")).with(csrf())
             .header("X-Requested-With", "XMLHttpRequest").accept(org.springframework.http.MediaType.APPLICATION_JSON)
             .param("enabled","true").param("url","ldaps://directory.example.invalid:636")
             .param("userSearchFilter","(uid={0})").param("viewer","clamav-viewers"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.ok").value(false))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("LDAP missing required fields")))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Base DN")));
        mvc.perform(post("/admin/settings/authentication/ldap/save")
             .with(user("admin").roles("ADMIN")).with(csrf())
             .header("X-Requested-With", "XMLHttpRequest").accept(org.springframework.http.MediaType.APPLICATION_JSON)
             .param("secret","do-not-echo-plaintext"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HTTPS")))
           .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-echo-plaintext"))));
    }

}
