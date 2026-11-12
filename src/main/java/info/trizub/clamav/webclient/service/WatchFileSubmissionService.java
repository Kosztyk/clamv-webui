package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ProcessedFile;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;

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
    public ProcessedFile submit(Path file, ClamdEndpoint endpoint, ProcessedFile previous,
                                long lastModified, long sizeBytes) {
        // ScanJobService repeats current endpoint/path/daemon policy checks and queues only after commit.
        jobs.createWatchFileJob(file, endpoint, "watcher");
        ProcessedFile next = previous == null
            ? new ProcessedFile(file.toAbsolutePath().normalize().toString(), lastModified, sizeBytes, null)
            : previous.withFingerprint(lastModified, sizeBytes);
        return processed.saveAndFlush(next);
    }
}
