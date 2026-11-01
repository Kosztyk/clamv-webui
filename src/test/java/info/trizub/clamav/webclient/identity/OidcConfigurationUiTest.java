package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exact requested environment names, real local login, SSO redirect and UI/H2 saves. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:oidc-compatibility-ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=OidcCompatibilityLocalAdmin456",
    "spring.task.scheduling.enabled=false", "clamav.configuration.file=target/test-config/oidc-ui-${random.uuid}/center.properties"
})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = OidcConfigurationUiTest.StandardEnvironment.class)
class OidcConfigurationUiTest {
    static class StandardEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.<String, Object>of(
                    "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID", "standard-ui-client",
                    "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET", "synthetic-standard-ui-secret",
                    "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE", "openid,profile,email,groups",
                    "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI", "https://127.0.0.1:9/offline-oidc")));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AuthenticationSettingsService settings;
    @Autowired info.trizub.clamav.webclient.configuration.ConfigurationCenterService center;

    @Test void standardDeploymentLocksScopesWhileLocalLoginAndUnlockedUiFieldsWork() throws Exception {
        var oidc = settings.properties().getOidc();
        assertThat(oidc.isEnabled()).isTrue();
        assertThat(settings.effectiveSecret("oidc")).isEqualTo("synthetic-standard-ui-secret");
        try (var discovery = mockStatic(ClientRegistrations.class)) {
            String login = mvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(login).contains("Sign in with SSO", "/oauth2/authorization/clamav", "Sign in locally");
            MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                .param("username", "admin").param("password", "OidcCompatibilityLocalAdmin456"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"))
                .andReturn().getRequest().getSession(false);
            assertThat(session).isNotNull();
            discovery.verifyNoInteractions(); // Offline issuer does not affect startup, login page or local authentication.

            discovery.when(() -> ClientRegistrations.fromIssuerLocation(oidc.getIssuer()))
                .thenAnswer(invocation -> LazyOidcClientRegistrationRepositoryTest.discovered(oidc.getIssuer()));
            String redirect = mvc.perform(get("/oauth2/authorization/clamav").secure(true))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            var query = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
            String scopes = URLDecoder.decode(query.getFirst("scope"), StandardCharsets.UTF_8);
            assertThat(scopes.split(" ")).containsExactlyInAnyOrder("openid", "profile", "email", "groups");
            assertThat(query.getFirst("client_id")).isEqualTo("standard-ui-client");
            assertThat(query.getFirst("state")).isNotBlank();
            assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
                .endsWith("/login/oauth2/code/clamav");

            mvc.perform(get("/admin/settings/authentication").session(session))
                .andExpect(redirectedUrl("/admin/configuration-center?category=identity"));
            mvc.perform(info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.request("review",Map.of(
                    "version",center.engine().store().snapshot().revision(),"changes",Map.of("clamav.identity.oidc.scopes","openid,profile,custom:read")),session))
                .andExpect(status().isBadRequest());
            var reviewed=info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.review(mvc,center,
                    Map.of("clamav.identity.oidc.groups-claim","roles","clamav.identity.oidc.groups.viewer","readers"),session);
            info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.apply(mvc,reviewed,session,"OidcCompatibilityLocalAdmin456");
            assertThat(oidc.getScopes()).containsExactly("openid","profile","email","groups");
            assertThat(oidc.getGroupsClaim()).isEqualTo("groups");
            assertThat(center.engine().values(true)).containsEntry("clamav.identity.oidc.groups-claim","roles");
            assertThat(settings.effectiveSecret("oidc")).isEqualTo("synthetic-standard-ui-secret");
        }
    }
}
