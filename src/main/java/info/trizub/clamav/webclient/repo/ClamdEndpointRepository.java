package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClamdEndpointRepository extends JpaRepository<ClamdEndpoint, Long> {
    Optional<ClamdEndpoint> findByName(String name);
}
