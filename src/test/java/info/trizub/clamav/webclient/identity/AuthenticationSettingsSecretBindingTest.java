package info.trizub.clamav.webclient.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuthenticationSettingsSecretBindingTest {
    final EmbeddedDatabase database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
        .addScript("db/migration/h2/V4__authentication_settings.sql").build();
    final JdbcTemplate jdbc=new JdbcTemplate(database);
    @AfterEach void close() { database.shutdown(); }
    AuthenticationSettingsService service() {
        var props=new IdentityProperties();
        props.getLdap().setUrl("ldaps://directory.example.test");props.getLdap().setBaseDn("dc=example");
        props.getLdap().setManagerDn("cn=reader,dc=example");
        props.getOidc().setIssuer("https://identity.example.test");props.getOidc().setClientId("original-client");
        var service=new AuthenticationSettingsService(jdbc,new ObjectMapper(),props);service.load();return service;
    }
    String cipher(String provider) { return jdbc.queryForObject("SELECT encrypted_secret FROM auth_provider_settings WHERE provider=?",String.class,provider); }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void legacyTestAndSaveRejectChangedDestinationAndRetainTheOriginalSecret(String provider) {
        var service=service();service.save(provider,service.values(provider),"synthetic-saved-secret","fixture");
        long revision=service.revision();String old=cipher(provider);
        var fields=new LinkedHashMap<>(service.values(provider));
        fields.put(provider.equals("ldap")?"url":"issuer",provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test");
        assertThatThrownBy(()->service.validateDraft(provider,fields,"")).hasMessageContaining("Re-enter");
        assertThatThrownBy(()->service.save(provider,fields,"","operator")).hasMessageContaining("Re-enter");
        assertThat(service.revision()).isEqualTo(revision);assertThat(cipher(provider)).isEqualTo(old);
        assertThat(service.effectiveSecret(provider)).isEqualTo("synthetic-saved-secret");
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void unchangedBlankAndExplicitReentryBothRemainSupported(String provider) {
        var service=service();service.save(provider,service.values(provider),"synthetic-saved-secret","fixture");
        service.validateDraft(provider,service.values(provider),"");service.save(provider,service.values(provider),"","operator");
        assertThat(service.effectiveSecret(provider)).isEqualTo("synthetic-saved-secret");
        var fields=new LinkedHashMap<>(service.values(provider));
        fields.put(provider.equals("ldap")?"url":"issuer",provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test");
        service.validateDraft(provider,fields,"synthetic-saved-secret");service.save(provider,fields,"synthetic-saved-secret","operator");
        assertThat(service.values(provider)).containsAllEntriesOf(fields);
        assertThat(cipher(provider)).startsWith("gcm2:").doesNotContain("synthetic-saved-secret");
    }
    @Test void swappingNewCiphertextBetweenProvidersFailsAuthentication() {
        var service=service();service.save("ldap",service.values("ldap"),"synthetic-ldap-secret","fixture");
        service.save("oidc",service.values("oidc"),"synthetic-oidc-secret","fixture");
        jdbc.update("UPDATE auth_provider_settings SET encrypted_secret=? WHERE provider='ldap'",cipher("oidc"));
        assertThatThrownBy(()->service.effectiveSecret("ldap")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot decrypt");
    }
    @Test void oldCiphertextRemainsReadableAndUpgradesOnAnUnchangedSave() throws Exception {
        var service=service();service.save("ldap",service.values("ldap"),"synthetic-legacy-secret","fixture");
        byte[] key=ReflectionTestUtils.invokeMethod(service,"key"),iv=new byte[12];new java.security.SecureRandom().nextBytes(iv);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        byte[] encrypted=cipher.doFinal("synthetic-legacy-secret".getBytes(StandardCharsets.UTF_8));
        byte[] joined=Arrays.copyOf(iv,iv.length+encrypted.length);System.arraycopy(encrypted,0,joined,iv.length,encrypted.length);
        String legacy="gcm1:"+Base64.getEncoder().encodeToString(joined);
        jdbc.update("UPDATE auth_provider_settings SET encrypted_secret=? WHERE provider='ldap'",legacy);
        service.load();assertThat(service.effectiveSecret("ldap")).isEqualTo("synthetic-legacy-secret");
        assertThat(cipher("ldap")).isEqualTo(legacy);
        service.save("ldap",service.values("ldap"),"","operator");
        assertThat(cipher("ldap")).startsWith("gcm2:");assertThat(service.effectiveSecret("ldap")).isEqualTo("synthetic-legacy-secret");
    }
}
