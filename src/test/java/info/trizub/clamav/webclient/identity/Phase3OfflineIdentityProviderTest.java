package info.trizub.clamav.webclient.identity;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.ExternalIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No live IdP or network needed: offline OIDC issuer must not prevent local emergency login. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase3-offline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=Phase3OfflineLocalAdminSecret456",
    "clamav.identity.ldap.enabled=false",
    "clamav.identity.oidc.enabled=true",
    "clamav.identity.oidc.issuer=https://127.0.0.1:9/realms/offline",
    "clamav.identity.oidc.client-id=phase3-test-client",
    "clamav.identity.oidc.client-secret=phase3-test-nonfunctional-secret",
    "clamav.identity.oidc.groups.viewer=scan-viewers",
    "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class Phase3OfflineIdentityProviderTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository localUsers;
    @Autowired ExternalIdentityRepository external;
    @Test void ssoButtonVisibleButIssuerOutageDoesNotBlockLocalBreakGlass() throws Exception {
        mvc.perform(get("/login"))
           .andExpect(status().isOk())
           .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/clamav")))
           .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Sign in with LDAP"))));
        var result=mvc.perform(post("/login").with(csrf())
            .param("username","admin").param("password","Phase3OfflineLocalAdminSecret456"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"))
            .andReturn();
        MockHttpSession session=(MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isOk());
        assertThat(localUsers.findByUsername("admin")).isPresent();
        assertThat(external.count()).isZero(); // no fake external local user is provisioned
        // CSRF still applies even if an external identity provider is unavailable.
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }
}
