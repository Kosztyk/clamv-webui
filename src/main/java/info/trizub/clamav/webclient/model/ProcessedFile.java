package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_files", indexes = {
        @Index(name = "idx_processed_path", columnList = "path", unique = true)
})
public class ProcessedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4096)
    private String path;

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

    public Long getId() { return id; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Instant getProcessedAt() { return processedAt; }
}
