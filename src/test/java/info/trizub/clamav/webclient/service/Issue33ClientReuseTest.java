package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.Platform;

class Issue33ClientReuseTest {
  private static ClamdEndpoint endpoint() {
    var ep = new ClamdEndpoint("test", "127.0.0.1", 3310, Platform.UNIX);
    ReflectionTestUtils.setField(ep, "id", 31L);
    return ep;
  }

  @Test
  void stableEndpointReusesScanWrapperButNotDeadlineBoundProbes() {
    try (var provider = new ClamavClientProvider()) {
      var endpoint = endpoint();
      var first = provider.clientFor(endpoint);
      assertThat(provider.clientFor(endpoint)).isSameAs(first);
      assertThat(provider.probeFor(endpoint)).isNotSameAs(provider.probeFor(endpoint));
      assertThat(provider.clientFor(endpoint)).isSameAs(first);
    }
  }

  @Test
  void endpointMutationAndExplicitInvalidationRefreshScanWrapper() {
    try (var provider = new ClamavClientProvider()) {
      var endpoint = endpoint();
      var first = provider.clientFor(endpoint);
      endpoint.setPort(3311);
      var changed = provider.clientFor(endpoint);
      assertThat(changed).isNotSameAs(first);
      endpoint.setHost("127.0.0.2");
      assertThat(provider.clientFor(endpoint)).isNotSameAs(changed);
      provider.invalidate(endpoint.getId());
      assertThat(provider.clientFor(endpoint)).isNotSameAs(changed);
    }
  }

  @Test
  void unsavedEndpointsHaveNoSharedIdentity() {
    try (var provider = new ClamavClientProvider()) {
      var endpoint = new ClamdEndpoint("unsaved", "127.0.0.1", 3310, Platform.UNIX);
      assertThat(provider.clientFor(endpoint)).isNotSameAs(provider.clientFor(endpoint));
    }
  }

  @Test
  void concurrentCallersShareOneWrapperPerEndpoint() throws Exception {
    var threads = Executors.newFixedThreadPool(8);
    try (var provider = new ClamavClientProvider()) {
      var endpoint = endpoint();
      List<Future<ClamdClient>> pending = new ArrayList<>();
      for (int i = 0; i < 32; i++) pending.add(threads.submit(() -> provider.clientFor(endpoint)));
      var first = pending.get(0).get(5, TimeUnit.SECONDS);
      for (var result : pending) assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(first);
    } finally {
      threads.shutdownNow();
    }
  }
}
