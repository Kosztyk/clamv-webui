package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.security.LocalAccountChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    @Value("${clamav.bootstrap.admin.password:}")
    private String configuredBootstrapPassword = "";
    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    public UserService(AppUserRepository repo, PasswordEncoder encoder, ApplicationEventPublisher events) {
        this.repo = repo;
        this.encoder = encoder;
        this.events = events;
    }

    @Transactional
    public void ensureDefaultAdmin() {
        if (repo.count() != 0) {
            // A local break-glass account must remain usable if all external IdPs are offline.
            // Do not silently turn an existing unassigned account into an administrator.
            var enabledAdmins = repo.findAll().stream().filter(u -> u.isEnabled()
                    && u.getRoles().contains(Role.ADMIN.asAuthority())).toList();
            if (enabledAdmins.isEmpty()) {
                throw new IllegalStateException("No enabled LOCAL administrator exists; restore an admin via a controlled database recovery");
            }
            for (AppUser admin : enabledAdmins) {
                if (encoder.matches("admin", admin.getPasswordHash())) {
                    throw new IllegalStateException("Insecure legacy admin/admin credential detected; rotate it on Phase 2 before upgrading");
                }
            }
            return;
        }
        {
            AppUser admin = new AppUser();
            String password = System.getenv("CLAMAV_BOOTSTRAP_ADMIN_PASSWORD");
            if (password == null || password.isBlank()) password = configuredBootstrapPassword;
            if (password != null && !password.isBlank()) {
                validatePassword(password);
            } else {
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
                Path file = Paths.get("data/bootstrap-admin-password");
                try {
                    Files.createDirectories(file.getParent());
                    try {
                        Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
                    } catch (UnsupportedOperationException ignored) {
                        // On non-POSIX filesystems the deployment must secure /app/data.
                        Files.createFile(file);
                    }
                    Files.writeString(file, password + System.lineSeparator(), StandardOpenOption.WRITE);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to write one-time bootstrap password to " + file, e);
                }
                log.warn("First-run admin password saved to {}. Rotate it after login and remove the file.", file);
            }
            admin.setUsername("admin");
            admin.setPasswordHash(encoder.encode(password));
            admin.setRoles(Set.of(Role.ADMIN.asAuthority(), Role.OPERATOR.asAuthority(), Role.VIEWER.asAuthority()));
            repo.save(admin);
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("Admin password must be 12-128 characters");
        }
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, Set<String> authorities, boolean enabled) {
        validatePassword(rawPassword);
        if (username == null || username.startsWith("ldap:") || username.startsWith("oidc:"))
            throw new IllegalArgumentException("Reserved identity namespace");
        if (repo.existsByUsername(username)) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRoles(validatedRoles(authorities));
        u.setEnabled(enabled);
        return repo.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser target = repo.findById(id).orElseThrow();
        requireAnotherAdmin(target);
        repo.deleteById(id);
        events.publishEvent(new LocalAccountChanged(id));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        validatePassword(newPassword);
        AppUser u = repo.findById(id).orElseThrow();
        u.setPasswordHash(encoder.encode(newPassword));
        repo.save(u);
        events.publishEvent(new LocalAccountChanged(id));
    }

    @Transactional
    public void updateAccess(Long id, Set<String> authorities, boolean enabled) {
        Set<String> roles = validatedRoles(authorities);
        AppUser user = repo.findById(id).orElseThrow();
        if (user.isEnabled() == enabled && user.getRoles().equals(roles)) return;
        if (!enabled || !roles.contains(Role.ADMIN.asAuthority())) requireAnotherAdmin(user);
        user.setRoles(roles);
        user.setEnabled(enabled);
        repo.save(user);
        events.publishEvent(new LocalAccountChanged(id));
    }

    private void requireAnotherAdmin(AppUser target) {
        if (target.getRoles().contains(Role.ADMIN.asAuthority()) && target.isEnabled()
                && repo.findAll().stream().noneMatch(u -> !u.getId().equals(target.getId())
                    && u.isEnabled() && u.getRoles().contains(Role.ADMIN.asAuthority()))) {
            throw new IllegalArgumentException("Cannot delete, disable or demote the last enabled administrator");
        }
    }

    private static Set<String> validatedRoles(Set<String> authorities) {
        Set<String> allowed = Set.of(Role.ADMIN.asAuthority(), Role.OPERATOR.asAuthority(), Role.VIEWER.asAuthority());
        if (authorities == null || authorities.isEmpty() || !allowed.containsAll(authorities))
            throw new IllegalArgumentException("Choose at least one supported role");
        return new java.util.HashSet<>(authorities);
    }

    @Transactional
    public void markLogin(String username) {
        repo.findByUsername(username).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            repo.save(u);
        });
    }
}
