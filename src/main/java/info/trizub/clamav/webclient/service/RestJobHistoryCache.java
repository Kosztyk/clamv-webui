package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.configuration.ConfigurationCatalog;
import info.trizub.clamav.webclient.configuration.ConfigurationSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Short-lived per-operation cache for external REST job listings. A monitor with many viewers makes
 * one upstream request per TTL, not one per viewer. Never include user filters in this key:
 * pagination and filtering run on the same snapshot.
 */
@Service
public class RestJobHistoryCache {
  private final RestApiBridge bridge;
  private final long ttlNanos;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  @Autowired
  public RestJobHistoryCache(RestApiBridge bridge, Environment environment) {
    this(bridge, resolveTtlMs(environment));
  }

  // Public for isolated unit tests without Spring environment bootstrap.
  public RestJobHistoryCache(RestApiBridge bridge, int ttlMs) {
    this.bridge = bridge;
    this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, Math.min(30000, ttlMs)));
  }

  static int resolveTtlMs(Environment environment) {
    String key = "CLAMAV_REST_HISTORY_CACHE_TTL_MS";
    String configured =
        ConfigurationSource.override(environment, ConfigurationCatalog.get(key))
            .map(ConfigurationSource.Override::value)
            .orElseGet(() -> environment.getProperty(key, "5000"));
    try {
      return Integer.parseInt(configured);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "REST history cache TTL must be an integer in milliseconds", e);
    }
  }

  public RestApiBridge.ApiResponse read(String operation) {
    if (!"jobs".equals(operation) && !"history".equals(operation)) {
      throw new IllegalArgumentException("Only job/history lists are cacheable");
    }
    Entry entry = entries.computeIfAbsent(operation, ignored -> new Entry());
    synchronized (entry) {
      long now = System.nanoTime();
      if (entry.expiresAtNanos > now) {
        if (entry.error != null) throw entry.error;
        return entry.snapshot;
      }
      try {
        RestApiBridge.ApiResponse response = bridge.read(operation, Map.of());
        entry.snapshot = response;
        entry.error = null;
        entry.expiresAtNanos = System.nanoTime() + ttlNanos;
        return response;
      } catch (ResponseStatusException error) {
        // A broken upstream cannot be hammered by hundreds of concurrent tabs.
        entry.snapshot = null;
        entry.error = error;
        entry.expiresAtNanos = System.nanoTime() + Math.min(ttlNanos, TimeUnit.SECONDS.toNanos(1));
        throw error;
      }
    }
  }

  /** Used after an upstream action and by tests; does not remove saved application settings. */
  public void invalidate() {
    entries.clear();
  }

  private static final class Entry {
    private RestApiBridge.ApiResponse snapshot;
    private ResponseStatusException error;
    private long expiresAtNanos;
  }
}
