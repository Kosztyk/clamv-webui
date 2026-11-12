package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.identity.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Check before Basic authentication and account for actual authentication outcomes, not HTTP response codes. */
@Component
public class BasicLoginLockoutFilter extends OncePerRequestFilter {
    private final LoginAttemptService attempts;
    private final BasicAuthenticationConverter converter = new BasicAuthenticationConverter();

    public BasicLoginLockoutFilter(LoginAttemptService attempts) { this.attempts = attempts; }

    /** Marks Basic attempts so form/LDAP failure handlers are not counted a second time. */
    public static final class Details extends WebAuthenticationDetails {
        private static final long serialVersionUID = 1L;
        public Details(HttpServletRequest request) { super(request); }
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String username = null;
        try {
            var authentication = converter.convert(request);
            if (authentication != null) username = authentication.getName();
        } catch (AuthenticationException malformed) {
            // Leave malformed credentials to Spring's Basic filter; no trustworthy username was decoded.
        }
        if (username != null && attempts.locked("local", username, request.getRemoteAddr())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", Long.toString(Math.max(1, attempts.retryAfterSeconds("local", username, request.getRemoteAddr()))));
            response.getWriter().write("{\"error\":\"login_locked\",\"message\":\"Too many failed password attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @EventListener
    public void authenticationFailed(AbstractAuthenticationFailureEvent event) {
        var authentication = event.getAuthentication();
        if (authentication.getDetails() instanceof Details details)
            attempts.fail("local", authentication.getName(), details.getRemoteAddress());
    }

    @EventListener
    public void authenticationSucceeded(AuthenticationSuccessEvent event) {
        var authentication = event.getAuthentication();
        if (authentication.getDetails() instanceof Details details)
            attempts.success("local", authentication.getName(), details.getRemoteAddress());
    }
}
