package info.trizub.clamav.webclient.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods=false)
public class IdentityOidcConfiguration {
    @Bean ClientRegistrationRepository externalOidcClientRegistrations(IdentityProperties props, AuthenticationSettingsService settings) {
        return new LazyOidcClientRegistrationRepository(props.getOidc(), settings);
    }
}
