package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Issue #31: all consumers share one per-endpoint snapshot within a short TTL. */
class Issue31ClamdSnapshotCacheTest {
  private static ClamdEndpoint endpoint(String name, String host) {
    return new ClamdEndpoint(name, host, 3310, xyz.capybara.clamav.Platform.UNIX);
  }

  private static ClamdClient client() throws IOException {
    ClamdClient client = mock(ClamdClient.class);
    when(client.version()).thenReturn("ClamAV 1.5.0/27920/Thu Oct 29 10:00:00 2026");
    when(client.stats()).thenReturn("THREADS: live 3 idle 2 max 10\nQUEUE: 0 items\nEND");
    return client;
  }

  @Test
  void concurrentViewersShareOneRefreshAndNoPing() throws Exception {
    var endpoints = mock(EndpointService.class);
    var provider = mock(ClamavClientProvider.class);
    var ep = endpoint("main", "clamav.local");
    when(endpoints.get(1L)).thenReturn(ep);
    var client = client();
    when(client.version())
        .thenAnswer(
            invocation -> {
              Thread.sleep(120);
              return "ClamAV 1.5.0/27920/Thu Oct 29 10:00:00 2026";
            });
    when(provider.probeFor(ep)).thenReturn(client);
    var cache = new ClamdTopService(endpoints, provider, 2000);
    var executor = Executors.newFixedThreadPool(12);
    try {
      var ready = new CountDownLatch(12);
      var start = new CountDownLatch(1);
      List<Future<Map<String, Object>>> tasks = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return cache.snapshot(1L);
                }));
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var f : tasks) assertThat(f.get(5, TimeUnit.SECONDS).get("ok")).isEqualTo(true);
      verify(client, times(1)).version();
      verify(client, times(1)).stats();
      verify(client, never()).ping();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aCacheHitKeepsTimestampThenRefreshesAfterTtl() throws Exception {
    var endpoints = mock(EndpointService.class);
    var provider = mock(ClamavClientProvider.class);
    var ep = endpoint("main", "clamav.local");
    when(endpoints.get(1L)).thenReturn(ep);
    var client = client();
    when(provider.probeFor(ep)).thenReturn(client);
    var cache = new ClamdTopService(endpoints, provider, 1000);
    var first = cache.snapshot(1L);
    assertThat(cache.snapshot(1L)).isSameAs(first);
    Thread.sleep(1100);
    assertThat(cache.snapshot(1L)).isNotSameAs(first);
    verify(client, times(2)).stats();
  }

  @Test
  void failedDaemonResponseIsCachedAndDoesNotTriggerPerViewerReconnect() throws Exception {
    var endpoints = mock(EndpointService.class);
    var provider = mock(ClamavClientProvider.class);
    var ep = endpoint("down", "clamav.local");
    when(endpoints.get(1L)).thenReturn(ep);
    var client = client();
    when(client.stats()).thenThrow(new IOException("daemon unavailable"));
    when(provider.probeFor(ep)).thenReturn(client);
    var cache = new ClamdTopService(endpoints, provider, 2000);
    var first = cache.snapshot(1L);
    assertThat(first.get("ok")).isEqualTo(false);
    assertThat(cache.snapshot(1L)).isSameAs(first);
    verify(client, times(1)).stats();
  }

  @Test
  void changedEndpointHostInvalidatesItsCache() throws Exception {
    var endpoints = mock(EndpointService.class);
    var provider = mock(ClamavClientProvider.class);
    var before = endpoint("main", "one.local");
    var after = endpoint("main", "two.local");
    when(endpoints.get(1L)).thenReturn(before, after);
    var c1 = client();
    var c2 = client();
    when(provider.probeFor(before)).thenReturn(c1);
    when(provider.probeFor(after)).thenReturn(c2);
    var cache = new ClamdTopService(endpoints, provider, 2000);
    assertThat(cache.snapshot(1L).get("ok")).isEqualTo(true);
    assertThat(cache.snapshot(1L).get("ok")).isEqualTo(true);
    verify(c1).stats();
    verify(c2).stats();
  }
}
