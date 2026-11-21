package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ProcessedFile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedFileRepository extends JpaRepository<ProcessedFile, Long> {
  /** Endpoint-scoped indexed lookup; do not merge markers from overlapping roots. */
  List<ProcessedFile> findByPathInAndEndpointId(Collection<String> paths, Long endpointId);

  Optional<ProcessedFile> findByPathAndEndpointId(String path, Long endpointId);

  Optional<ProcessedFile> findByLastScanJobId(String jobId);

  /**
   * Only the marker still owned by this failed job may become eligible to retry. A newer scan of
   * the same pathname must never inherit an older job's failure.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "update ProcessedFile p set p.retryAfter = :retryAfter, "
          + "p.retryFailures = :failures where p.lastScanJobId = :jobId")
  int scheduleRetry(
      @Param("jobId") String jobId,
      @Param("retryAfter") Instant retryAfter,
      @Param("failures") int failures);

  List<ProcessedFile> findTop500ByIdGreaterThanOrderByIdAsc(long id);
}
