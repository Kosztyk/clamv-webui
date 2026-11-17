package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.security.LocalAccountChanged;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);
  private static final Pattern LOCAL_USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");
  private static final String ADMIN_AUTHORITY = Role.ADMIN.asAuthority();
  private static final Set<String> LOCAL_AUTHORITIES =
      Set.of(ADMIN_AUTHORITY, Role.OPERATOR.asAuthority(), Role.VIEWER.asAuthority());

  @Value("${clamav.bootstrap.admin.password:}")
  private String configuredBootstrapPassword = "";

  private final AppUserRepository repo;
  private final PasswordEncoder encoder;
  private final ApplicationEventPublisher events;

  public UserService(
      AppUserRepository repo, PasswordEncoder encoder, ApplicationEventPublisher events) {
    this.repo = repo;
    this.encoder = encoder;
    this.events = events;
  }

  @Transactional
  public void ensureDefaultAdmin() {
    if (repo.count() != 0) {
      // A local break-glass account must remain usable if all external IdPs are offline.
      // Do not silently turn an existing unassigned account into an administrator.
      var enabledAdmins =
          repo.findAll().stream()
              .filter(u -> u.isEnabled() && u.getRoles().contains(ADMIN_AUTHORITY))
              .toList();
      if (enabledAdmins.isEmpty()) {
        throw new IllegalStateException(
            "No enabled LOCAL administrator exists; restore an admin via a controlled database recovery");
      }
      for (AppUser admin : enabledAdmins) {
        if (encoder.matches("admin", admin.getPasswordHash())) {
          throw new IllegalStateException(
              "Insecure legacy admin/admin credential detected; rotate it on Phase 2 before upgrading");
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
            Files.createFile(
                file,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
          } catch (UnsupportedOperationException ignored) {
            // On non-POSIX filesystems the deployment must secure /app/data.
            Files.createFile(file);
          }
          Files.writeString(file, password + System.lineSeparator(), StandardOpenOption.WRITE);
        } catch (IOException e) {
          throw new IllegalStateException(
              "Unable to write one-time bootstrap password to " + file, e);
        }
        log.warn(
            "First-run admin password saved to {}. Rotate it after login and remove the file.",
            file);
      }
      admin.setUsername("admin");
      admin.setPasswordHash(encoder.encode(password));
      admin.setRoles(LOCAL_AUTHORITIES);
      repo.save(admin);
    }
  }

  private static void validatePassword(String password) {
    // BCrypt processes a maximum of 72 UTF-8 bytes, not 72 Java characters.
    // Never accept a value that would be silently truncated or rejected by the encoder.
    if (password == null
        || password.length() < 12
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalArgumentException(
          "Password must contain at least 12 characters and at most 72 UTF-8 bytes");
    }
  }

  private static String normalizedUsername(String username) {
    if (username == null || username.isBlank())
      throw new IllegalArgumentException(
          "Username must be 3–64 letters, numbers, dots, underscores or hyphens");
    // Normalize only new local accounts; do not rename existing accounts or IdP principals.
    String normalized = username.toLowerCase(Locale.ROOT);
    if (!LOCAL_USERNAME.matcher(normalized).matches())
      throw new IllegalArgumentException(
          "Username must be 3–64 letters, numbers, dots, underscores or hyphens");
    // Explicit defense in depth, including names supplied with mixed-case prefixes.
    if (normalized.startsWith("ldap:") || normalized.startsWith("oidc:"))
      throw new IllegalArgumentException("Reserved identity namespace");
    return normalized;
  }

  @Transactional
  public AppUser createUser(
      String username, String rawPassword, Set<String> authorities, boolean enabled) {
    validatePassword(rawPassword);
    String normalized = normalizedUsername(username);
    if (repo.existsByUsernameIgnoreCase(normalized)) {
      throw new IllegalArgumentException("User already exists: " + normalized);
    }
    AppUser u = new AppUser();
    u.setUsername(normalized);
    u.setPasswordHash(encoder.encode(rawPassword));
    u.setRoles(validatedRoles(authorities));
    u.setEnabled(enabled);
    try {
      // Flush before reporting success: duplicate attempts racing one another must
      // produce a validation message, not a transaction-commit HTTP 500.
      return repo.saveAndFlush(u);
    } catch (DataIntegrityViolationException e) {
      throw new IllegalArgumentException("Username is already in use", e);
    }
  }

  @Transactional
  public void deleteUser(Long id) {
    AppUser target = repo.findById(id).orElseThrow();
    requireEnabledAdminAfterChange(target, false);
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
    requireEnabledAdminAfterChange(user, enabled && roles.contains(ADMIN_AUTHORITY));
    if (user.isEnabled() == enabled && user.getRoles().equals(roles)) return;
    user.setRoles(roles);
    user.setEnabled(enabled);
    repo.save(user);
    events.publishEvent(new LocalAccountChanged(id));
  }

  private void requireEnabledAdminAfterChange(AppUser target, boolean keepsAdminAccess) {
    if (keepsAdminAccess || !target.isEnabled() || !target.getRoles().contains(ADMIN_AUTHORITY)) {
      return;
    }
    boolean anotherAdmin =
        repo.findAll().stream()
            .anyMatch(
                u ->
                    !u.getId().equals(target.getId())
                        && u.isEnabled()
                        && u.getRoles().contains(ADMIN_AUTHORITY));
    if (!anotherAdmin) {
      throw new IllegalArgumentException(
          "Cannot delete, disable or demote the last enabled administrator");
    }
  }

  private static Set<String> validatedRoles(Set<String> authorities) {
    if (authorities == null
        || authorities.isEmpty()
        || !LOCAL_AUTHORITIES.containsAll(authorities)) {
      throw new IllegalArgumentException("Choose at least one supported role");
    }
    return new java.util.HashSet<>(authorities);
  }

  @Transactional
  public void markLogin(String username) {
    repo.findByUsername(username)
        .ifPresent(
            u -> {
              u.setLastLoginAt(Instant.now());
              repo.save(u);
            });
  }
}
