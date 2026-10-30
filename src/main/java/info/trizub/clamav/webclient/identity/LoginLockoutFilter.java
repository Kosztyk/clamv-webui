package info.trizub.clamav.webclient.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Applies only to form-password login; machine-to-machine API tokens are unaffected. */
@Component
public class LoginLockoutFilter extends OncePerRequestFilter {
    private final LoginAttemptService attempts;
    public LoginLockoutFilter(LoginAttemptService attempts) { this.attempts=attempts; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if ("POST".equals(req.getMethod()) && ("/login".equals(path) || "/login/ldap".equals(path))) {
            String mechanism = path.equals("/login") ? "local" : "ldap";
            if (attempts.locked(mechanism, req.getParameter("username"), req.getRemoteAddr())) {
                res.sendRedirect(req.getContextPath() + "/login?locked");
                return;
            }
        }
        chain.doFilter(req,res);
    }
}
