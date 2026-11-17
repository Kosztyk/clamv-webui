package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Journal record committed before an infected source file is moved. */
@Entity
@Table(name = "quarantine_artifacts")
public class QuarantineArtifact {
  @Id
  @Column(length = 32)
  private String id;

  @Column(name = "job_id", nullable = false, length = 64)
  private String jobId;

  @Column(name = "original_path", nullable = false, length = 4096)
  private String originalPath;

  @Column(name = "destination_path", nullable = false, length = 4096)
  private String destinationPath;

  @Column(name = "signatures_json", columnDefinition = "TEXT")
  private String signaturesJson;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "source_sha256", length = 64)
  private String sourceSha256;

  @Column(name = "source_size_bytes")
  private Long sourceSizeBytes;

  @Column(name = "failure_code", length = 32)
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected QuarantineArtifact() {}

  public QuarantineArtifact(
      String id, String jobId, String originalPath, String destinationPath, String signaturesJson) {
    this.id = id;
    this.jobId = jobId;
    this.originalPath = originalPath;
    this.destinationPath = destinationPath;
    this.signaturesJson = signaturesJson;
    this.status = "PREPARED";
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  /** Old V9/V10 rows have no evidence; never replay their source move by pathname alone. */
  public void setSourceEvidence(long size, String sha256) {
    if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid source evidence");
    this.sourceSizeBytes = size;
    this.sourceSha256 = sha256;
  }

  public Long getSourceSizeBytes() {
    return sourceSizeBytes;
  }

  public String getSourceSha256() {
    return sourceSha256;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void fail(String code) {
    if (code == null || !code.matches("[A-Z_]{1,32}"))
      throw new IllegalArgumentException("Invalid quarantine failure code");
    this.failureCode = code;
    setStatus("FAILED");
  }

  public String getId() {
    return id;
  }

  public String getJobId() {
    return jobId;
  }

  public String getOriginalPath() {
    return originalPath;
  }

  public String getDestinationPath() {
    return destinationPath;
  }

  public String getSignaturesJson() {
    return signaturesJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
    updatedAt = Instant.now();
  }
}
