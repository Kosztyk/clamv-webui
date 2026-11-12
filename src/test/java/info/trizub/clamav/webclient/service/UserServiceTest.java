package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    @Test void lastEnabledAdminCannotBeDeleted() {
        AppUserRepository repo = mock(AppUserRepository.class);
        AppUser admin = new AppUser("admin", "hash", Set.of(Role.ADMIN.asAuthority()));
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(repo.findById(1L)).thenReturn(Optional.of(admin));
        when(repo.findAll()).thenReturn(List.of(admin));
        UserService users = new UserService(repo, new BCryptPasswordEncoder(), mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThatThrownBy(() -> users.deleteUser(1L)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("last enabled administrator");
        verify(repo, never()).deleteById(anyLong());
    }

    @Test void shortPasswordsAreRejected() {
        UserService users = new UserService(mock(AppUserRepository.class), new BCryptPasswordEncoder(), mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThatThrownBy(() -> users.createUser("new", "admin", Set.of("ROLE_VIEWER"), true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
