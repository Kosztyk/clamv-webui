package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RestJobHistoryCacheTest {
  @Test
  void twentyViewersAndMultiplePagesUseOneRefresh() throws Exception {
    RestApiBridge upstream = mock(RestApiBridge.class);
    RestApiBridge.ApiResponse sample =
        new RestApiBridge.ApiResponse(
            200, new ObjectMapper().readTree("{\"jobs\":[{\"id\":\"one\"}]}"));
    AtomicInteger calls = new AtomicInteger();
    when(upstream.read(eq("history"), eq(Map.of())))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              Thread.sleep(80);
              return sample;
            });
    RestJobHistoryCache cache = new RestJobHistoryCache(upstream, 5000);
    ExecutorService workers = Executors.newFixedThreadPool(20);
    try {
      CountDownLatch ready = new CountDownLatch(20);
      CountDownLatch go = new CountDownLatch(1);
      var futures =
          IntStream.range(0, 20)
              .mapToObj(
                  i ->
                      workers.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return cache.read("history");
                          }))
              .toList();
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (var f : futures) assertThat(f.get(3, TimeUnit.SECONDS)).isSameAs(sample);
      assertThat(calls.get()).isEqualTo(1);
      assertThat(cache.read("history")).isSameAs(sample);
      assertThat(calls.get()).isEqualTo(1);
      cache.invalidate();
      cache.read("history");
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void cachesOnlyJobListsAndRefreshesAfterExpiry() throws Exception {
    RestApiBridge upstream = mock(RestApiBridge.class);
    when(upstream.read(eq("jobs"), eq(Map.of())))
        .thenReturn(
            new RestApiBridge.ApiResponse(200, new ObjectMapper().readTree("{\"jobs\":[]}")));
    RestJobHistoryCache cache = new RestJobHistoryCache(upstream, 1000);
    cache.read("jobs");
    cache.read("jobs");
    verify(upstream, times(1)).read("jobs", Map.of());
    Thread.sleep(1100);
    cache.read("jobs");
    verify(upstream, times(2)).read("jobs", Map.of());
    assertThatThrownBy(() -> cache.read("webhooks")).isInstanceOf(IllegalArgumentException.class);
  }
}
