package info.trizub.clamav.webclient.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Regression for changing LDAP URL/base DN after authenticating existing users. */
class Issue44LdapDirectoryIdentityTest {
  private final EmbeddedDatabase database =
      new EmbeddedDatabaseBuilder()
          .generateUniqueName(true)
          .setType(EmbeddedDatabaseType.H2)
          .addScript("db/migration/h2/V3__external_identities.sql")
          .addScript("db/migration/h2/V4__authentication_settings.sql")
          .addScript("db/migration/h2/V8__ldap_directory_identity.sql")
          .build();
  private final JdbcTemplate jdbc = new JdbcTemplate(database);

  @AfterEach
  void close() {
    database.shutdown();
  }

  private static IdentityProperties bootstrap(String url, String baseDn, String explicit) {
    IdentityProperties properties = new IdentityProperties();
    var ldap = properties.getLdap();
    ldap.setEnabled(true);
    ldap.setUrl(url);
    ldap.setBaseDn(baseDn);
    ldap.setDirectoryId(explicit);
    ldap.setManagerDn("cn=service,dc=example");
    ldap.setManagerPassword("test-bind-secret");
    ldap.setGroupSearchBase("ou=groups");
    ldap.getGroups().setViewer("readers");
    return properties;
  }

  private AuthenticationSettingsService initialize(IdentityProperties config) {
    AuthenticationSettingsService settings =
        new AuthenticationSettingsService(jdbc, new ObjectMapper(), config);
    settings.load();
    return settings;
  }

  private static String principal(IdentityProperties properties, String dn) {
    return new ExternalIdentityMapper(properties)
        .map(
            ExternalIdentityMapper.Source.LDAP,
            properties.getLdap().getDirectoryId(),
            dn,
            List.of("readers"))
        .principalName();
  }

  @Test
  void upgradePinsHistoricalIssuerExactlyAndKeepsPrincipalAcrossEndpointAndBaseChange() {
    var original = initialize(bootstrap("ldaps://old.example.test:636", "dc=example,dc=test", ""));
    String dn = "uid=sam,ou=people,dc=example,dc=test";
    String formerIssuer = "ldaps://old.example.test:636/dc=example,dc=test";
    String originalHash = principal(original.properties(), dn);
    assertThat(original.properties().getLdap().getDirectoryId()).isEqualTo(formerIssuer);
    assertThat(original.properties().getLdap().getConfiguredDirectoryId()).isBlank();
    assertThat(originalHash)
        .isEqualTo(
            new ExternalIdentityMapper(original.properties())
                .map(ExternalIdentityMapper.Source.LDAP, formerIssuer, dn, List.of("readers"))
                .principalName());
    jdbc.update(
        "INSERT INTO external_identities (principal_name,source,issuer,subject,first_seen_at,last_seen_at) "
            + "VALUES (?,'LDAP',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        originalHash,
        formerIssuer,
        dn);
    var restarted =
        initialize(bootstrap("ldaps://failover.example.test:636", "dc=other,dc=test", ""));
    assertThat(restarted.properties().getLdap().getDirectoryId()).isEqualTo(formerIssuer);
    assertThat(principal(restarted.properties(), dn)).isEqualTo(originalHash);
    assertThat(
            jdbc.queryForObject(
                "SELECT issuer FROM external_identities WHERE principal_name=?",
                String.class,
                originalHash))
        .isEqualTo(formerIssuer);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identities", Integer.class))
        .isEqualTo(1);
    assertThat(restarted.pinnedLdapDirectoryId()).isEqualTo(formerIssuer);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ldap_directory_identity", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void explicitIdCanBeSelectedOnFirstSetupButCannotBeRotatedLater() {
    var first =
        initialize(bootstrap("ldaps://ldap-one.example.test", "dc=example", "office-directory"));
    assertThat(first.properties().getLdap().getDirectoryId()).isEqualTo("office-directory");
    String user = "uid=user,dc=example";
    String canonical = principal(first.properties(), user);
    var second =
        initialize(bootstrap("ldaps://ldap-two.example.test", "dc=other", "office-directory"));
    assertThat(principal(second.properties(), user)).isEqualTo(canonical);
    assertThatThrownBy(
            () ->
                initialize(
                    bootstrap("ldaps://ldap-three.example.test", "dc=other", "other-directory")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already pinned");
    assertThat(
            jdbc.queryForObject(
                "SELECT directory_id FROM ldap_directory_identity WHERE name='primary'",
                String.class))
        .isEqualTo("office-directory");
  }

  @Test
  void disabledLdapDoesNotPinAnEmptyIssuerOrStealTheFirstConfiguredIdentifier() {
    var disabled = bootstrap("", "", "");
    disabled.getLdap().setEnabled(false);
    initialize(disabled);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ldap_directory_identity", Integer.class))
        .isEqualTo(0);
    var enabled =
        initialize(bootstrap("ldaps://ldap.example.test", "dc=example", "stable-identity"));
    assertThat(enabled.properties().getLdap().getDirectoryId()).isEqualTo("stable-identity");
  }

  @Test
  void temporarilyDisabledConfiguredLdapStillPinsHistoricalIdentity() {
    var disabled = bootstrap("ldaps://old-server.example.test", "dc=old", "");
    disabled.getLdap().setEnabled(false);
    initialize(disabled);
    assertThat(
            jdbc.queryForObject(
                "SELECT directory_id FROM ldap_directory_identity WHERE name='primary'",
                String.class))
        .isEqualTo("ldaps://old-server.example.test/dc=old");
    var enabled = initialize(bootstrap("ldaps://new-server.example.test", "dc=new", ""));
    assertThat(enabled.properties().getLdap().getDirectoryId())
        .isEqualTo("ldaps://old-server.example.test/dc=old");
  }

  @Test
  void invalidExplicitDirectoryIdsAreRejected() {
    assertThatThrownBy(() -> LdapDirectoryIdentity.validateExplicit("foo/bar"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LdapDirectoryIdentity.validateExplicit("bad id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LdapDirectoryIdentity.validateExplicit("a".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
