package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.SettingsService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.Set;

@Component
public class ApiV1Access {
    private final SettingsService settings;
    public ApiV1Access(SettingsService settings) { this.settings = settings; }
    public ApiTokenPrincipal principal(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof ApiTokenPrincipal t ? t : null;
    }
    public void require(Authentication auth, String scope) {
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Authentication required");
        ApiTokenPrincipal token = principal(auth);
        if (token != null) {
            if (!token.scopes().contains(scope)) throw new AccessDeniedException("Insufficient token scope");
            return;
        }
        boolean admin = hasRole(auth, "ADMIN");
        if (!admin && (scope.equals("audit:read") || (scope.startsWith("scan:") && !hasRole(auth,"OPERATOR")) ||
            (scope.equals("jobs:read") && !hasRole(auth,"OPERATOR")) ||
            (!scope.equals("audit:read") && !scope.startsWith("scan:") && !scope.equals("jobs:read") &&
             !hasRole(auth,"VIEWER") && !hasRole(auth,"OPERATOR"))))
            throw new AccessDeniedException("Insufficient role");
    }
    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }
    public void endpoint(Authentication auth, ClamdEndpoint ep) {
        if (ep == null) throw new IllegalArgumentException("Endpoint unavailable");
        ApiTokenPrincipal token = principal(auth);
        if (token != null && !token.endpointIds().isEmpty() && !token.endpointIds().contains(ep.getId()))
            throw new AccessDeniedException("Endpoint not permitted by token");
    }
    public void job(Authentication auth, ScanJob job) {
        ApiTokenPrincipal token = principal(auth);
        if (token != null && !token.endpointIds().isEmpty() &&
                (job == null || job.getEndpoint() == null || !token.endpointIds().contains(job.getEndpoint().getId())))
            throw new AccessDeniedException("Job endpoint not permitted by token");
        if (token != null && !new TokenJobScope(token).allows(job))
            throw new AccessDeniedException("Job not permitted by token restrictions");
    }
    public Path path(Authentication auth, String value) {
        ApiTokenPrincipal token = principal(auth);
        if (token == null) return null; // Browser/Basic callers retain the service's global-policy validation.
        if (token.pathRoots().isEmpty()) throw new AccessDeniedException("Token has no authorized path roots");
        try {
            Path target = info.trizub.clamav.webclient.util.PathPolicy.requireAllowed(Path.of(value), settings.allowedRoots());
            if (new TokenJobScope(token).allowsPath(target)) return target;
        } catch (IllegalArgumentException denied) {
            // Missing and forbidden targets must have the same outcome for a restricted token.
        }
        throw new AccessDeniedException(info.trizub.clamav.webclient.util.PathPolicy.DENIED_MESSAGE);
    }
}
