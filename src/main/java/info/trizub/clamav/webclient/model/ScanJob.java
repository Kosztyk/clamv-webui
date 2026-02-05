package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scan_jobs")
public class ScanJob {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJobStatus status = ScanJobStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ScanVerdict verdict;

    @Column(nullable = false, length = 2048)
    private String target;

    @Column(length = 1024)
    private String storedPath; // for uploaded file on disk (if any)

    @Column(length = 128)
    private String sha256;

    private Long sizeBytes;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id")
    private ClamdEndpoint endpoint;

    @Column(length = 64)
    private String submittedBy;

    private Instant submittedAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    // IMPORTANT (PostgreSQL): do NOT use @Lob for large Strings here.
    // Mapping as CLOB can route to OID Large Objects and fail with:
    //   "Large Objects may not be used in auto-commit mode"
    @Column(columnDefinition = "TEXT")
    private String foundVirusesJson; // Map<file, viruses> serialized

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 2048)
    private String quarantinePath;

    public ScanJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ScanJobType getType() { return type; }
    public void setType(ScanJobType type) { this.type = type; }
    public ScanJobStatus getStatus() { return status; }
    public void setStatus(ScanJobStatus status) { this.status = status; }
    public ScanVerdict getVerdict() { return verdict; }
    public void setVerdict(ScanVerdict verdict) { this.verdict = verdict; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getFoundVirusesJson() { return foundVirusesJson; }
    public void setFoundVirusesJson(String foundVirusesJson) { this.foundVirusesJson = foundVirusesJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getQuarantinePath() { return quarantinePath; }
    public void setQuarantinePath(String quarantinePath) { this.quarantinePath = quarantinePath; }
}
