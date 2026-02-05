package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.WatchedDirectory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WatchedDirectoryRepository extends JpaRepository<WatchedDirectory, Long> {
    List<WatchedDirectory> findByEnabledTrue();
}
