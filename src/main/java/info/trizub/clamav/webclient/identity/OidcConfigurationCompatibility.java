package info.trizub.clamav.webclient.identity;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.function.Consumer;

/** Bridges the requested Spring 'oidc' registration to our existing lazy provider. */
@Configuration(proxyBeanMethods = false)
public class OidcConfigurationCompatibility {
    // A static post-processor runs after @ConfigurationProperties binding and before
    // AuthenticationSettingsService captures bootstrap secrets and loads UI overrides.
    // It performs no issuer discovery, so an offline IdP cannot prevent local login.
    @Bean static BeanPostProcessor oidcConfigurationAliases(Environment environment) {
        return new Aliases(environment);
    }

    private static final class Aliases implements BeanPostProcessor {
        private static final String CUSTOM = "clamav.identity.oidc.";
        private static final String REGISTRATION = "spring.security.oauth2.client.registration.oidc.";
        private static final String ISSUER = "spring.security.oauth2.client.provider.oidc.issuer-uri";
        private final Environment environment;

        private Aliases(Environment environment) { this.environment = environment; }

        @Override public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (!(bean instanceof IdentityProperties properties)) return bean;
            IdentityProperties.Oidc oidc = properties.getOidc();
            copy("issuer", ISSUER, oidc::setIssuer);
            copy("client-id", REGISTRATION + "client-id", oidc::setClientId);
            copy("groups-claim", null, oidc::setGroupsClaim);
            copy("button-label", null, oidc::setButtonLabel);
            copy("logout-mode", null, oidc::setLogoutMode);
            // Resolve the documented underscore-separated legacy environment names too.
            String file = environment.getProperty(CUSTOM + "client-secret-file");
            if (file != null) oidc.setClientSecretFile(file);
            // A legacy secret file is an explicit source; never combine it with an alias.
            copy("client-secret", oidc.getClientSecretFile().isBlank()
                ? REGISTRATION + "client-secret" : null, oidc::setClientSecret);

            Binder binder = Binder.get(environment);
            var customScopes = binder.bind(CUSTOM + "scopes", Bindable.listOf(String.class));
            if (customScopes.isBound()) oidc.setScopes(customScopes.get());
            else binder.bind(REGISTRATION + "scope", Bindable.listOf(String.class)).ifBound(oidc::setScopes);

            String explicitEnabled = environment.getProperty(CUSTOM + "enabled");
            if (explicitEnabled != null) {
                oidc.setEnabled(environment.getProperty(CUSTOM + "enabled", Boolean.class, false));
            } else if (present(REGISTRATION + "client-id") && present(REGISTRATION + "client-secret")
                    && present(ISSUER) && !oidc.getIssuer().isBlank() && !oidc.getClientId().isBlank()
                    && (!oidc.getClientSecret().isBlank() || !oidc.getClientSecretFile().isBlank())) {
                oidc.setEnabled(true);
            }
            return bean;
        }

        private void copy(String custom, String standard, Consumer<String> setter) {
            String value = environment.getProperty(CUSTOM + custom);
            if (value == null && standard != null) value = environment.getProperty(standard);
            if (value != null) setter.accept(value);
        }

        private boolean present(String property) {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank();
        }
    }
}
