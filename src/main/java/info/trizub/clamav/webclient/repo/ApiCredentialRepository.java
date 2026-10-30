package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ApiCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {
    Optional<ApiCredential> findByTokenHash(String hash);
}
