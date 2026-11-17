package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.WatchedDirectory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchedDirectoryRepository extends JpaRepository<WatchedDirectory, Long> {
  List<WatchedDirectory> findByEnabledTrue();
}
