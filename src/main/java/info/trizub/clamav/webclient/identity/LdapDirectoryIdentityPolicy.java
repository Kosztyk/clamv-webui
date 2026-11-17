package info.trizub.clamav.webclient.identity;

/** Validation shared by Configuration Center and the legacy identity form. */
public final class LdapDirectoryIdentityPolicy {
  private LdapDirectoryIdentityPolicy() {}

  public static void validate(String id) {
    LdapDirectoryIdentity.validateExplicit(id);
  }
}
