package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ApiCredential;
import info.trizub.clamav.webclient.repo.ApiCredentialRepository;
import info.trizub.clamav.webclient.security.ApiTokenPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ApiCredentialService {
    public static final Set<String> ALLOWED_SCOPES = Set.of("scan:upload", "scan:path", "jobs:read", "endpoints:read", "health:read", "audit:read");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ApiCredentialRepository repo;
    private final EndpointService endpoints;
    private final SettingsService settings;
    public ApiCredentialService(ApiCredentialRepository repo, EndpointService endpoints, SettingsService settings) {
        this.repo = repo; this.endpoints = endpoints; this.settings = settings;
    }
    public record Created(ApiCredential credential, String secret) {}
    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private static Set<String> csvStrings(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }
    private static Set<Long> csvIds(String csv) {
        return csvStrings(csv).stream().map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
    }
    @Transactional
    public Created issue(String label, Set<String> scopes, Set<Long> endpointIds, Set<String> pathRoots, Instant expiresAt) {
        if (label == null || label.isBlank() || label.length() > 100) throw new IllegalArgumentException("Invalid token label");
        if (scopes == null || scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) throw new IllegalArgumentException("Invalid token scopes");
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) throw new IllegalArgumentException("Expiry must be in the future");
        Set<Long> eps = endpointIds == null ? Set.of() : Set.copyOf(endpointIds);
        for (Long id : eps) {
            if (id == null || endpoints.all().stream().noneMatch(e -> id.equals(e.getId()))) throw new IllegalArgumentException("Unknown endpoint restriction");
        }
        Set<String> roots = pathRoots == null ? Set.of() : new HashSet<>(pathRoots);
        Set<String> normalizedRoots = new HashSet<>();
        for (String root : roots) {
            if (root == null || root.isBlank() || root.contains(",") || root.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid token path root");
            try {
                var input = java.nio.file.Path.of(root);
                if (!input.isAbsolute()) throw new IllegalArgumentException("Token path roots must be absolute existing directories");
                // Root selection is administrator-only: resolve its chosen alias, then enforce the global roots.
                // Scan submissions use requireAllowed's lexical screening before any target lookup.
                var path = info.trizub.clamav.webclient.util.PathPolicy.requireCanonicalAllowed(
                    info.trizub.clamav.webclient.util.PathPolicy.canonical(input), settings.allowedRoots());
                String canonical = path.toString();
                if (!java.nio.file.Files.isDirectory(path) || canonical.contains(",") || !canonical.equals(canonical.trim())
                        || canonical.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid token path root; use an existing directory without commas or control characters");
                normalizedRoots.add(canonical);
            } catch (java.nio.file.InvalidPathException e) { throw new IllegalArgumentException("Invalid token path root"); }
        }
        if (String.join(",", normalizedRoots).length() > 2048) throw new IllegalArgumentException("Token path roots exceed the storage limit");
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String raw = "clamv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiCredential c = new ApiCredential(); c.setLabel(label.trim()); c.setTokenHash(sha256(raw));
        c.setPrefix(raw.substring(0, 12)); c.setScopes(String.join(",", new TreeSet<>(scopes)));
        c.setEndpointIds(eps.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        c.setPathRoots(String.join(",", new TreeSet<>(normalizedRoots)));
        c.setExpiresAt(expiresAt);
        return new Created(repo.save(c), raw);
    }
    @Transactional
    public Optional<ApiTokenPrincipal> authenticate(String secret) {
        if (secret == null || !secret.matches("clamv_[A-Za-z0-9_-]{43}")) return Optional.empty();
        String digest = sha256(secret);
        var match = repo.findByTokenHash(digest);
        if (match.isEmpty()) return Optional.empty();
        ApiCredential token = match.get();
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII), token.getTokenHash().getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        if (token.getRevokedAt() != null || token.getExpiresAt() != null && !token.getExpiresAt().isAfter(Instant.now())) return Optional.empty();
        // Bounded audit metadata: update the last-used timestamp at most every minute.
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(Instant.now().minusSeconds(60))) {
            token.setLastUsedAt(Instant.now()); repo.save(token);
        }
        return Optional.of(new ApiTokenPrincipal(token.getId(), token.getLabel(), csvStrings(token.getScopes()),
            csvIds(token.getEndpointIds()), csvStrings(token.getPathRoots())));
    }
    public List<ApiCredential> list() { return repo.findAll(); }
    @Transactional
    public boolean revoke(long id) {
        var match = repo.findById(id);
        if (match.isEmpty()) return false;
        if (match.get().getRevokedAt() == null) { match.get().setRevokedAt(Instant.now()); repo.save(match.get()); }
        return true;
    }
}
