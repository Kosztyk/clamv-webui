package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ProcessedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ProcessedFileRepository extends JpaRepository<ProcessedFile, Long> {
    Optional<ProcessedFile> findByPath(String path);
    List<ProcessedFile> findByPathStartingWith(String pathPrefix);
    List<ProcessedFile> findTop500ByIdGreaterThanOrderByIdAsc(long id);
}
