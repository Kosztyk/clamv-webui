package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
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
        repo.save(new AuditEvent(user, action, details, outcome, jobId, ip, ua));
    }
}
