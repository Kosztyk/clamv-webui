package info.trizub.clamav.webclient.identity;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/** A login-time provider; LDAP never blocks application startup or emergency login. */
final class DynamicLdapAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticationSettingsService settings;
    private final ExternalIdentityMapper mapper;
    private volatile long configuredRevision=-1;
    private volatile LdapIdentityAuthenticationProvider cached;
    DynamicLdapAuthenticationProvider(AuthenticationSettingsService settings,ExternalIdentityMapper mapper){
        this.settings=settings;this.mapper=mapper;
    }
    @Override public Authentication authenticate(Authentication auth) throws AuthenticationException {
        if(!settings.properties().getLdap().isEnabled()) throw new BadCredentialsException("LDAP sign-in unavailable");
        try {
            LdapIdentityAuthenticationProvider provider=current();
            return provider.authenticate(auth);
        } catch(AuthenticationException e){throw e;}
        catch(Exception e){throw new BadCredentialsException("LDAP authentication unavailable");}
    }
    private synchronized LdapIdentityAuthenticationProvider current() throws Exception {
        long revision=settings.revision();
        if(cached==null || configuredRevision!=revision) {
            var ldap=settings.properties().getLdap();
            var constructed=IdentityLdapConfiguration.createContext(ldap,settings.effectiveSecret("ldap"));
            LdapIdentityAuthenticationProvider candidate=new LdapIdentityAuthenticationProvider(
                constructed.provider(),mapper,ldap.getUrl()+"/"+ldap.getBaseDn());
            cached=candidate;configuredRevision=revision;
            // Contexts are released with the replaced provider; LDAP operations close their DirContext.
        }
        return cached;
    }
    @Override public boolean supports(Class<?> type){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);}
}
