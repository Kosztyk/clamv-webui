package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanJobRepository extends JpaRepository<ScanJob, String> {
    List<ScanJob> findTop200ByOrderBySubmittedAtDesc();
}
