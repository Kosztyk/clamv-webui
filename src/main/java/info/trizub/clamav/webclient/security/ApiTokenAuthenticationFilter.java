package info.trizub.clamav.webclient.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {
  private static final String BEARER_PREFIX = "Bearer ";

  private final ApiCredentialService tokens;
  private final ObjectMapper mapper;

  public ApiTokenAuthenticationFilter(ApiCredentialService tokens, ObjectMapper mapper) {
    this.tokens = tokens;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String authorization = req.getHeader(HttpHeaders.AUTHORIZATION);
    return !req.getRequestURI().startsWith(req.getContextPath() + "/api/v1/")
        || authorization == null
        || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!req.isSecure()) {
      error(
          res,
          403,
          "tls_required",
          "Bearer tokens require HTTPS. For TLS termination, explicitly configure native forwarding with an allowlist of trusted proxy addresses; see docs/REVERSE_PROXY_TLS.md.");
      return;
    }
    String authorization = req.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization.length() > 512) {
      error(res, 401, "invalid_token", "Invalid bearer token");
      return;
    }
    var principal = tokens.authenticate(authorization.substring(BEARER_PREFIX.length()).trim());
    if (principal.isEmpty()) {
      error(res, 401, "invalid_token", "Invalid or expired bearer token");
      return;
    }
    var authentication =
        new UsernamePasswordAuthenticationToken(
            principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN")));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(req, res);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void error(HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setHeader("Cache-Control", "no-store");
    mapper.writeValue(
        res.getOutputStream(),
        Map.of("error", code, "message", message, "timestamp", Instant.now().toString()));
  }
}
