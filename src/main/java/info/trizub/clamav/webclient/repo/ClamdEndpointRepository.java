package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClamdEndpointRepository extends JpaRepository<ClamdEndpoint, Long> {
  Optional<ClamdEndpoint> findByName(String name);
}
