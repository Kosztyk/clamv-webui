package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import xyz.capybara.clamav.Platform;

class Issue26EndpointValidationTest {
  @Test
  void nameLengthAndCharacters() {
    assertThat(EndpointInputValidator.name("secondary_1")).isEqualTo("secondary_1");
    assertThat(EndpointInputValidator.name("A".repeat(64))).hasSize(64);
    for (String invalid : new String[] {null, " ", "x".repeat(65), "bad/name", " name", "name "})
      assertThatThrownBy(() -> EndpointInputValidator.name(invalid))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "/run/clamav/clamd.sock",
        "http://scanner:3310",
        "scanner host",
        "scanner@internal",
        "bad\\\\host",
        "host:abc",
        "localhost:3310"
      })
  void hostCannotBeSocketPathUrlOrMalformed(String invalid) {
    assertThatThrownBy(() -> EndpointInputValidator.host(invalid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsIpv6AndDnsWithoutResolvingExternalNames() {
    assertThat(EndpointInputValidator.host("clamav-server")).isEqualTo("clamav-server");
    assertThat(EndpointInputValidator.host("192.0.2.112")).isEqualTo("192.0.2.112");
    assertThatThrownBy(() -> EndpointInputValidator.host("999.999.999.999"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(EndpointInputValidator.host("[2001:db8::1]")).isEqualTo("2001:db8::1");
    assertThatThrownBy(() -> EndpointInputValidator.host("x".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidPortsAndPlatforms() {
    for (String port : new String[] {null, "abc", "0", "-1", "65536", "9999999999999"})
      assertThatThrownBy(() -> EndpointInputValidator.port(port))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(EndpointInputValidator.port("1")).isEqualTo(1);
    assertThat(EndpointInputValidator.port("65535")).isEqualTo(65535);
    for (String platform : new String[] {null, "linux", "UNIX/SHELL", ""})
      assertThatThrownBy(() -> EndpointInputValidator.platform(platform))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(EndpointInputValidator.platform("unix")).isEqualTo(Platform.UNIX);
  }

  @Test
  void serviceRejectsInvalidCreateBeforeInsertAndCaseInsensitiveDuplicate() {
    ClamdEndpointRepository repo = mock(ClamdEndpointRepository.class);
    when(repo.findAll())
        .thenReturn(List.of(new ClamdEndpoint("EXTRA", "scanner", 3310, Platform.UNIX)));
    EndpointService service =
        new EndpointService(
            repo,
            mock(SettingsService.class),
            mock(ClamdConfigurationObserver.class),
            new MockEnvironment());
    assertThatThrownBy(() -> service.create("new", "/run/clamd.sock", 3310, Platform.UNIX, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("extra", "scanner", 3310, Platform.UNIX, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already in use");
    assertThatThrownBy(() -> service.create("default", "scanner", 3310, Platform.UNIX, true))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repo, never()).save(any());
  }
}
