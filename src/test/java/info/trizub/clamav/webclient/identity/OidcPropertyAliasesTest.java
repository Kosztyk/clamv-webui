package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OidcPropertyAliasesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class BindingConfiguration { }

    private Map<String, Object> standardEnvironment() {
        return Map.of(
            "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID", "standard-client",
            "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET", "synthetic-standard-secret",
            "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE", "openid,profile,email,groups",
            "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI", "https://identity.example/realm");
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", new HashMap<>(environment))))
            .withUserConfiguration(BindingConfiguration.class, OidcConfigurationCompatibility.class);
    }

    @Test void allFourRequestedEnvironmentVariablesConfigureAndEnableTheActiveProvider() {
        runner(standardEnvironment()).run(context -> {
            assertThat(context).hasNotFailed();
            var oidc = context.getBean(IdentityProperties.class).getOidc();
            assertThat(oidc.isEnabled()).isTrue();
            assertThat(oidc.getClientId()).isEqualTo("standard-client");
            assertThat(oidc.getClientSecret()).isEqualTo("synthetic-standard-secret");
            assertThat(oidc.getIssuer()).isEqualTo("https://identity.example/realm");
            assertThat(oidc.getScopes()).containsExactly("openid", "profile", "email", "groups");
            assertThat(oidc.getRegistrationId()).isEqualTo("clamav");
        });
    }

    @Test void absentConfigurationKeepsOidcDisabledAndProvidesRequestedDefaultScopes() {
        runner(Map.of()).run(context -> {
            var oidc = context.getBean(IdentityProperties.class).getOidc();
            assertThat(oidc.isEnabled()).isFalse();
            assertThat(oidc.getScopes()).containsExactly("openid", "profile", "email", "groups");
        });
    }

    @Test void incompleteStandardConfigurationDoesNotEnableSso() {
        var partial = new HashMap<>(standardEnvironment());
        partial.remove("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET");
        runner(partial).run(context -> assertThat(context.getBean(IdentityProperties.class).getOidc().isEnabled()).isFalse());
    }

    @Test void existingCanonicalPropertiesOverrideAllAliasesIncludingAutomaticEnable() {
        runner(standardEnvironment()).withPropertyValues(
            "clamav.identity.oidc.enabled=false", "clamav.identity.oidc.issuer=https://legacy.example/realm",
            "clamav.identity.oidc.client-id=legacy-client", "clamav.identity.oidc.client-secret=legacy-secret",
            "clamav.identity.oidc.scopes=openid,email").run(context -> {
                var oidc = context.getBean(IdentityProperties.class).getOidc();
                assertThat(oidc.isEnabled()).isFalse();
                assertThat(oidc.getIssuer()).isEqualTo("https://legacy.example/realm");
                assertThat(oidc.getClientId()).isEqualTo("legacy-client");
                assertThat(oidc.getClientSecret()).isEqualTo("legacy-secret");
                assertThat(oidc.getScopes()).containsExactly("openid", "email");
            });
    }

    @Test void documentedLegacyEnvironmentNamesWinWhenBothFamiliesExist() {
        var both = new HashMap<>(standardEnvironment());
        both.putAll(Map.of("CLAMAV_IDENTITY_OIDC_ENABLED", "false",
            "CLAMAV_IDENTITY_OIDC_CLIENT_ID", "legacy-env-client",
            "CLAMAV_IDENTITY_OIDC_CLIENT_SECRET", "legacy-env-secret",
            "CLAMAV_IDENTITY_OIDC_ISSUER", "https://legacy-env.example/realm",
            "CLAMAV_IDENTITY_OIDC_SCOPES", "openid,profile"));
        runner(both).run(context -> {
            var oidc = context.getBean(IdentityProperties.class).getOidc();
            assertThat(oidc.isEnabled()).isFalse();
            assertThat(oidc.getClientId()).isEqualTo("legacy-env-client");
            assertThat(oidc.getClientSecret()).isEqualTo("legacy-env-secret");
            assertThat(oidc.getIssuer()).isEqualTo("https://legacy-env.example/realm");
            assertThat(oidc.getScopes()).containsExactly("openid", "profile");
        });
    }

    @Test void dottedStandardPropertiesAndIndexedScopeListsAlsoBind() {
        runner(Map.of()).withPropertyValues(
            "spring.security.oauth2.client.registration.oidc.client-id=property-client",
            "spring.security.oauth2.client.registration.oidc.client-secret=property-secret",
            "spring.security.oauth2.client.provider.oidc.issuer-uri=https://property.example/realm",
            "spring.security.oauth2.client.registration.oidc.scope[0]=openid",
            "spring.security.oauth2.client.registration.oidc.scope[1]=custom:read").run(context -> {
                var oidc = context.getBean(IdentityProperties.class).getOidc();
                assertThat(oidc.isEnabled()).isTrue();
                assertThat(oidc.getClientId()).isEqualTo("property-client");
                assertThat(oidc.getClientSecret()).isEqualTo("property-secret");
                assertThat(oidc.getScopes()).containsExactly("openid", "custom:read");
            });
    }

    @Test void legacySecretFileIsNotCombinedWithAStandardInlineSecret() {
        var both = new HashMap<>(standardEnvironment());
        both.put("CLAMAV_IDENTITY_OIDC_CLIENT_SECRET_FILE", "/run/secrets/legacy-oidc");
        runner(both).run(context -> {
            var oidc = context.getBean(IdentityProperties.class).getOidc();
            assertThat(oidc.getClientSecret()).isEmpty();
            assertThat(oidc.getClientSecretFile()).isEqualTo("/run/secrets/legacy-oidc");
        });
    }

    @Test void legacySettingsStillRequireTheirExplicitEnableSwitch() {
        runner(Map.of("CLAMAV_IDENTITY_OIDC_CLIENT_ID", "old-client",
            "CLAMAV_IDENTITY_OIDC_CLIENT_SECRET", "old-secret",
            "CLAMAV_IDENTITY_OIDC_ISSUER", "https://old.example/realm")).run(context -> {
                var oidc = context.getBean(IdentityProperties.class).getOidc();
                assertThat(oidc.getClientId()).isEqualTo("old-client");
                assertThat(oidc.isEnabled()).isFalse();
            });
    }

    @Test void documentedClaimAndButtonEnvironmentNamesRetainRoleMappingAndLogout() {
        var environment = new HashMap<>(standardEnvironment());
        environment.putAll(Map.of("CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM", "roles",
            "CLAMAV_IDENTITY_OIDC_GROUPS_ADMIN", "directory-admins",
            "CLAMAV_IDENTITY_OIDC_BUTTON_LABEL", "Sign in with corporate SSO",
            "CLAMAV_IDENTITY_OIDC_LOGOUT_MODE", "provider"));
        runner(environment).run(context -> {
            var properties = context.getBean(IdentityProperties.class);
            var oidc = properties.getOidc();
            assertThat(oidc.getGroupsClaim()).isEqualTo("roles");
            assertThat(oidc.getButtonLabel()).isEqualTo("Sign in with corporate SSO");
            assertThat(oidc.getLogoutMode()).isEqualTo("provider");
            var memberships = ExternalOidcUserService.extractGroups(
                Map.of("roles", List.of("directory-admins")), oidc.getGroupsClaim());
            var identity = new ExternalIdentityMapper(properties).map(
                ExternalIdentityMapper.Source.OIDC, oidc.getIssuer(), "subject-123", memberships);
            assertThat(identity.authorities()).extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
        });
    }
}
