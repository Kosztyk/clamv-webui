package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.ExternalIdentityRepository;
import info.trizub.clamav.webclient.security.SessionRevocationService;
import info.trizub.clamav.webclient.service.AuditService;
import info.trizub.clamav.webclient.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
public class UserSessionController {
    private final UserService users;
    private final AppUserRepository accounts;
    private final ExternalIdentityRepository external;
    private final SessionRevocationService sessions;
    private final AuditService audit;
    public UserSessionController(UserService users, AppUserRepository accounts, ExternalIdentityRepository external,
            SessionRevocationService sessions, AuditService audit) {
        this.users = users; this.accounts = accounts; this.external = external;
        this.sessions = sessions; this.audit = audit;
    }

    @PostMapping("/admin/users/{id}/access")
    public String update(@PathVariable Long id, @RequestParam String role, @RequestParam boolean enabled,
            Authentication authentication, HttpServletRequest request, RedirectAttributes flash) {
        requireLocal(id);
        try {
            Set<String> roles = switch (role) {
                case "ADMIN" -> Set.of("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
                case "OPERATOR" -> Set.of("ROLE_OPERATOR", "ROLE_VIEWER");
                case "VIEWER" -> Set.of("ROLE_VIEWER");
                default -> throw new IllegalArgumentException("Choose VIEWER, OPERATOR or ADMIN");
            };
            users.updateAccess(id, roles, enabled);
            audit.record(authentication, request, "USER_ACCESS_UPDATE", "id=" + id, "SUCCESS", null);
        } catch (IllegalArgumentException error) {
            flash.addFlashAttribute("errorMsg", error.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/sessions/revoke")
    public String revokeLocal(@PathVariable Long id, Authentication authentication, HttpServletRequest request) {
        requireLocal(id);
        sessions.revokeLocal(id);
        audit.record(authentication, request, "USER_SESSIONS_REVOKE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/external/{principal}/sessions/revoke")
    public String revokeExternal(@PathVariable String principal, Authentication authentication, HttpServletRequest request) {
        if (!external.existsById(principal)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        sessions.revokeExternal(principal);
        audit.record(authentication, request, "EXTERNAL_SESSIONS_REVOKE", principal, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    private void requireLocal(Long id) {
        if (!accounts.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
