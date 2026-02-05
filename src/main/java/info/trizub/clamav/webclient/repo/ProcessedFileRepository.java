package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ProcessedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedFileRepository extends JpaRepository<ProcessedFile, Long> {
    Optional<ProcessedFile> findByPath(String path);
}
