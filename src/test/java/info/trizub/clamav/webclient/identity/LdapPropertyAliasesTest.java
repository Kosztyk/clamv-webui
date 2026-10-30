package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LdapPropertyAliasesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class BindingConfiguration { }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("ldapTestEnvironment", new HashMap<>(environment))))
            .withUserConfiguration(BindingConfiguration.class);
    }

    @Test void allRequestedEnvironmentVariablesBindWithoutCustomNames() {
        Map<String, Object> environment = Map.of(
            "SECURITY_LDAP_ENABLED", "true",
            "SECURITY_LDAP_URL", "ldaps://directory.example:636",
            "SECURITY_LDAP_BASE_DN", "dc=example,dc=com",
            "SECURITY_LDAP_USER_SEARCH_BASE", "ou=people",
            "SECURITY_LDAP_USER_SEARCH_FILTER", "(sAMAccountName={0})",
            "SECURITY_LDAP_GROUP_SEARCH_BASE", "ou=groups",
            "SECURITY_LDAP_GROUP_SEARCH_FILTER", "(&(objectClass=group)(member={0}))",
            "SECURITY_LDAP_BIND_DN", "cn=service,dc=example,dc=com",
            "SECURITY_LDAP_BIND_PASSWORD", "synthetic-environment-secret");
        runner(environment).run(context -> {
            assertThat(context).hasNotFailed();
            var ldap = context.getBean(IdentityProperties.class).getLdap();
            assertThat(ldap.isEnabled()).isTrue();
            assertThat(ldap.getUrl()).isEqualTo(environment.get("SECURITY_LDAP_URL"));
            assertThat(ldap.getBaseDn()).isEqualTo(environment.get("SECURITY_LDAP_BASE_DN"));
            assertThat(ldap.getUserSearchBase()).isEqualTo("ou=people");
            assertThat(ldap.getUserSearchFilter()).isEqualTo("(sAMAccountName={0})");
            assertThat(ldap.getGroupSearchBase()).isEqualTo("ou=groups");
            assertThat(ldap.getGroupSearchFilter()).isEqualTo(environment.get("SECURITY_LDAP_GROUP_SEARCH_FILTER"));
            assertThat(ldap.getManagerDn()).isEqualTo(environment.get("SECURITY_LDAP_BIND_DN"));
            assertThat(ldap.getManagerPassword()).isEqualTo("synthetic-environment-secret");
        });
    }

    @Test void omittedVariablesKeepLdapDisabledAndLegacyDefaults() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            var ldap = context.getBean(IdentityProperties.class).getLdap();
            assertThat(ldap.isEnabled()).isFalse();
            assertThat(ldap.getUserSearchFilter()).isEqualTo("(uid={0})");
            assertThat(ldap.getGroupSearchFilter()).isEmpty();
            assertThat(new LdapGroupSearchFilter(ldap).render("uid=sam,dc=example", "sam"))
                .isEqualTo("(member=uid=sam,dc=example)");
        });
    }

    @Test void existingCanonicalPropertiesOverrideAliasDefaults() {
        runner(Map.of("SECURITY_LDAP_ENABLED", "true", "SECURITY_LDAP_BIND_DN", "cn=new",
                "SECURITY_LDAP_BIND_PASSWORD", "new-secret", "SECURITY_LDAP_GROUP_SEARCH_FILTER", "(member={0})"))
            .withPropertyValues("clamav.identity.ldap.enabled=false",
                "clamav.identity.ldap.manager-dn=cn=existing",
                "clamav.identity.ldap.manager-password=existing-secret",
                "clamav.identity.ldap.group-search-filter=(uniqueMember={0})")
            .run(context -> {
                var ldap = context.getBean(IdentityProperties.class).getLdap();
                assertThat(ldap.isEnabled()).isFalse();
                assertThat(ldap.getManagerDn()).isEqualTo("cn=existing");
                assertThat(ldap.getManagerPassword()).isEqualTo("existing-secret");
                assertThat(ldap.getGroupSearchFilter()).isEqualTo("(uniqueMember={0})");
            });
    }

    @Test void documentedLegacyEnvironmentNamesWinWhenBothFamiliesArePresent() {
        runner(Map.of("CLAMAV_IDENTITY_LDAP_BASE_DN", "dc=existing",
            "SECURITY_LDAP_BASE_DN", "dc=new",
            "CLAMAV_IDENTITY_LDAP_MANAGER_DN", "cn=existing",
            "SECURITY_LDAP_BIND_DN", "cn=new",
            "CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD", "existing-secret",
            "SECURITY_LDAP_BIND_PASSWORD", "new-secret"))
            .run(context -> {
                var ldap = context.getBean(IdentityProperties.class).getLdap();
                assertThat(ldap.getBaseDn()).isEqualTo("dc=existing");
                assertThat(ldap.getManagerDn()).isEqualTo("cn=existing");
                assertThat(ldap.getManagerPassword()).isEqualTo("existing-secret");
            });
    }

    @Test void dottedSecurityPropertiesAlsoWork() {
        runner(Map.of()).withPropertyValues("security.ldap.enabled=true",
            "security.ldap.url=ldaps://directory.example", "security.ldap.bind-dn=cn=service",
            "security.ldap.bind-password=property-secret", "security.ldap.group-search-filter=(memberUid={1})")
            .run(context -> {
                var ldap = context.getBean(IdentityProperties.class).getLdap();
                assertThat(ldap.isEnabled()).isTrue();
                assertThat(ldap.getUrl()).isEqualTo("ldaps://directory.example");
                assertThat(ldap.getManagerDn()).isEqualTo("cn=service");
                assertThat(ldap.getManagerPassword()).isEqualTo("property-secret");
                assertThat(ldap.getGroupSearchFilter()).isEqualTo("(memberUid={1})");
            });
    }
}
