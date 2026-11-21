package info.trizub.clamav.webclient.identity;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalOidcUserServiceTest {
  @Test
  void nestedKeycloakRolesAndAuthentikGroupsAreReadWithoutImplicitAdmin() {
    Map<String, Object> claims =
        Map.of(
            "realm_access",
            Map.of("roles", List.of("readers", "writers")),
            "groups",
            List.of("ClamAV Viewers"));
    assertThat(ExternalOidcUserService.extractGroups(claims, "realm_access.roles"))
        .containsExactly("readers", "writers");
    assertThat(ExternalOidcUserService.extractGroups(claims, "groups"))
        .containsExactly("ClamAV Viewers");
    assertThat(ExternalOidcUserService.extractGroups(claims, "admin")).isEmpty();
    assertThat(ExternalOidcUserService.extractGroups(claims, "realm_access.__proto__.roles"))
        .isEmpty();
  }
}
