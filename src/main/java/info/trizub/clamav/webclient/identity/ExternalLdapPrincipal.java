package info.trizub.clamav.webclient.identity;
import java.security.Principal;
/** Source-qualified LDAP principal with immutable directory subject metadata. */
public record ExternalLdapPrincipal(ExternalIdentityMapper.MappedIdentity identity, String displayName) implements Principal {
    @Override public String getName() { return identity.principalName(); }
}
