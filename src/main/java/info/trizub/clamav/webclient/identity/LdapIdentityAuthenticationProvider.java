package info.trizub.clamav.webclient.identity;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import java.util.List;

/** Only attached to the separate /login/ldap endpoint; never falls back to local auth. */
public class LdapIdentityAuthenticationProvider implements AuthenticationProvider {
    private final LdapAuthenticationProvider delegate;
    private final ExternalIdentityMapper mapper;
    private final String directoryId;
    public LdapIdentityAuthenticationProvider(LdapAuthenticationProvider delegate, ExternalIdentityMapper mapper, String directoryId) {
        this.delegate=delegate; this.mapper=mapper; this.directoryId=directoryId;
    }
    @Override public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (authentication.getCredentials() == null || authentication.getCredentials().toString().isBlank())
            throw new BadCredentialsException("Invalid credentials");
        Authentication ldap = delegate.authenticate(authentication);
        if (!(ldap.getPrincipal() instanceof LdapUserDetails user) || user.getDn().isBlank())
            throw new BadCredentialsException("LDAP immutable subject unavailable");
        var names = ldap.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("LDAP_GROUP:")).map(a -> a.substring("LDAP_GROUP:".length())).toList();
        var identity = mapper.map(ExternalIdentityMapper.Source.LDAP, directoryId, user.getDn(), names);
        if (identity.authorities().isEmpty()) throw new BadCredentialsException("LDAP user has no mapped application role");
        // Credentials are erased; the principal never impersonates a local username.
        return UsernamePasswordAuthenticationToken.authenticated(new ExternalLdapPrincipal(identity, user.getUsername()), null, identity.authorities());
    }
    @Override public boolean supports(Class<?> authentication) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication); }
}
