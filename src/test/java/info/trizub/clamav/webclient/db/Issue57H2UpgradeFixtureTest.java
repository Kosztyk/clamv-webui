package info.trizub.clamav.webclient.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Fast, Docker-free predecessor V4 H2 schema fixture test. The full CI upgrade-path job
 * additionally boots the real v2.1beta1 Docker image, verifies both H2 and PostgreSQL, and never
 * relies on baselineOnMigrate. Only this disposable test DB simulates an already-migrated V4
 * schema.
 */
class Issue57H2UpgradeFixtureTest {
  private static final String PREFIX = "upgrade-fixtures/v2.1beta1/";

  private static String encrypt(byte[] key, String provider, String plain) throws Exception {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    cipher.updateAAD(("clamav-auth-provider:" + provider).getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    byte[] joined = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, joined, 0, iv.length);
    System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
    return "gcm2:" + Base64.getEncoder().encodeToString(joined);
  }

  private static String decrypt(byte[] key, String provider, String encoded) throws Exception {
    assertTrue(encoded.startsWith("gcm2:"));
    byte[] joined = Base64.getDecoder().decode(encoded.substring(5));
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, joined, 0, 12));
    cipher.updateAAD(("clamav-auth-provider:" + provider).getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(joined, 12, joined.length - 12), StandardCharsets.UTF_8);
  }

  private static Map<String, String> snapshot(Connection conn) throws Exception {
    var queries =
        Map.of(
            "user", "SELECT username || ':' || password_hash FROM app_users WHERE id=5701",
            "token",
                "SELECT token_hash || ':' || scopes FROM api_credentials WHERE label='upgrade57-token'",
            "endpoint", "SELECT name || ':' || host FROM clamd_endpoints WHERE id=5702",
            "job",
                "SELECT id || ':' || status || ':' || verdict || ':' || target FROM scan_jobs WHERE id='upgrade57-job'",
            "audit", "SELECT action || ':' || details FROM audit_events WHERE id=5703",
            "ldap",
                "SELECT settings_json || ':' || encrypted_secret FROM auth_provider_settings WHERE provider='ldap'",
            "oidc",
                "SELECT settings_json || ':' || encrypted_secret FROM auth_provider_settings WHERE provider='oidc'");
    var result = new HashMap<String, String>();
    for (var e : queries.entrySet()) {
      try (Statement statement = conn.createStatement();
          ResultSet rs = statement.executeQuery(e.getValue())) {
        assertTrue(rs.next(), "Missing predecessor row: " + e.getKey());
        result.put(e.getKey(), rs.getString(1));
        assertFalse(rs.next(), "Unexpected duplicate predecessor row: " + e.getKey());
      }
    }
    return result;
  }

  @Test
  void previousReleaseV4FixtureUpgradesWithoutLosingUsersTokensJobsOrEncryptedSettings()
      throws Exception {
    String url =
        "jdbc:h2:mem:upgrade57-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String token = "clamv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    String tokenHash =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
    String ldapSecret = "fixture-only-ldap-secret", oidcSecret = "fixture-only-oidc-secret";
    Map<String, String> before;
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      for (String name :
          List.of(
              "V1__initial_schema.sql",
              "V3__external_identities.sql",
              "V4__authentication_settings.sql")) {
        ScriptUtils.executeSqlScript(conn, new ClassPathResource(PREFIX + "h2-schema/" + name));
      }
      try (Statement statement = conn.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO app_users(id,username,password_hash,enabled,created_at) "
                + "VALUES (1,'admin','$2a$12$fixture-do-not-login',TRUE,CURRENT_TIMESTAMP)");
        statement.executeUpdate("INSERT INTO app_user_roles(user_id,role) VALUES (1,'ROLE_ADMIN')");
      }
      String sql =
          new String(
              new ClassPathResource(PREFIX + "seed.sql.template").getInputStream().readAllBytes(),
              StandardCharsets.UTF_8);
      sql =
          sql.replace("__TOKEN_HASH__", tokenHash)
              .replace("__TOKEN_PREFIX__", token.substring(0, 12))
              .replace("__LDAP_CIPHER__", encrypt(key, "ldap", ldapSecret))
              .replace("__OIDC_CIPHER__", encrypt(key, "oidc", oidcSecret));
      ScriptUtils.executeSqlScript(
          conn, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
      before = snapshot(conn);
    }
    // Only the disposable fixture lacks Flyway history; the real predecessor image
    // creates its own history and must migrate without enabling baseline-on-migrate.
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration/h2")
            .javaMigrations(new V2__reconcile_legacy_columns())
            .baselineOnMigrate(true)
            .baselineVersion("4")
            .load();
    flyway.migrate();
    assertEquals(0, flyway.info().pending().length, "Pending migrations block upgrade");
    assertTrue(
        flyway.validateWithResult().validationSuccessful, "Flyway history validation failed");
    try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
      assertEquals(before, snapshot(conn), "V4 -> current migration changed historical data");
      try (PreparedStatement statement =
          conn.prepareStatement(
              "SELECT encrypted_secret FROM auth_provider_settings WHERE provider=?")) {
        for (String provider : List.of("ldap", "oidc")) {
          statement.setString(1, provider);
          try (ResultSet rs = statement.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(
                provider.equals("ldap") ? ldapSecret : oidcSecret,
                decrypt(key, provider, rs.getString(1)));
          }
        }
      }
      try (Statement st = conn.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = FALSE")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
    }
    flyway.migrate(); // restart is idempotent
    assertEquals(0, flyway.info().pending().length);
  }
}
