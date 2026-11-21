package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ProcessedFile;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commit a durable WATCH job and its submitted fingerprint in one per-file transaction. */
@Service
public class WatchFileSubmissionService {
  private final ScanJobService jobs;
  private final ProcessedFileRepository processed;

  public WatchFileSubmissionService(ScanJobService jobs, ProcessedFileRepository processed) {
    this.jobs = jobs;
    this.processed = processed;
  }

  @Transactional
  public ProcessedFile submit(
      Path file,
      ClamdEndpoint endpoint,
      ProcessedFile previous,
      long lastModified,
      long sizeBytes) {
    // ScanJobService repeats current endpoint/path/daemon policy checks and queues only after
    // commit.
    String jobId = jobs.createWatchFileJob(file, endpoint, "watcher").getId();
    if (endpoint == null || endpoint.getId() == null)
      throw new IllegalArgumentException(
          "Watcher endpoint must be persisted before creating a fingerprint");
    if (previous != null && !endpoint.getId().equals(previous.getEndpointId()))
      throw new IllegalArgumentException("Watcher fingerprint belongs to another daemon endpoint");
    ProcessedFile next =
        previous == null
            ? new ProcessedFile(
                file.toAbsolutePath().normalize().toString(),
                lastModified,
                sizeBytes,
                null,
                endpoint.getId())
            : previous.withSubmission(lastModified, sizeBytes, jobId);
    if (previous == null) next.assignSubmission(jobId);
    return processed.saveAndFlush(next);
  }
}
