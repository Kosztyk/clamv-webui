package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.service.ApiCredentialService;
import info.trizub.clamav.webclient.service.AuditService;
import info.trizub.clamav.webclient.api.v1.ApiTokenAdminController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Admin-only browser interface for issuing and revoking scoped API tokens. */
@Controller
@RequestMapping("/admin/api-tokens")
public class ApiTokenAdminPageController {
    private final ApiCredentialService credentials;
    private final AuditService audit;
    public ApiTokenAdminPageController(ApiCredentialService credentials, AuditService audit) {
        this.credentials = credentials; this.audit = audit;
    }
    @GetMapping
    public String page(Model model, Authentication auth, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("username", auth.getName());
        model.addAttribute("tokens", credentials.list().stream().map(ApiTokenAdminController.TokenView::from).toList());
        model.addAttribute("scopes", new TreeSet<>(ApiCredentialService.ALLOWED_SCOPES));
        return "pages/api-tokens";
    }
    @PostMapping("/create")
    public String create(@RequestParam String label, @RequestParam(name = "scopes", required = false) Set<String> scopes,
                         @RequestParam(name = "endpointIds", required = false, defaultValue = "") String endpointIds,
                         @RequestParam(name = "pathRoots", required = false, defaultValue = "") String pathRoots,
                         @RequestParam(name = "expiresHours", defaultValue = "720") int expiresHours,
                         Authentication auth, HttpServletRequest request, RedirectAttributes flash) {
        try {
            if (!request.isSecure()) {
                flash.addFlashAttribute("errorMsg", "API tokens can only be issued over HTTPS. Configure a trusted TLS reverse proxy first.");
                return "redirect:/admin/api-tokens";
            }
            if (expiresHours < 1 || expiresHours > 8760) throw new IllegalArgumentException("Expiry must be 1–8760 hours");
            Set<Long> ids = new HashSet<>();
            if (!endpointIds.isBlank()) for (String part : endpointIds.split(",")) ids.add(Long.valueOf(part.trim()));
            Set<String> roots = pathRoots.isBlank() ? Set.of() : Arrays.stream(pathRoots.split(","))
                .map(String::trim).filter(x -> !x.isBlank()).collect(Collectors.toSet());
            var created = credentials.issue(label, scopes, ids, roots, Instant.now().plusSeconds(3600L * expiresHours));
            audit.record(auth, request, "API_TOKEN_CREATE", "id=" + created.credential().getId(), "SUCCESS", null);
            flash.addFlashAttribute("apiTokenSecret", created.secret());
            flash.addFlashAttribute("successMsg", "Token created. Copy it now; it will not be shown again.");
        } catch (RuntimeException e) {
            flash.addFlashAttribute("errorMsg", "Token not created: invalid label, scope or endpoint/path restriction.");
        }
        return "redirect:/admin/api-tokens";
    }
    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable long id, Authentication auth, HttpServletRequest request, RedirectAttributes flash) {
        if (credentials.revoke(id)) {
            audit.record(auth, request, "API_TOKEN_REVOKE", "id=" + id, "SUCCESS", null);
            flash.addFlashAttribute("successMsg", "Token revoked.");
        } else flash.addFlashAttribute("errorMsg", "Unknown token.");
        return "redirect:/admin/api-tokens";
    }
}
