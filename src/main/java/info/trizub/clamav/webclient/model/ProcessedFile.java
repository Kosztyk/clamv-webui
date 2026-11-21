package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_files")
public class ProcessedFile {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 4096)
  private String path;

  /** Watcher fingerprint belongs to one daemon endpoint, not a global pathname. */
  @Column(name = "endpoint_id")
  private Long endpointId;

  /**
   * The WATCH job currently representing this fingerprint. A QUEUED/RUNNING job suppresses
   * duplicate submissions; a failed job schedules a retry.
   */
  @Column(name = "last_scan_job_id", length = 64)
  private String lastScanJobId;

  @Column(name = "retry_failures", nullable = false)
  private int retryFailures;

  @Column(name = "retry_after")
  private Instant retryAfter;

  private long lastModified;
  private long sizeBytes;

  @Column(length = 128)
  private String sha256;

  private Instant processedAt = Instant.now();

  public ProcessedFile() {}

  public ProcessedFile(String path, long lastModified, long sizeBytes, String sha256) {
    this.path = path;
    this.lastModified = lastModified;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
  }

  public ProcessedFile(
      String path, long lastModified, long sizeBytes, String sha256, Long endpointId) {
    this(path, lastModified, sizeBytes, sha256);
    this.endpointId = endpointId;
  }

  public Long getEndpointId() {
    return endpointId;
  }

  public void setEndpointId(Long endpointId) {
    this.endpointId = endpointId;
  }

  public String getLastScanJobId() {
    return lastScanJobId;
  }

  public Instant getRetryAfter() {
    return retryAfter;
  }

  public int getRetryFailures() {
    return retryFailures;
  }

  public Long getId() {
    return id;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public long getLastModified() {
    return lastModified;
  }

  public void setLastModified(long lastModified) {
    this.lastModified = lastModified;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  /** Keep the last committed fingerprint unchanged until the replacement commits. */
  public ProcessedFile withFingerprint(long lastModified, long sizeBytes) {
    ProcessedFile next = new ProcessedFile(path, lastModified, sizeBytes, null, endpointId);
    next.id = id;
    return next;
  }

  /**
   * Construct an updated marker without mutating the managed previous row before the transaction
   * that creates the new WATCH job commits.
   */
  public ProcessedFile withSubmission(long modified, long size, String jobId) {
    ProcessedFile next = withFingerprint(modified, size);
    next.lastScanJobId = jobId;
    next.retryAfter = null;
    // Preserve backoff history only while retrying identical bytes/metadata.
    next.retryFailures = lastModified == modified && sizeBytes == size ? retryFailures : 0;
    return next;
  }

  public void assignSubmission(String jobId) {
    if (jobId == null || jobId.isBlank())
      throw new IllegalArgumentException("Missing WATCH job ID");
    lastScanJobId = jobId;
    retryAfter = null;
  }
}
