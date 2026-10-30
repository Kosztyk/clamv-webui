package info.trizub.clamav.webclient.identity;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** Dynamic, lazy issuer discovery; no external IdP network dependency at startup. */
public class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {
    private final IdentityProperties.Oidc config;
    private final AuthenticationSettingsService settings;
    private volatile ClientRegistration registration;
    private volatile long registrationRevision=-1;
    public LazyOidcClientRegistrationRepository(IdentityProperties.Oidc config, AuthenticationSettingsService settings) {
        this.config=config;this.settings=settings;
    }
    @Override public ClientRegistration findByRegistrationId(String id) {
        if (!"clamav".equals(id) || !config.isEnabled()) return null;
        long revision=settings.revision();
        ClientRegistration cached=registration;
        if (cached!=null && registrationRevision==revision) return cached;
        synchronized(this) {
            if (registration!=null && registrationRevision==revision) return registration;
            if(!config.getIssuer().startsWith("https://") || config.getClientId().isBlank())
                throw new IllegalStateException("OIDC provider is not configured");
            String secret=settings.effectiveSecret("oidc");
            if(secret.isBlank()) throw new IllegalStateException("OIDC client secret missing");
            var scopes = OidcScopes.normalize(config.getScopes());
            // Discovery only happens when the SSO button is clicked, never during local login.
            ClientRegistration resolved=ClientRegistrations.fromIssuerLocation(config.getIssuer())
                .registrationId("clamav").clientId(config.getClientId()).clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(scopes)
                .clientName(config.getButtonLabel()).build();
            registration=resolved;registrationRevision=revision;
            return resolved;
        }
    }
}
