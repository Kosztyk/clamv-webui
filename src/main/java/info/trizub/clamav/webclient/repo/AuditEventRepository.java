package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.AuditEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository
    extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
  List<AuditEvent> findTop200ByOrderByAtDesc();

  @Query(
      "select new info.trizub.clamav.webclient.model.AuditRetentionRow(a.id, a.at) "
          + "from AuditEvent a where a.at < :cutoff order by a.at asc, a.id asc")
  List<info.trizub.clamav.webclient.model.AuditRetentionRow> findRetentionFirst(
      @Param("cutoff") Instant cutoff, Pageable pageable);

  @Query(
      "select new info.trizub.clamav.webclient.model.AuditRetentionRow(a.id, a.at) "
          + "from AuditEvent a where a.at < :cutoff "
          + "and (a.at > :afterTime or (a.at = :afterTime and a.id > :afterId)) "
          + "order by a.at asc, a.id asc")
  List<info.trizub.clamav.webclient.model.AuditRetentionRow> findRetentionAfter(
      @Param("cutoff") Instant cutoff,
      @Param("afterTime") Instant afterTime,
      @Param("afterId") long afterId,
      Pageable pageable);
}
