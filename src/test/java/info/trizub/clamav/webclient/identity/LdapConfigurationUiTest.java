package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;

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
    "clamav.identity.ldap.groups.viewer=readers", "spring.task.scheduling.enabled=false", "clamav.configuration.file=target/test-config/ldap-ui-${random.uuid}/center.properties"
})
@AutoConfigureMockMvc
class LdapConfigurationUiTest {
    @Autowired MockMvc mvc;
    @Autowired AuthenticationSettingsService settings;
    @Autowired info.trizub.clamav.webclient.configuration.ConfigurationCenterService center;

    @Test void deploymentFieldsStayLockedWhileOtherLdapSettingsAndLocalRecoveryWork() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Sign in with LDAP")))
            .andExpect(content().string(containsString("Sign in locally")));
        mvc.perform(post("/login").with(csrf()).param("username", "admin")
                .param("password", "LdapCompatibilityLocalRecoverySecret456"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dashboard"));
        mvc.perform(get("/admin/settings/authentication").with(user("administrator").roles("ADMIN")))
            .andExpect(redirectedUrl("/admin/configuration-center?category=identity"));
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.request("review",Map.of(
                "version",center.engine().store().snapshot().revision(),"changes",Map.of("clamav.identity.ldap.group-search-filter","(&(objectClass=group)(member={0}))")),session))
            .andExpect(status().isBadRequest());
        var reviewed=info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.review(mvc,center,
                Map.of("clamav.identity.ldap.group-name-attribute","displayName"),session);
        info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.apply(mvc,reviewed,session,"LdapCompatibilityLocalRecoverySecret456");
        assertThat(center.engine().values(true)).containsEntry("clamav.identity.ldap.group-name-attribute","displayName");
        assertThat(center.engine().values(false)).containsEntry("clamav.identity.ldap.group-name-attribute","cn");
        assertThat(settings.properties().getLdap().getGroupSearchFilter()).isEqualTo("(member={0})");
        assertThat(settings.effectiveSecret("ldap")).isEqualTo("synthetic-bootstrap-secret");
        mvc.perform(get("/admin/configuration-center/api/view").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("displayName")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("synthetic-bootstrap-secret"))));
    }
}
