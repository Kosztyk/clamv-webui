package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.model.ApiCredential;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import info.trizub.clamav.webclient.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/tokens")
public class ApiTokenAdminController {
    private final ApiCredentialService credentials;
    private final AuditService audit;
    public ApiTokenAdminController(ApiCredentialService credentials, AuditService audit) {
        this.credentials = credentials; this.audit = audit;
    }
    public record TokenView(Long id, String label, String prefix, String scopes, String endpointIds,
                            String pathRoots, Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {
        public static TokenView from(ApiCredential c) {
            return new TokenView(c.getId(), c.getLabel(), c.getPrefix(), c.getScopes(), c.getEndpointIds(),
                c.getPathRoots(), c.getCreatedAt(), c.getExpiresAt(), c.getRevokedAt(), c.getLastUsedAt());
        }
    }
    public record Issue(@NotBlank String label, @NotEmpty Set<String> scopes,
                        Set<Long> endpointIds, Set<String> pathRoots, Instant expiresAt) {}
    public record Created(TokenView token, String secret) {}
    @PostMapping
    public ResponseEntity<Created> issue(@Valid @RequestBody Issue request, Authentication auth, HttpServletRequest req) {
        if (!req.isSecure()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HTTPS required for token issuance");
        var created = credentials.issue(request.label(), request.scopes(), request.endpointIds(), request.pathRoots(), request.expiresAt());
        audit.record(auth, req, "API_TOKEN_CREATE", "id=" + created.credential().getId(), "SUCCESS", null);
        return ResponseEntity.status(201).header("Cache-Control", "no-store")
                .body(new Created(TokenView.from(created.credential()), created.secret()));
    }
    @GetMapping
    public List<TokenView> list() { return credentials.list().stream().map(TokenView::from).toList(); }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication auth, HttpServletRequest req) {
        if (!credentials.revoke(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found");
        audit.record(auth, req, "API_TOKEN_REVOKE", "id=" + id, "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }
}
