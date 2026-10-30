package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.security.ApiTokenPrincipal;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    public void record(Authentication auth, HttpServletRequest req, String action, String details, String outcome, String jobId) {
        String user = auth != null ? auth.getName() : null;
        String ip = req != null ? req.getRemoteAddr() : null;
        String ua = req != null ? req.getHeader("User-Agent") : null;
        AuditEvent event = new AuditEvent(user, action, details, outcome, jobId, ip, ua);
        event.setTransport(transportFor(auth, req));
        repo.save(event);
    }
    public void recordScan(Authentication auth, HttpServletRequest req, String action, String outcome, Long endpointId, String jobId) {
        AuditEvent event = new AuditEvent(auth != null ? auth.getName() : null, action,
                "endpointId=" + endpointId, outcome, jobId,
                req != null ? req.getRemoteAddr() : null, null);
        event.setTransport(transportFor(auth, req));
        event.setEndpointId(endpointId);
        repo.save(event);
    }
    /** Classify transport safely even when the request URI is unavailable (e.g. in mocks). */
    private static String transportFor(Authentication auth, HttpServletRequest req) {
        if (auth != null && auth.getPrincipal() instanceof ApiTokenPrincipal) {
            return "API_TOKEN";
        }
        String requestUri = req != null ? req.getRequestURI() : null;
        return requestUri != null && requestUri.startsWith("/api/")
                ? "API_BASIC_OR_SESSION" : "BROWSER";
    }

    /** Worker completion audit, shared by UI and API submissions. */
    public void recordResult(ScanJob job) {
        if (job == null) return;
        AuditEvent event = new AuditEvent(job.getSubmittedBy(), "SCAN_RESULT",
            "type=" + job.getType(), job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN",
            job.getId(), null, null);
        event.setEndpointId(job.getEndpoint() != null ? job.getEndpoint().getId() : null);
        event.setTransport(job.getSubmittedTransport() != null ? job.getSubmittedTransport() : "UNKNOWN_LEGACY");
        repo.save(event);
    }
}
