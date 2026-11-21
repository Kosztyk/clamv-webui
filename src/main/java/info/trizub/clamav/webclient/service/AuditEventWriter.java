package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate transaction boundary so an audit insert/commit failure never marks the caller's main
 * transaction rollback-only.
 */
@Service
public class AuditEventWriter {
  private final AuditEventRepository repository;

  public AuditEventWriter(AuditEventRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(AuditEvent event) {
    // Force constraint errors now (not at the caller's later commit).
    repository.saveAndFlush(event);
  }
}
