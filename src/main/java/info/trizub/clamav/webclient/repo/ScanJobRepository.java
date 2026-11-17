package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ScanJobRepository
    extends JpaRepository<ScanJob, String>, JpaSpecificationExecutor<ScanJob> {
  // JPQL constructor projection: neither TEXT column, storedPath, nor a full
  // ClamdEndpoint entity is selected. The count query also avoids the join.
  @Query(
      value =
          "select new info.trizub.clamav.webclient.model.ScanJobSummary("
              + "j.id, j.type, j.status, j.verdict, j.target, j.submittedBy, j.submittedTransport, j.submittedAt, "
              + "e.id, e.name, j.sizeBytes, j.startedAt, j.finishedAt) "
              + "from ScanJob j left join j.endpoint e",
      countQuery = "select count(j) from ScanJob j")
  Page<ScanJobSummary> findSummaries(Pageable pageable);

  // No count query and no TEXT columns for the frequently polled preview.
  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobSummary("
          + "j.id, j.type, j.status, j.verdict, j.target, j.submittedBy, j.submittedTransport, j.submittedAt, "
          + "e.id, e.name, j.sizeBytes, j.startedAt, j.finishedAt) "
          + "from ScanJob j left join j.endpoint e order by j.submittedAt desc, j.id desc")
  List<ScanJobSummary> findRecentSummaries(Pageable pageable);

  // API v1 list filtering uses scalar constructor queries: no TEXT diagnostics,
  // quarantine paths, stored paths, or eager endpoint entity loads.
  @Query(
      value =
          "select new info.trizub.clamav.webclient.model.ScanJobSummary("
              + "j.id, j.type, j.status, j.verdict, j.target, j.submittedBy, j.submittedTransport, j.submittedAt, "
              + "e.id, e.name, j.sizeBytes, j.startedAt, j.finishedAt) "
              + "from ScanJob j left join j.endpoint e "
              + "where (:endpointId is null or e.id = :endpointId) "
              + "and (:status is null or j.status = :status) "
              + "and (:verdict is null or j.verdict = :verdict) "
              + "and (:filterEndpoints = false or e.id in :allowedEndpointIds)",
      countQuery =
          "select count(j) from ScanJob j left join j.endpoint e "
              + "where (:endpointId is null or e.id = :endpointId) "
              + "and (:status is null or j.status = :status) "
              + "and (:verdict is null or j.verdict = :verdict) "
              + "and (:filterEndpoints = false or e.id in :allowedEndpointIds)")
  Page<ScanJobSummary> findFilteredSummaries(
      @Param("endpointId") Long endpointId,
      @Param("status") ScanJobStatus status,
      @Param("verdict") info.trizub.clamav.webclient.model.ScanVerdict verdict,
      @Param("filterEndpoints") boolean filterEndpoints,
      @Param("allowedEndpointIds") java.util.Collection<Long> allowedEndpointIds,
      Pageable pageable);

  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobSummary("
          + "j.id, j.type, j.status, j.verdict, j.target, j.submittedBy, j.submittedTransport, j.submittedAt, "
          + "e.id, e.name, j.sizeBytes, j.startedAt, j.finishedAt) "
          + "from ScanJob j left join j.endpoint e where j.id in :ids")
  List<ScanJobSummary> findSummariesByIdIn(@Param("ids") java.util.Collection<String> ids);

  // Exact quarantine state is retained for every API page without bloating the
  // dashboard/scan/jobs list projection or reading any TEXT columns.
  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobListQuarantine("
          + "j.id, j.quarantinePath) from ScanJob j where j.id in :ids")
  List<info.trizub.clamav.webclient.model.ScanJobListQuarantine> findApiListQuarantine(
      @Param("ids") java.util.Collection<String> ids);

  // Page-scoped second query: large TEXT columns only for infected or failed rows,
  // never for all history or ordinary clean job list entries.
  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobListDiagnostics("
          + "j.id, j.foundVirusesJson, j.errorMessage, j.quarantinePath) "
          + "from ScanJob j where j.id in :ids and j.verdict in :verdicts")
  List<info.trizub.clamav.webclient.model.ScanJobListDiagnostics> findApiListDiagnostics(
      @Param("ids") java.util.Collection<String> ids,
      @Param("verdicts")
          java.util.Collection<info.trizub.clamav.webclient.model.ScanVerdict> verdicts);

  // Full records are still loaded for execution, authorization and detail views.
  @Override
  @EntityGraph(attributePaths = "endpoint")
  Optional<ScanJob> findById(String id);

  // Bounded startup recovery of already-confirmed infected scans. Never retry a
  // RUNNING scan if its source may have been moved to quarantine.
  @Query(
      "select j.id from ScanJob j where j.status = :status and j.foundVirusesJson is not null "
          + "order by j.submittedAt, j.id")
  List<String> findStagedPositiveJobIds(@Param("status") ScanJobStatus status, Pageable pageable);

  /**
   * Keyset pagination lets live recovery visit all stranded jobs without an ever-growing offset and
   * without being trapped behind older failed rows.
   */
  @Query(
      "select j.id from ScanJob j where j.status = :status "
          + "and j.foundVirusesJson is not null and j.id > :after order by j.id")
  List<String> findStagedPositiveIdsAfter(
      @Param("status") ScanJobStatus status, @Param("after") String after, Pageable pageable);

  @Query(
      "select j.id from ScanJob j where j.status = :status "
          + "and j.foundVirusesJson is null and j.startedAt < :cutoff "
          + "and j.id > :after order by j.id")
  List<String> findOrphanRunningIdsAfter(
      @Param("status") ScanJobStatus status,
      @Param("cutoff") Instant cutoff,
      @Param("after") String after,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "update ScanJob j set j.status = :queued, j.startedAt = null "
          + "where j.id = :id and j.status = :running and j.foundVirusesJson is null")
  int requeueOrphan(
      @Param("id") String id,
      @Param("running") ScanJobStatus running,
      @Param("queued") ScanJobStatus queued);

  // One indexed SQL update replaces findAll() + per-row writes during startup.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("update ScanJob j set j.status = :queued where j.status = :running")
  int requeueInterrupted(
      @Param("running") ScanJobStatus running, @Param("queued") ScanJobStatus queued);

  // The queue sweeper requests just IDs, in small bounded pages.
  @Query(
      "select j.id from ScanJob j where j.status = :status "
          + "order by j.submittedAt asc, j.id asc")
  List<String> findQueuedIds(@Param("status") ScanJobStatus status, Pageable pageable);

  long countByStatus(ScanJobStatus status);

  // Age-first keyset seek, matching the (status, finished_at, id) retention index.
  // Keeping these queries separate avoids nullable timestamp comparisons that differ
  // between H2, PostgreSQL, MariaDB and MySQL query planners.
  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobRetentionRow("
          + "j.id, j.finishedAt, j.storedPath, j.quarantinePath) from ScanJob j "
          + "where j.status = :status and j.finishedAt < :cutoff "
          + "order by j.finishedAt asc, j.id asc")
  List<info.trizub.clamav.webclient.model.ScanJobRetentionRow> findRetentionFirst(
      @Param("status") ScanJobStatus status, @Param("cutoff") Instant cutoff, Pageable pageable);

  @Query(
      "select new info.trizub.clamav.webclient.model.ScanJobRetentionRow("
          + "j.id, j.finishedAt, j.storedPath, j.quarantinePath) from ScanJob j "
          + "where j.status = :status and j.finishedAt < :cutoff "
          + "and (j.finishedAt > :afterTime or (j.finishedAt = :afterTime and j.id > :afterId)) "
          + "order by j.finishedAt asc, j.id asc")
  List<info.trizub.clamav.webclient.model.ScanJobRetentionRow> findRetentionAfter(
      @Param("status") ScanJobStatus status,
      @Param("cutoff") Instant cutoff,
      @Param("afterTime") Instant afterTime,
      @Param("afterId") String afterId,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "update ScanJob j set j.status = :running, j.startedAt = :started "
          + "where j.id = :id and j.status = :queued")
  int claimQueued(
      @Param("id") String id,
      @Param("queued") ScanJobStatus queued,
      @Param("running") ScanJobStatus running,
      @Param("started") Instant started);
}
