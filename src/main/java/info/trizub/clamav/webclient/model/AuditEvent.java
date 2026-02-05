package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant at = Instant.now();

    @Column(length = 64)
    private String username;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 1024)
    private String details;

    @Column(length = 64)
    private String outcome; // SUCCESS/FAILED

    @Column(length = 64)
    private String jobId;

    @Column(length = 64)
    private String ip;

    @Column(length = 255)
    private String userAgent;

    public AuditEvent() {}

    public AuditEvent(String username, String action, String details, String outcome, String jobId, String ip, String userAgent) {
        this.username = username;
        this.action = action;
        this.details = details;
        this.outcome = outcome;
        this.jobId = jobId;
        this.ip = ip;
        this.userAgent = userAgent;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public String getOutcome() { return outcome; }
    public String getJobId() { return jobId; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
}
