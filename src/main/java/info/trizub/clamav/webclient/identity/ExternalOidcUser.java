package info.trizub.clamav.webclient.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.util.Collection;
import java.util.Map;

/** The session principal reports canonical source-qualified name; OIDC claims remain available. */
public record ExternalOidcUser(OidcUser delegate, ExternalIdentityMapper.MappedIdentity identity) implements OidcUser {
    @Override public String getName() { return identity.principalName(); }
    @Override public Map<String,Object> getAttributes() { return delegate.getAttributes(); }
    @Override public Map<String,Object> getClaims() { return delegate.getClaims(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return identity.authorities(); }
}
