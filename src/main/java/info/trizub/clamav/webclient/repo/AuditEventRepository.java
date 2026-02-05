package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop200ByOrderByAtDesc();
}
