package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ApiCredential;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {
  Optional<ApiCredential> findByTokenHash(String hash);
}
