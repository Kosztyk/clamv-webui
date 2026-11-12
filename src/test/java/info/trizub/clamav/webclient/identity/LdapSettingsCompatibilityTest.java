package info.trizub.clamav.webclient.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LdapSettingsCompatibilityTest {
    @TempDir Path directory;
    private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2).addScript("db/migration/h2/V4__authentication_settings.sql").build();
    private final JdbcTemplate jdbc = new JdbcTemplate(database);
    @AfterEach void close() { database.shutdown(); }

    private IdentityProperties bootstrap(String password, String file) {
        var properties = new IdentityProperties();
        var ldap = properties.getLdap();
        ldap.setEnabled(true);
        ldap.setUrl("ldaps://directory.example");
        ldap.setBaseDn("dc=example");
        ldap.setManagerDn("cn=service,dc=example");
        ldap.setManagerPassword(password);
        ldap.setManagerPasswordFile(file);
        ldap.setGroupSearchBase("ou=groups");
        ldap.getGroups().setViewer("readers");
        return properties;
    }

    private AuthenticationSettingsService service(IdentityProperties properties) {
        var service = new AuthenticationSettingsService(jdbc, new ObjectMapper(), properties);
        service.load();
        return service;
    }

    @Test void customFilterAndEnvironmentSecretSurviveSettingsSaveAndServiceRestart() {
        var service = service(bootstrap("environment-secret", ""));
        Map<String, String> fields = new LinkedHashMap<>(service.values("ldap"));
        fields.put("groupSearchFilter", "(&(objectClass=group)(member={0}))");
        service.save("ldap", fields, "", "administrator");
        var restarted = service(bootstrap("environment-secret", ""));
        assertThat(restarted.values("ldap")).containsEntry("url", "ldaps://directory.example")
            .containsEntry("groupSearchFilter", "(&(objectClass=group)(member={0}))");
        assertThat(restarted.properties().getLdap().getGroupSearchFilter()).isEqualTo(fields.get("groupSearchFilter"));
        assertThat(restarted.effectiveSecret("ldap")).isEqualTo("environment-secret");
        assertThat(restarted.hasSecret("ldap")).isTrue();
        assertThat(restarted.values("ldap").toString()).doesNotContain("environment-secret");
        assertThat(jdbc.queryForObject("SELECT settings_json FROM auth_provider_settings WHERE provider='ldap'", String.class))
            .doesNotContain("environment-secret");
        assertThat(jdbc.queryForObject("SELECT encrypted_secret FROM auth_provider_settings WHERE provider='ldap'", String.class))
            .isNull();
        // Older callers omitting the new field retain it; explicitly clearing it restores legacy lookup.
        fields.remove("groupSearchFilter");
        restarted.save("ldap", fields, "", "administrator");
        assertThat(restarted.values("ldap")).containsEntry("groupSearchFilter", "(&(objectClass=group)(member={0}))");
        fields.put("groupSearchFilter", "");
        restarted.save("ldap", fields, "", "administrator");
        assertThat(restarted.properties().getLdap().getGroupSearchFilter()).isEmpty();
    }

    @Test void legacySavedSettingsKeepMemberAttributeAndGainAnEmptyCustomFilter() throws Exception {
        var bootstrap = bootstrap("environment-secret", "");
        bootstrap.getLdap().setGroupSearchFilter("(memberUid={1})");
        Map<String, String> old = new LinkedHashMap<>(service(bootstrap).values("ldap"));
        old.remove("groupSearchFilter");
        old.put("groupMemberAttribute", "uniqueMember");
        jdbc.update("INSERT INTO auth_provider_settings(provider,settings_json,updated_at,updated_by) VALUES (?,?,CURRENT_TIMESTAMP,?)",
            "ldap", new ObjectMapper().writeValueAsString(old), "legacy-administrator");
        var restarted = service(bootstrap("environment-secret", ""));
        assertThat(restarted.values("ldap")).containsEntry("groupSearchFilter", "")
            .containsEntry("groupMemberAttribute", "uniqueMember");
        assertThat(new LdapGroupSearchFilter(restarted.properties().getLdap()).render("uid=sam,dc=example", "sam"))
            .isEqualTo("(uniqueMember=uid=sam,dc=example)");
        assertThat(restarted.effectiveSecret("ldap")).isEqualTo("environment-secret");
    }

    @Test void secretFileSourceIsRetainedAndCanRotateAfterSaveAndRestart() throws Exception {
        var file = directory.resolve("ldap-bind-secret");
        Files.writeString(file, "first-secret\n");
        var service = service(bootstrap("", file.toString()));
        service.save("ldap", service.values("ldap"), "", "administrator");
        var restarted = service(bootstrap("", file.toString()));
        assertThat(restarted.effectiveSecret("ldap")).isEqualTo("first-secret");
        Files.writeString(file, "rotated-secret\n");
        assertThat(restarted.effectiveSecret("ldap")).isEqualTo("rotated-secret");
        assertThat(restarted.properties().getLdap().getManagerPasswordFile()).isEqualTo(file.toString());
    }

    @Test void malformedGroupFilterCannotReplaceTheSavedConfiguration() {
        var service = service(bootstrap("environment-secret", ""));
        Map<String, String> fields = new LinkedHashMap<>(service.values("ldap"));
        service.save("ldap", fields, "", "administrator");
        fields.put("groupSearchFilter", "(member={2})");
        assertThatThrownBy(() -> service.save("ldap", fields, "", "administrator"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.values("ldap")).containsEntry("groupSearchFilter", "");
        assertThat(service.effectiveSecret("ldap")).isEqualTo("environment-secret");
    }
}
