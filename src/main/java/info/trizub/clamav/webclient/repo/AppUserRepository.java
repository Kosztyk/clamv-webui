package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByUsername(String username);

  boolean existsByUsername(String username);

  boolean existsByUsernameIgnoreCase(String username);
}
