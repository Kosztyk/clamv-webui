package info.trizub.clamav.webclient.identity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stores the LDAP namespace once in the application database (shared by all nodes). On upgrading
 * existing deployments, the initial value is EXACTLY the historical URL + "/" + base-DN issuer used
 * by ExternalIdentityMapper. That preserves all existing canonical principal hashes, external
 * identities and audit attribution.
 */
final class LdapDirectoryIdentity {
  private static final String KEY = "primary";

  private LdapDirectoryIdentity() {}

  static void validateExplicit(String identifier) {
    if (identifier == null || identifier.isBlank()) return;
    if (!identifier.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
      throw new IllegalArgumentException(
          "LDAP directory ID must contain 1-128 letters, digits, dot, underscore, colon or hyphen");
  }

  static String pinned(JdbcTemplate jdbc) {
    return jdbc.query(
        "SELECT directory_id FROM ldap_directory_identity WHERE name=?",
        rs -> rs.next() ? rs.getString(1) : null,
        KEY);
  }

  static String resolve(JdbcTemplate jdbc, String configured, String url, String baseDn) {
    String explicit = configured == null ? "" : configured.trim();
    validateExplicit(explicit);
    if (url == null || url.isBlank() || baseDn == null || baseDn.isBlank())
      throw new IllegalArgumentException(
          "LDAP URL and base DN are required to establish directory identity");
    String existing = pinned(jdbc);
    if (existing != null) {
      if (!explicit.isEmpty() && !explicit.equals(existing))
        throw new IllegalArgumentException(
            "LDAP directory ID is already pinned. Keep the existing ID; changing it would create new principals. An identity migration is required to change it.");
      return existing;
    }
    // Upgrade compatibility: this must match the original hashing input byte-for-byte.
    String initial = explicit.isEmpty() ? url + "/" + baseDn : explicit;
    if (initial.length() > 2048)
      throw new IllegalArgumentException("LDAP directory identity is too long");
    try {
      jdbc.update(
          "INSERT INTO ldap_directory_identity (name,directory_id) VALUES (?,?)", KEY, initial);
      return initial;
    } catch (DuplicateKeyException concurrentStartup) {
      // Another node may have selected the identity concurrently. Check its value.
      String pinned = pinned(jdbc);
      if (pinned == null || (!explicit.isEmpty() && !explicit.equals(pinned)))
        throw new IllegalStateException(
            "LDAP directory identity changed during startup", concurrentStartup);
      return pinned;
    }
  }
}
