package info.trizub.clamav.webclient.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SessionValidationFilter extends OncePerRequestFilter {
    private final SessionAccessService sessions;
    public SessionValidationFilter(SessionAccessService sessions) { this.sessions = sessions; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            // Basic CLI requests may carry an anonymous CSRF session. Always verify their supplied
            // credentials, rather than accepting a cached same-name browser authentication.
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            chain.doFilter(request, response); return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        // Basic and Bearer authenticate each request; do not turn machine requests into browser sessions.
        if (session != null && sessions.managed(authentication)) {
            try {
                if (!sessions.valid(authentication, session)) {
                    SessionRejectionHandler.reject(request, response); return;
                }
            } catch (DataAccessException unavailable) {
                // A database outage must not authorize a cached role snapshot, or destroy recoverable sessions.
                response.setStatus(503); response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"error\":\"authentication_unavailable\",\"message\":\"Account validation is unavailable\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
