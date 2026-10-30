package info.trizub.clamav.webclient.repo;
import info.trizub.clamav.webclient.model.ExternalIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity,String> {}
