package info.trizub.clamav.webclient.identity;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.*;

/** Performs issuer/subject validation and deny-by-default claim -> role mapping. */
public class ExternalOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final ExternalIdentityMapper mapper;
    private final IdentityProperties.Oidc config;
    private final OidcUserService delegate = new OidcUserService();
    public ExternalOidcUserService(ExternalIdentityMapper mapper, IdentityProperties properties) {
        this.mapper=mapper; this.config=properties.getOidc();
    }
    @Override public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        if (!config.isEnabled()) throw denied("OIDC sign-in disabled");
        OidcUser user = delegate.loadUser(request); // Spring validates signature, nonce, audience and token claims.
        String issuer = user.getIssuer() == null ? "" : user.getIssuer().toString();
        if (config.getIssuer().isBlank() || !issuer.equals(config.getIssuer())) {
            throw denied("OIDC issuer is not explicitly trusted");
        }
        String subject = user.getSubject();
        Collection<String> groups = extractGroups(user.getClaims(), config.getGroupsClaim());
        var identity = mapper.map(ExternalIdentityMapper.Source.OIDC, issuer, subject, groups);
        if (identity.authorities().isEmpty()) throw denied("No application role assigned by OIDC groups");
        return new ExternalOidcUser(user, identity);
    }
    static Collection<String> extractGroups(Map<String,Object> claims, String path) {
        if (path == null || !path.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) return List.of();
        Object current = claims;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?,?> nested)) return List.of();
            current = nested.get(part);
        }
        if (current instanceof Collection<?> many) {
            return many.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        if (current instanceof String one && !one.isBlank()) return List.of(one);
        return List.of();
    }
    private static OAuth2AuthenticationException denied(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied", description, null));
    }
}
