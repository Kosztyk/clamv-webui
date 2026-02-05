package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class UserService {

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(AppUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public void ensureDefaultAdmin() {
        if (repo.count() == 0) {
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setPasswordHash(encoder.encode("admin"));
            admin.setRoles(Set.of(Role.ADMIN.asAuthority(), Role.OPERATOR.asAuthority(), Role.VIEWER.asAuthority()));
            repo.save(admin);
        }
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, Set<String> authorities, boolean enabled) {
        if (repo.existsByUsername(username)) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRoles(authorities);
        u.setEnabled(enabled);
        return repo.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        repo.deleteById(id);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser u = repo.findById(id).orElseThrow();
        u.setPasswordHash(encoder.encode(newPassword));
        repo.save(u);
    }

    @Transactional
    public void markLogin(String username) {
        repo.findByUsername(username).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            repo.save(u);
        });
    }
}
