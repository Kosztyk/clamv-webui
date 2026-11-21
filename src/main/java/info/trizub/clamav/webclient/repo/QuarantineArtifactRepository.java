package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuarantineArtifactRepository extends JpaRepository<QuarantineArtifact, String> {
  List<QuarantineArtifact> findByJobIdOrderByIdAsc(String jobId);

  /** Keyset traversal also repairs finished jobs whose permission hardening was interrupted. */
  @Query(
      "select a from QuarantineArtifact a where a.id > :lastId and a.status in "
          + "('PREPARED', 'HARDEN_FAILED') order by a.id")
  List<QuarantineArtifact> findOutstandingAfter(@Param("lastId") String lastId, Pageable page);

  /** Only unfinished jobs are checked on startup; avoid materializing all historical artifacts. */
  @Query(
      "select distinct a.jobId from QuarantineArtifact a where a.jobId in "
          + "(select j.id from ScanJob j where j.status = :status) order by a.jobId")
  List<String> findRecoverableJobIds(@Param("status") ScanJobStatus status, Pageable page);
}
