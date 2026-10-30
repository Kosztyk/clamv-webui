package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real binding, UI routes, H2 persistence and local recovery; directory connection alone is mocked. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ldap-configuration-ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=LdapCompatibilityLocalRecoverySecret456",
    "security.ldap.enabled=true", "security.ldap.url=ldaps://127.0.0.1:9",
    "security.ldap.base-dn=dc=example", "security.ldap.bind-dn=cn=service,dc=example",
    "security.ldap.bind-password=synthetic-bootstrap-secret",
    "security.ldap.group-search-base=ou=groups", "security.ldap.group-search-filter=(member={0})",
    "clamav.identity.ldap.groups.viewer=readers", "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class LdapConfigurationUiTest {
    @Autowired MockMvc mvc;
    @Autowired AuthenticationSettingsService settings;
    @MockitoBean AuthenticationProviderProber prober;

    @Test void aliasConfiguredLdapRemainsEditableAndLocalRecoveryWorks() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Sign in with LDAP")))
            .andExpect(content().string(containsString("Sign in locally")));
        mvc.perform(post("/login").with(csrf()).param("username", "admin")
                .param("password", "LdapCompatibilityLocalRecoverySecret456"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"));
        mvc.perform(get("/admin/settings/authentication").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"groupSearchFilter\"")));
        String custom = "(&(objectClass=group)(member={0}))";
        mvc.perform(post("/admin/settings/authentication/ldap/save").secure(true).with(csrf())
                .with(user("administrator").roles("ADMIN"))
                .header("X-Requested-With", "XMLHttpRequest")
                .param("enabled", "true").param("url", "ldaps://127.0.0.1:9")
                .param("baseDn", "dc=example").param("managerDn", "cn=service,dc=example")
                .param("userSearchFilter", "(uid={0})").param("groupSearchBase", "ou=groups")
                .param("groupSearchFilter", custom).param("viewer", "readers"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
        verify(prober).test(eq("ldap"), argThat(options -> custom.equals(options.get("groupSearchFilter"))),
            eq("synthetic-bootstrap-secret"));
        settings.load();
        assertThat(settings.properties().getLdap().getGroupSearchFilter()).isEqualTo(custom);
        assertThat(settings.effectiveSecret("ldap")).isEqualTo("synthetic-bootstrap-secret");
        mvc.perform(get("/admin/settings/authentication").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("value=\"(&amp;(objectClass=group)(member={0}))\"")));
    }
}
