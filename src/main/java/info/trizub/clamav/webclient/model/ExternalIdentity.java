package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

/** External profiles have no password and cannot become local accounts implicitly. */
@Entity
@Table(name="external_identities")
public class ExternalIdentity {
    @Id @Column(name="principal_name", length=64, nullable=false)
    private String principalName;
    @Column(name="source", length=8, nullable=false)
    private String source;
    @Column(name="issuer", length=512, nullable=false)
    private String issuer;
    @Column(name="subject", length=512, nullable=false)
    private String subject;
    @Column(name="first_seen_at", nullable=false)
    private Instant firstSeenAt;
    @Column(name="last_seen_at", nullable=false)
    private Instant lastSeenAt;
    protected ExternalIdentity() {}
    public ExternalIdentity(String principalName, String source, String issuer, String subject) {
        this.principalName=principalName; this.source=source; this.issuer=issuer; this.subject=subject;
        this.firstSeenAt=Instant.now(); this.lastSeenAt=this.firstSeenAt;
    }
    public String getPrincipalName() { return principalName; }
    public String getSource() { return source; }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void seenNow() { this.lastSeenAt=Instant.now(); }
}
