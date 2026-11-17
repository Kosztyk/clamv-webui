package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.QuarantineArtifact;
import info.trizub.clamav.webclient.repo.QuarantineArtifactRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each journal transition is a committed, independent transaction before/after the FS operation.
 */
@Service
public class QuarantineArtifactLedger {
  private final QuarantineArtifactRepository repository;

  public QuarantineArtifactLedger(QuarantineArtifactRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reserve(QuarantineArtifact artifact) {
    repository.saveAndFlush(artifact);
  }

  /** All detections are reserved together, before any file is moved. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reserveBatch(List<QuarantineArtifact> artifacts) {
    repository.saveAllAndFlush(artifacts);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transition(String id, String status) {
    QuarantineArtifact artifact = repository.findById(id).orElseThrow();
    // Journal status reflects current evidence, including a file later found missing.
    // Never return a terminal status to PREPARED.
    if ("PREPARED".equals(status) && !"PREPARED".equals(artifact.getStatus()))
      throw new IllegalStateException("Quarantine journal cannot return to PREPARED");
    artifact.setStatus(status);
    repository.saveAndFlush(artifact);
  }

  /** Persist a reason with every explicit failed outcome, not only a status. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(String id, String code) {
    QuarantineArtifact artifact = repository.findById(id).orElseThrow();
    artifact.fail(code);
    repository.saveAndFlush(artifact);
  }

  @Transactional(readOnly = true)
  public List<QuarantineArtifact> forJob(String jobId) {
    return repository.findByJobIdOrderByIdAsc(jobId);
  }

  @Transactional(readOnly = true)
  public List<QuarantineArtifact> outstandingAfter(String lastId) {
    return repository.findOutstandingAfter(
        lastId, org.springframework.data.domain.PageRequest.of(0, 100));
  }

  @Transactional(readOnly = true)
  public List<String> interruptedJobs() {
    return repository.findRecoverableJobIds(
        info.trizub.clamav.webclient.model.ScanJobStatus.RUNNING,
        org.springframework.data.domain.PageRequest.of(0, 100));
  }
}
