package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.DaemonScanPolicy;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reject known-oversized multipart requests BEFORE Spring's multipart resolver creates a temporary
 * file. Runs inside Spring Security BEFORE CsrfFilter, because CSRF form-parameter lookup itself
 * can trigger multipart parsing.
 *
 * <p>There is no safe way to know how many multipart files are present just from Content-Length:
 * browsers may send many files in a single request. The UI supplies a file-count hint in the QUERY
 * STRING, before the multipart body is parsed. Missing/untrusted hints never remove the existing
 * per-file daemon policy checks in ScanJobService.
 */
public final class UploadAdmissionFilter extends OncePerRequestFilter {
  private final long maxRequestBytes;
  private final EndpointService endpoints;
  private final DaemonScanPolicy policy;

  public UploadAdmissionFilter(
      long maxRequestBytes, EndpointService endpoints, DaemonScanPolicy policy) {
    if (maxRequestBytes <= 0)
      throw new IllegalArgumentException("Multipart request limit must be positive");
    this.maxRequestBytes = maxRequestBytes;
    this.endpoints = endpoints;
    this.policy = policy;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) return true;
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.equals("/scan/upload")
        && !path.equals("/api/scan/upload")
        && !path.equals("/api/v1/scans/uploads")
        && !path.equals("/rest-monitor/upload");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String type = request.getContentType();
    if (type == null
        || !type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
      chain.doFilter(request, response);
      return;
    }
    long length = request.getContentLengthLong();
    if (length >= 0) {
      long ceiling = maxRequestBytes;
      Integer count = numericParameter(request.getQueryString(), "uploadFileCount", 10000);
      if (count != null && count > 0 && authenticatedScanOperator()) {
        try {
          Long id = endpointId(request.getQueryString());
          ClamdEndpoint endpoint = id == null ? endpoints.defaultEndpoint() : endpoints.get(id);
          if (endpoint != null) {
            ceiling =
                UploadRequestLimits.ceiling(
                    maxRequestBytes, policy.uploadMaxBytes(endpoint), count);
          }
        } catch (RuntimeException ignored) {
          // The handler validates endpoint IDs and missing daemon policy.
          // Do not expose internal endpoint availability in a preflight response.
        }
      }
      if (length > ceiling) {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/") || path.equals("/rest-monitor/upload")) {
          response.setContentType("application/json");
          response
              .getWriter()
              .write(
                  "{\"error\":\"upload_too_large\",\"message\":\"Upload exceeds the configured request limit\"}");
        } else {
          response.setContentType("text/plain;charset=UTF-8");
          response
              .getWriter()
              .write("Upload too large. Return to the Scan page and select smaller files.");
        }
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean authenticatedScanOperator() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) return false;
    return authentication.getAuthorities().stream()
        .anyMatch(
            authority ->
                "ROLE_OPERATOR".equals(authority.getAuthority())
                    || "ROLE_ADMIN".equals(authority.getAuthority()));
  }

  private static Long endpointId(String query) {
    Integer id = numericParameter(query, "uploadPreflightEndpointId", Integer.MAX_VALUE);
    return id == null ? null : id.longValue();
  }

  private static Integer numericParameter(String query, String key, int max) {
    if (query == null) return null;
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals <= 0 || !pair.substring(0, equals).equals(key)) continue;
      String raw = pair.substring(equals + 1);
      if (raw.isEmpty() || raw.length() > 10 || !raw.matches("[0-9]+")) return null;
      try {
        int number = Integer.parseInt(raw);
        return number > 0 && number <= max ? number : null;
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}
