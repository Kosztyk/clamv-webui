package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.security.ApiTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Best-effort audit facade. Audit persistence must not undo an operation that has already succeeded
 * (for example, a scan job submitted to the queue). All fields are bounded to the audit_events
 * schema before writing.
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);
  private final AuditEventWriter writer;

  public AuditService(AuditEventWriter writer) {
    this.writer = writer;
  }

  public void record(
      Authentication auth,
      HttpServletRequest req,
      String action,
      String details,
      String outcome,
      String jobId) {
    writeSafely(
        action,
        () -> {
          AuditEvent event =
              new AuditEvent(
                  limit(auth != null ? auth.getName() : null, 64),
                  limit(action, 64),
                  limit(details, 1024),
                  limit(outcome, 64),
                  limit(jobId, 64),
                  limit(req != null ? req.getRemoteAddr() : null, 64),
                  limit(req != null ? req.getHeader("User-Agent") : null, 255));
          event.setTransport(limit(transportFor(auth, req), 32));
          return event;
        });
  }

  public void recordScan(
      Authentication auth,
      HttpServletRequest req,
      String action,
      String outcome,
      Long endpointId,
      String jobId) {
    writeSafely(
        action,
        () -> {
          AuditEvent event =
              new AuditEvent(
                  limit(auth != null ? auth.getName() : null, 64),
                  limit(action, 64),
                  limit("endpointId=" + endpointId, 1024),
                  limit(outcome, 64),
                  limit(jobId, 64),
                  limit(req != null ? req.getRemoteAddr() : null, 64),
                  limit(req != null ? req.getHeader("User-Agent") : null, 255));
          event.setTransport(limit(transportFor(auth, req), 32));
          event.setEndpointId(endpointId);
          return event;
        });
  }

  /** Worker completion audit, shared by UI and API submissions. */
  public void recordResult(ScanJob job) {
    if (job == null) return;
    writeSafely(
        "SCAN_RESULT",
        () -> {
          AuditEvent event =
              new AuditEvent(
                  limit(job.getSubmittedBy(), 64),
                  "SCAN_RESULT",
                  limit("type=" + job.getType(), 1024),
                  limit(job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN", 64),
                  limit(job.getId(), 64),
                  null,
                  null);
          event.setEndpointId(job.getEndpoint() != null ? job.getEndpoint().getId() : null);
          event.setTransport(
              limit(
                  job.getSubmittedTransport() != null
                      ? job.getSubmittedTransport()
                      : "UNKNOWN_LEGACY",
                  32));
          return event;
        });
  }

  private void writeSafely(String action, Supplier<AuditEvent> eventFactory) {
    try {
      // This call goes through a separate Spring bean and REQUIRES_NEW
      // transaction, so even a flush/commit failure is caught here.
      writer.write(eventFactory.get());
    } catch (RuntimeException exception) {
      // Never log user-supplied details, User-Agent, or SQL parameters.
      String safeAction = limit(action, 64);
      if (safeAction != null) safeAction = safeAction.replaceAll("[\r\n\t]", "_");
      log.warn(
          "Audit write failed for action={} ({}); primary operation is unaffected",
          safeAction,
          exception.getClass().getSimpleName());
    }
  }

  /** Prevent oversized fields and avoid splitting a UTF-16 surrogate pair. */
  static String limit(String value, int maxChars) {
    if (value == null || value.length() <= maxChars) return value;
    int end = maxChars;
    if (end > 0
        && end < value.length()
        && Character.isHighSurrogate(value.charAt(end - 1))
        && Character.isLowSurrogate(value.charAt(end))) {
      end--;
    }
    return value.substring(0, end);
  }

  /** Classify transport safely even when request URI is unavailable in mocks. */
  private static String transportFor(Authentication auth, HttpServletRequest req) {
    if (auth != null && auth.getPrincipal() instanceof ApiTokenPrincipal) {
      return "API_TOKEN";
    }
    String uri = req != null ? req.getRequestURI() : null;
    return uri != null && uri.startsWith("/api/") ? "API_BASIC_OR_SESSION" : "BROWSER";
  }
}
