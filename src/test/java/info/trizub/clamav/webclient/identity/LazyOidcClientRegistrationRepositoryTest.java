package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LazyOidcClientRegistrationRepositoryTest {
    static ClientRegistration.Builder discovered(String issuer) {
        return ClientRegistration.withRegistrationId("discovered")
            .issuerUri(issuer).authorizationUri(issuer + "/authorize").tokenUri(issuer + "/token")
            .jwkSetUri(issuer + "/jwks").userInfoUri(issuer + "/userinfo");
    }

    private IdentityProperties.Oidc config() {
        var oidc = new IdentityProperties.Oidc();
        oidc.setEnabled(true);
        oidc.setIssuer("https://identity.example/realm");
        oidc.setClientId("test-client");
        return oidc;
    }

    @Test void scopesReachAuthorizationCodeRegistrationAndRefreshAfterSettingsChange() {
        var oidc = config();
        var settings = mock(AuthenticationSettingsService.class);
        when(settings.effectiveSecret("oidc")).thenReturn("synthetic-client-secret");
        when(settings.revision()).thenReturn(1L);
        try (var discovery = mockStatic(ClientRegistrations.class)) {
            discovery.when(() -> ClientRegistrations.fromIssuerLocation(oidc.getIssuer()))
                .thenAnswer(invocation -> discovered(oidc.getIssuer()));
            var repository = new LazyOidcClientRegistrationRepository(oidc, settings);
            discovery.verifyNoInteractions();
            var first = repository.findByRegistrationId("clamav");
            assertThat(first.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email", "groups");
            assertThat(first.getClientId()).isEqualTo("test-client");
            assertThat(first.getClientSecret()).isEqualTo("synthetic-client-secret");
            assertThat(first.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
            assertThat(first.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
            assertThat(repository.findByRegistrationId("clamav")).isSameAs(first);
            discovery.verify(() -> ClientRegistrations.fromIssuerLocation(oidc.getIssuer()), times(1));
            oidc.setScopes(List.of("openid", "email", "custom:read"));
            when(settings.revision()).thenReturn(2L);
            assertThat(repository.findByRegistrationId("clamav").getScopes())
                .containsExactlyInAnyOrder("openid", "email", "custom:read").doesNotContain("groups");
            discovery.verify(() -> ClientRegistrations.fromIssuerLocation(oidc.getIssuer()), times(2));
            org.springframework.test.util.ReflectionTestUtils.setField(repository,"environment",
                new org.springframework.mock.env.MockEnvironment().withProperty("clamav.public-url","https://app.example/clamav/"));
            when(settings.revision()).thenReturn(3L);
            assertThat(repository.findByRegistrationId("clamav").getRedirectUri())
                .isEqualTo("https://app.example/clamav/login/oauth2/code/{registrationId}");
        }
    }

    @Test void disabledOrUnknownRegistrationNeverDiscoversTheIssuer() {
        var oidc = config();
        var repository = new LazyOidcClientRegistrationRepository(oidc, mock(AuthenticationSettingsService.class));
        try (var discovery = mockStatic(ClientRegistrations.class)) {
            assertThat(repository.findByRegistrationId("oidc")).isNull();
            oidc.setEnabled(false);
            assertThat(repository.findByRegistrationId("clamav")).isNull();
            discovery.verifyNoInteractions();
        }
    }

    @Test void invalidScopesAreRejectedBeforeIssuerDiscovery() {
        var oidc = config();
        oidc.setScopes(List.of("profile", "email"));
        var settings = mock(AuthenticationSettingsService.class);
        when(settings.effectiveSecret("oidc")).thenReturn("synthetic-client-secret");
        try (var discovery = mockStatic(ClientRegistrations.class)) {
            var repository = new LazyOidcClientRegistrationRepository(oidc, settings);
            assertThatThrownBy(() -> repository.findByRegistrationId("clamav"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("OIDC scopes must include openid");
            discovery.verifyNoInteractions();
        }
    }

    @Test void scopesAcceptCsvOrSpacesWithoutDuplicatesAndRejectUnsafeValues() {
        assertThat(OidcScopes.parse(" openid,profile email,groups,openid "))
            .containsExactly("openid", "profile", "email", "groups");
        for (String invalid : List.of("openid,\"quoted\"", "openid,back\\slash", "openid\r\nunsafe", "openid," + "x".repeat(1024)))
            assertThatThrownBy(() -> OidcScopes.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
