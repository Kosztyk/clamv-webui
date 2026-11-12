package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import info.trizub.clamav.webclient.model.ScanJobStatus;

import java.util.List;

public interface ScanJobRepository extends JpaRepository<ScanJob, String>, JpaSpecificationExecutor<ScanJob> {
    List<ScanJob> findTop200ByOrderBySubmittedAtDesc();
    long countByStatus(ScanJobStatus status);
    List<ScanJob> findTop100ByStatusOrderBySubmittedAtAsc(ScanJobStatus status);
    List<ScanJob> findTop500ByStatusAndFinishedAtBeforeAndIdGreaterThanOrderByIdAsc(ScanJobStatus status,Instant cutoff,String id);
    @Modifying(clearAutomatically=true, flushAutomatically=true)
    @Transactional
    @Query("update ScanJob j set j.status = :running, j.startedAt = :started " +
           "where j.id = :id and j.status = :queued")
    int claimQueued(@Param("id") String id, @Param("queued") ScanJobStatus queued,
                    @Param("running") ScanJobStatus running, @Param("started") Instant started);
}
