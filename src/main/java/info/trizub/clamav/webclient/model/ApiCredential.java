package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Only a SHA-256 digest is persisted; raw bearer credentials are never stored. */
@Entity
@Table(name = "api_credentials", indexes = @Index(name = "idx_api_credentials_hash", columnList = "token_hash", unique = true))
public class ApiCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String label;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false, length = 16)
    private String prefix;
    @Column(nullable = false, length = 512)
    private String scopes;
    @Column(length = 1024)
    private String endpointIds;
    @Column(length = 2048)
    private String pathRoots;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant lastUsedAt;
    public Long getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String v) { label = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { tokenHash = v; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String v) { prefix = v; }
    public String getScopes() { return scopes; }
    public void setScopes(String v) { scopes = v; }
    public String getEndpointIds() { return endpointIds; }
    public void setEndpointIds(String v) { endpointIds = v; }
    public String getPathRoots() { return pathRoots; }
    public void setPathRoots(String v) { pathRoots = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { revokedAt = v; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant v) { lastUsedAt = v; }
}
