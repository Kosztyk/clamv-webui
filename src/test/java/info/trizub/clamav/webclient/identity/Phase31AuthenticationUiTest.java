package info.trizub.clamav.webclient.identity;

import info.trizub.clamav.webclient.configuration.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static info.trizub.clamav.webclient.configuration.ConfigurationHttpTestSupport.*;

/** Legacy values survive upgrade; only the complete central editor can change them. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:phase31authui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=Phase31IndependentEmergencyAdmin456",
    "clamav.configuration.file=target/test-config/phase31-consolidated-${random.uuid}/center.properties",
    "clamav.config.initial-delay-ms=3600000","clamav.metrics.clamd-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class Phase31AuthenticationUiTest {
    @Autowired MockMvc mvc;
    @Autowired AuthenticationSettingsService settings;
    @Autowired ConfigurationCenterService center;
    @Autowired JdbcTemplate jdbc;

    @Test void oldIdentityBookmarkRedirectsAndLocalRecoveryStillAuthenticates() throws Exception {
        mvc.perform(get("/admin/settings/authentication").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/settings/authentication").with(user("admin").roles("ADMIN")))
            .andExpect(redirectedUrl("/admin/configuration-center?category=identity"));
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in locally")));
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","Phase31IndependentEmergencyAdmin456"))
            .andExpect(redirectedUrl("/dashboard"));
    }
    @Test void everyRuntimeProviderOptionHasExactlyOneCentralField() {
        for(String provider:List.of("ldap","oidc")) {
            var fields=ConfigurationCatalog.all().stream().filter(f->f.section().equals(provider)&&!f.legacy().isBlank()).toList();
            assertThat(fields.stream().map(ConfigurationSetting::legacy).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(AuthenticationSettingsService.optionKeys(provider));
            assertThat(fields.stream().map(ConfigurationSetting::legacy).toList()).doesNotHaveDuplicates();
            assertThat(fields).allMatch(f->!f.advanced());
            assertThat(ConfigurationCatalog.find("clamav.identity."+provider+"."+(provider.equals("ldap")?"manager-password":"client-secret"))).isPresent();
        }
    }
    @Test void allLegacyLdapValuesAndEncryptedCredentialsRemainAvailableToTheCentralEditor() throws Exception {
        Map<String,String> legacy=new LinkedHashMap<>();
        legacy.put("enabled","true");legacy.put("url","ldaps://directory.example.invalid:636");
        legacy.put("baseDn","dc=example,dc=invalid");legacy.put("managerDn","cn=service,dc=example,dc=invalid");
        legacy.put("userSearchBase","ou=people");legacy.put("userSearchFilter","(uid={0})");
        legacy.put("groupSearchBase","ou=groups");legacy.put("groupSearchFilter","(uniqueMember={0})");
        legacy.put("groupMemberAttribute","uniqueMember");legacy.put("groupNameAttribute","displayName");
        legacy.put("startTls","false");legacy.put("nestedGroups","true");legacy.put("maxNestedDepth","4");
        legacy.put("pooled","true");legacy.put("connectTimeoutMillis","3000");legacy.put("readTimeoutMillis","7000");
        legacy.put("referrals","throw");legacy.put("viewer","readers");legacy.put("operator","operators");legacy.put("admin","administrators");
        String secret="retained-legacy-manager-secret";
        settings.save("ldap",legacy,secret,"upgrade-fixture");settings.load();
        assertThat(ConfigurationCatalog.provider("ldap",center.engine().values(true))).containsAllEntriesOf(legacy);
        assertThat(center.engine().values(true)).containsEntry("clamav.identity.ldap.manager-password",secret);
        String encrypted=jdbc.queryForObject("select encrypted_secret from auth_provider_settings where provider='ldap'",String.class);
        assertThat(encrypted).startsWith("gcm1:").doesNotContain(secret);
        mvc.perform(get("/admin/configuration-center/api/view").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("displayName")));
    }
    @Test void centralOidcChangesAreReviewedEncryptedAndStagedWithoutChangingActiveLogin() throws Exception {
        var session=new MockHttpSession();
        var changes=Map.of("clamav.identity.oidc.enabled","true","clamav.identity.oidc.issuer","https://identity.example.invalid",
            "clamav.identity.oidc.client-id","central-client","clamav.identity.oidc.client-secret","central-private-client-secret",
            "clamav.identity.oidc.scopes","openid profile email groups custom:read","clamav.identity.oidc.groups-claim","realm_access.roles",
            "clamav.identity.oidc.groups.viewer","readers","clamav.identity.oidc.logout-mode","provider");
        var reviewed=review(mvc,center,changes,session);
        assertThat(reviewed.toString()).doesNotContain("central-private-client-secret");
        mvc.perform(request("apply",Map.of("reviewId",reviewed.get("reviewId").asText(),"verificationUsername","admin","verificationPassword","wrong"),session))
            .andExpect(status().isBadRequest());
        apply(mvc,reviewed,session,"Phase31IndependentEmergencyAdmin456");
        assertThat(center.engine().store().saved("clamav.identity.oidc.scopes")).contains("openid,profile,email,groups,custom:read");
        assertThat(center.engine().values(true)).containsEntry("clamav.identity.oidc.groups-claim","realm_access.roles");
        assertThat(settings.properties().getOidc().isEnabled()).isFalse();
        assertThat(Files.readString(center.engine().store().file())).doesNotContain("central-private-client-secret");
    }
    @Test void retiredProviderSavesAndTestsCannotMutateEitherConfigurationStore() throws Exception {
        long revision=settings.revision();String central=center.engine().store().snapshot().revision();
        for(String provider:List.of("ldap","oidc")) for(String action:List.of("save","test")) {
            String path="/admin/settings/authentication/"+provider+"/"+action;
            mvc.perform(post(path).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).with(user("admin").roles("ADMIN")).param("secret","never-reflect-retired-secret"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.saved").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("never-reflect-retired-secret"))));
        }
        assertThat(settings.revision()).isEqualTo(revision);
        assertThat(center.engine().store().snapshot().revision()).isEqualTo(central);
    }
    @Test void centralValidationRejectsIncompatibleProviderLimitsAndMissingOpenid() throws Exception {
        for(var change:List.of(Map.of("clamav.identity.ldap.max-nested-depth","9"),Map.of("clamav.identity.ldap.connect-timeout-millis","999"),
                Map.of("clamav.identity.oidc.scopes","profile,email")))
            mvc.perform(request("review",Map.of("version",center.engine().store().snapshot().revision(),"changes",change),new MockHttpSession()))
                .andExpect(status().isBadRequest());
        assertThat(ConfigurationCatalog.validate(ConfigurationCatalog.get("clamav.identity.ldap.max-nested-depth"),"04")).isEqualTo("4");
    }
}
