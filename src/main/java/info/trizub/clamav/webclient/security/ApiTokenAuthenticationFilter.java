package info.trizub.clamav.webclient.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {
    private final ApiCredentialService tokens;
    private final ObjectMapper mapper;
    public ApiTokenAuthenticationFilter(ApiCredentialService tokens, ObjectMapper mapper) { this.tokens = tokens; this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith(req.getContextPath() + "/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (!req.isSecure()) {
                error(res, 403, "tls_required", "Bearer tokens require HTTPS. Forwarded HTTPS support defaults to framework; verify the trusted proxy forwards the original HTTPS scheme and check any SERVER_FORWARD_HEADERS_STRATEGY override."); return;
            }
            if (auth.length() > 512) { error(res, 401, "invalid_token", "Invalid bearer token"); return; }
            var principal = tokens.authenticate(auth.substring(7).trim());
            if (principal.isEmpty()) { error(res, 401, "invalid_token", "Invalid or expired bearer token"); return; }
            ApiTokenPrincipal token = principal.get();
            var a = new UsernamePasswordAuthenticationToken(token, null, List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN")));
            var ctx = SecurityContextHolder.createEmptyContext(); ctx.setAuthentication(a);
            SecurityContextHolder.setContext(ctx);
        }
        try { chain.doFilter(req, res); } finally {
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) SecurityContextHolder.clearContext();
        }
    }
    private void error(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.setHeader("Cache-Control", "no-store");
        mapper.writeValue(res.getOutputStream(), Map.of("error", code, "message", message, "timestamp", Instant.now().toString()));
    }
}
