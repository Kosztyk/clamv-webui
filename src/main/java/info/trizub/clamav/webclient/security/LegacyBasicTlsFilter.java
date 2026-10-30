package info.trizub.clamav.webclient.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Never accept username/password Basic authentication over plain HTTP. */
@Component
public class LegacyBasicTlsFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String authorization = req.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6) && !req.isSecure()) {
            res.setStatus(403); res.setContentType("application/json"); res.setHeader("Cache-Control", "no-store");
            res.getWriter().write("{\"error\":\"tls_required\",\"message\":\"HTTP Basic requires HTTPS\"}"); return;
        }
        chain.doFilter(req, res);
    }
}
