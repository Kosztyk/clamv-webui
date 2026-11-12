package info.trizub.clamav.webclient.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OidcSettingsCompatibilityTest {
    private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2).addScript("db/migration/h2/V4__authentication_settings.sql").build();
    private final JdbcTemplate jdbc = new JdbcTemplate(database);
    @AfterEach void close() { database.shutdown(); }

    private IdentityProperties bootstrap() {
        var properties = new IdentityProperties();
        var oidc = properties.getOidc();
        oidc.setEnabled(true);
        oidc.setIssuer("https://startup.example/realm");
        oidc.setClientId("startup-client");
        oidc.setClientSecret("synthetic-startup-secret");
        oidc.getGroups().setViewer("readers");
        return properties;
    }

    private AuthenticationSettingsService service(IdentityProperties properties) {
        var service = new AuthenticationSettingsService(jdbc, new ObjectMapper(), properties);
        service.load();
        return service;
    }

    @Test void savedScopesAndExplicitDisableOverrideBootstrapAfterRestartWithoutLosingSecret() {
        var service = service(bootstrap());
        var fields = new LinkedHashMap<>(service.values("oidc"));
        fields.put("scopes", "openid email custom:read openid");
        fields.put("enabled", "false");
        service.save("oidc", fields, "", "administrator");
        var restarted = service(bootstrap());
        assertThat(restarted.properties().getOidc().isEnabled()).isFalse();
        assertThat(restarted.properties().getOidc().getIssuer()).isEqualTo("https://startup.example/realm");
        assertThat(restarted.properties().getOidc().getScopes()).containsExactly("openid", "email", "custom:read");
        assertThat(restarted.values("oidc")).containsEntry("scopes", "openid,email,custom:read");
        assertThat(restarted.effectiveSecret("oidc")).isEqualTo("synthetic-startup-secret");
        assertThat(restarted.values("oidc").toString()).doesNotContain("synthetic-startup-secret");
        assertThat(jdbc.queryForObject("SELECT settings_json FROM auth_provider_settings WHERE provider='oidc'", String.class))
            .doesNotContain("synthetic-startup-secret");
        // An older caller which has no scopes field keeps the currently active scopes.
        fields.remove("scopes");
        restarted.save("oidc", fields, "", "administrator");
        assertThat(restarted.values("oidc")).containsEntry("scopes", "openid,email,custom:read");
    }

    @Test void oldSavedJsonWithoutScopesRetainsBootstrapScopeConfiguration() throws Exception {
        var fields = new LinkedHashMap<>(service(bootstrap()).values("oidc"));
        fields.remove("scopes");
        jdbc.update("INSERT INTO auth_provider_settings(provider,settings_json,updated_at,updated_by) VALUES (?,?,CURRENT_TIMESTAMP,?)",
            "oidc", new ObjectMapper().writeValueAsString(fields), "legacy-admin");
        var startup = bootstrap();
        startup.getOidc().setScopes(List.of("openid", "email"));
        var restarted = service(startup);
        assertThat(restarted.properties().getOidc().getScopes()).containsExactly("openid", "email");
        assertThat(restarted.effectiveSecret("oidc")).isEqualTo("synthetic-startup-secret");
    }

    @Test void invalidScopeSaveLeavesPriorConfigurationAndRevisionIntact() {
        var service = service(bootstrap());
        service.save("oidc", service.values("oidc"), "", "administrator");
        long revision = service.revision();
        var fields = new LinkedHashMap<>(service.values("oidc"));
        fields.put("scopes", "profile,email");
        assertThatThrownBy(() -> service.save("oidc", fields, "", "administrator"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("include openid");
        assertThat(service.revision()).isEqualTo(revision);
        assertThat(service.values("oidc")).containsEntry("scopes", "openid,profile,email,groups");
    }
}
