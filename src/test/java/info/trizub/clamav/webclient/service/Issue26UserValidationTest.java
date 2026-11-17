package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class Issue26UserValidationTest {
  private static final String VALID_PASSWORD = "a-valid-long-password-123";
  private final AppUserRepository repo = mock(AppUserRepository.class);
  private final UserService service =
      new UserService(repo, new BCryptPasswordEncoder(), mock(ApplicationEventPublisher.class));

  @ParameterizedTest
  @ValueSource(
      strings = {"", " ", "ab", "bad username", "name!", "ldap:john", "LDAP:john", "oIdC:john"})
  void invalidUsernamesNeverReachDatabase(String invalid) {
    assertThatThrownBy(
            () -> service.createUser(invalid, VALID_PASSWORD, Set.of("ROLE_VIEWER"), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Username");
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void nullAndOverlengthNamesAreRejected() {
    for (String invalid : new String[] {null, "x".repeat(65)})
      assertThatThrownBy(
              () -> service.createUser(invalid, VALID_PASSWORD, Set.of("ROLE_VIEWER"), true))
          .isInstanceOf(IllegalArgumentException.class);
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void validUppercaseNamesAreNormalizedBeforePersisting() {
    when(repo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AppUser user = service.createUser("Alice_Smith", VALID_PASSWORD, Set.of("ROLE_VIEWER"), true);
    assertThat(user.getUsername()).isEqualTo("alice_smith");
    verify(repo).existsByUsernameIgnoreCase("alice_smith");
  }

  @Test
  void existingMixedCaseNamePreventsCaseOnlyDuplicate() {
    when(repo.existsByUsernameIgnoreCase("admin")).thenReturn(true);
    assertThatThrownBy(
            () -> service.createUser("Admin", VALID_PASSWORD, Set.of("ROLE_VIEWER"), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void asciiAndMultibytePasswordsAboveBcrypt72ByteLimitAreRejected() {
    for (String invalid : new String[] {"short", "z".repeat(73), "🐈".repeat(20)}) {
      assertThatThrownBy(() -> service.createUser("alice", invalid, Set.of("ROLE_VIEWER"), true))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("72 UTF-8 bytes");
      assertThatThrownBy(() -> service.resetPassword(11L, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void exactly72BytesIsAllowed() {
    when(repo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    assertThat(service.createUser("bob", "x".repeat(72), Set.of("ROLE_VIEWER"), true)).isNotNull();
  }
}
