package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.identity.AuthenticationSettingsService;
import info.trizub.clamav.webclient.identity.ExternalLdapPrincipal;
import info.trizub.clamav.webclient.identity.ExternalOidcUser;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Rechecks local privileges on every authenticated request and bounds external claim snapshots. */
@Service
public class SessionAccessService {
    public static final String AUTHENTICATED_AT = SessionAccessService.class.getName() + ".authenticatedAt";
    public static final String PROVIDER_REVISION = SessionAccessService.class.getName() + ".providerRevision";
    private final AppUserRepository users;
    private final SessionRegistry registry;
    private final AuthenticationSettingsService settings;
    private final long maxAgeMillis;

    public SessionAccessService(AppUserRepository users, SessionRegistry registry, AuthenticationSettingsService settings,
            @Value("${clamav.security.session.max-age:1h}") Duration maxAge) {
        if (maxAge.compareTo(Duration.ofSeconds(1)) < 0 || maxAge.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("clamav.security.session.max-age must be between 1s and 24h");
        this.users = users; this.registry = registry; this.settings = settings;
        maxAgeMillis = maxAge.toMillis();
    }

    public void authenticated(HttpSession session) {
        session.setAttribute(AUTHENTICATED_AT, System.currentTimeMillis());
        session.setAttribute(PROVIDER_REVISION, settings.revision());
    }

    public boolean managed(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        Object principal = authentication.getPrincipal();
        return principal instanceof LocalUserDetails || principal instanceof ExternalLdapPrincipal
            || principal instanceof ExternalOidcUser;
    }

    public boolean valid(Authentication authentication, HttpSession session) {
        if (!managed(authentication) || session == null) return false;
        var info = registry.getSessionInformation(session.getId());
        if (info == null || info.isExpired()) return false;
        Object started = session.getAttribute(AUTHENTICATED_AT);
        long now = System.currentTimeMillis();
        if (!(started instanceof Long at) || at > now || now - at >= maxAgeMillis) return false;
        if (authentication.getPrincipal() instanceof LocalUserDetails local) {
            return local.matches(users.findById(local.accountId()).orElse(null));
        }
        if (!Long.valueOf(settings.revision()).equals(session.getAttribute(PROVIDER_REVISION))) return false;
        return authentication.getPrincipal() instanceof ExternalLdapPrincipal
            ? settings.properties().getLdap().isEnabled() : settings.properties().getOidc().isEnabled();
    }
}
