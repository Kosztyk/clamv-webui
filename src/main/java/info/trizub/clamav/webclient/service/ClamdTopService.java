package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.configuration.ConfigurationCatalog;
import info.trizub.clamav.webclient.configuration.ConfigurationSource;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class ClamdTopService {
  private static final Logger log = LoggerFactory.getLogger(ClamdTopService.class);

  private static final Pattern THREADS_PATTERN =
      Pattern.compile(
          "THREADS:\\s*live\\s+(\\d+)\\s+idle\\s+(\\d+)\\s+max\\s+(\\d+)(?:\\s+idle-timeout\\s+(\\d+))?.*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern QUEUE_PATTERN =
      Pattern.compile(
          "QUEUE:\\s*(\\d+)\\s+items(?:\\s+(\\d+)\\s+max)?(?:\\s.*)?", Pattern.CASE_INSENSITIVE);
  private static final Pattern POOLS_PATTERN =
      Pattern.compile("POOLS:\\s+(\\d+).*", Pattern.CASE_INSENSITIVE);
  private static final Pattern STATS_PATTERN =
      Pattern.compile("STATS\\s+(.+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern KEY_VALUE_PATTERN =
      Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)\\s+([^\\s]+)");

  private static final int MAX_CACHE_ENTRIES = 128;
  private final EndpointService endpoints;
  private final ClamavClientProvider clientProvider;
  private final long ttlNanos;

  // A bounded per-endpoint cache. All callers, including the scheduled metrics collector,
  // share the same snapshot; a change of host/port/name makes a different key.
  private final Map<EndpointKey, CacheEntry> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<EndpointKey, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<EndpointKey, CacheEntry> eldest) {
              return size() > MAX_CACHE_ENTRIES;
            }
          });

  // Keep the constructor used by isolated service/protocol tests.
  public ClamdTopService(EndpointService endpoints, ClamavClientProvider clientProvider) {
    this(endpoints, clientProvider, 2000);
  }

  @Autowired
  public ClamdTopService(
      EndpointService endpoints, ClamavClientProvider clientProvider, Environment environment) {
    this(endpoints, clientProvider, resolveCacheTtlMillis(environment));
  }

  // Keep the numeric constructor for existing service tests and programmatic clients.
  public ClamdTopService(
      EndpointService endpoints, ClamavClientProvider clientProvider, long cacheTtlMillis) {
    this.endpoints = endpoints;
    this.clientProvider = clientProvider;
    this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, Math.min(3000, cacheTtlMillis)));
  }

  /** Respect explicit deployment configuration before a saved Configuration Center value. */
  public static long resolveCacheTtlMillis(Environment environment) {
    String key = "clamav.metrics.clamdtop-cache-ttl-ms";
    String value =
        ConfigurationSource.override(environment, ConfigurationCatalog.get(key))
            .map(ConfigurationSource.Override::value)
            .orElseGet(() -> environment.getProperty(key, "2000"));
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "ClamdTop cache TTL must be a whole number of milliseconds", e);
    }
  }

  private record EndpointKey(
      Long id, String name, String host, int port, String platform, boolean enabled) {
    static EndpointKey of(ClamdEndpoint ep) {
      return new EndpointKey(
          ep.getId(),
          ep.getName(),
          ep.getHost(),
          ep.getPort(),
          ep.getPlatform() == null ? "" : ep.getPlatform().name(),
          ep.isEnabled());
    }
  }

  private static final class CacheEntry {
    final ReentrantLock refresh = new ReentrantLock();
    volatile Map<String, Object> snapshot;
    volatile long refreshedAtNanos;
  }

  private boolean fresh(CacheEntry entry) {
    return entry.snapshot != null && System.nanoTime() - entry.refreshedAtNanos < ttlNanos;
  }

  public Map<String, Object> snapshot(Long endpointId) {
    ClamdEndpoint ep =
        endpointId != null ? endpoints.get(endpointId) : endpoints.defaultEndpointOrEnsure();
    if (ep == null) {
      return Map.of(
          "ok",
          false,
          "timestamp",
          Instant.now().toString(),
          "error",
          "No clamd endpoints are configured.");
    }

    CacheEntry entry;
    synchronized (cache) {
      entry = cache.computeIfAbsent(EndpointKey.of(ep), ignored -> new CacheEntry());
    }
    if (fresh(entry)) return entry.snapshot;

    // A slow/unresponsive clamd should not make every returning viewer wait on it:
    // serve the previous snapshot while exactly one thread refreshes it.
    if (!entry.refresh.tryLock()) {
      Map<String, Object> previous = entry.snapshot;
      if (previous != null) return previous;
      // First cold request: wait for the single in-flight refresh; no extra clamd calls.
      entry.refresh.lock();
      try {
        return entry.snapshot != null ? entry.snapshot : unavailable(ep);
      } finally {
        entry.refresh.unlock();
      }
    }
    try {
      if (fresh(entry)) return entry.snapshot;
      Map<String, Object> value = query(ep);
      // The map is complete before publishing and cannot be modified by a controller.
      entry.snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(value));
      entry.refreshedAtNanos = System.nanoTime();
      return entry.snapshot;
    } finally {
      entry.refresh.unlock();
    }
  }

  private Map<String, Object> unavailable(ClamdEndpoint ep) {
    return Map.of(
        "ok",
        false,
        "timestamp",
        Instant.now().toString(),
        "endpoint",
        endpointInfo(ep),
        "error",
        "ClamD snapshot is unavailable.");
  }

  private Map<String, Object> query(ClamdEndpoint ep) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", false);
    result.put("timestamp", Instant.now().toString());
    result.put("endpoint", endpointInfo(ep));

    try {
      // STATS establishes liveness; PING was an unnecessary third connection.
      // The probe's existing shared deadline still bounds VERSION + STATS.
      ClamdClient client = clientProvider.probeFor(ep);
      String version = safe(client.version());
      String stats = safe(client.stats());
      result.put("ok", true);
      result.put("version", version);
      result.put("versionParsed", parseVersion(version));
      result.put("stats", stats);
      result.put("parsed", parseStats(stats));
    } catch (Exception e) {
      log.warn("ClamD STATS probe failed for endpoint {}", ep.getId(), e);
      result.put("error", "Unable to retrieve ClamD metrics. Check server logs.");
    }
    return result;
  }

  public Map<String, Object> parseStats(String stats) {
    Map<String, Object> parsed = new LinkedHashMap<>();
    Map<String, Object> threads = new LinkedHashMap<>();
    Map<String, Object> queue = new LinkedHashMap<>();
    Map<String, Object> mem = new LinkedHashMap<>();
    List<Map<String, String>> commands = new ArrayList<>();
    List<String> unparsed = new ArrayList<>();
    boolean missingQueueCapacity = false;

    if (stats == null || stats.isBlank()) {
      parsed.put("threads", threads);
      parsed.put("queue", queue);
      parsed.put("memory", mem);
      parsed.put("commands", commands);
      parsed.put("unparsed", unparsed);
      return parsed;
    }

    for (String rawLine : stats.split("\\R")) {
      String line = rawLine == null ? "" : rawLine.trim();
      if (line.isEmpty() || line.equalsIgnoreCase("END")) {
        continue;
      }

      Matcher matcher = THREADS_PATTERN.matcher(line);
      if (matcher.matches()) {
        int live = intValue(matcher.group(1));
        int idle = intValue(matcher.group(2));
        int max = intValue(matcher.group(3));
        addCount(threads, "live", live);
        addCount(threads, "idle", idle);
        addCount(threads, "busy", Math.max(0, live - idle));
        addCount(threads, "max", max);
        if (matcher.group(4) != null) {
          threads.put("idleTimeout", intValue(matcher.group(4)));
        }
        continue;
      }

      matcher = QUEUE_PATTERN.matcher(line);
      if (matcher.matches()) {
        addCount(queue, "items", intValue(matcher.group(1)));
        if (matcher.group(2) != null && !missingQueueCapacity)
          addCount(queue, "max", intValue(matcher.group(2)));
        else {
          // Native STATS provides a queue count, not a queue capacity.
          missingQueueCapacity = true;
          queue.remove("max");
        }
        continue;
      }

      matcher = POOLS_PATTERN.matcher(line);
      if (matcher.matches()) {
        parsed.put("pools", intValue(matcher.group(1)));
        continue;
      }

      matcher = STATS_PATTERN.matcher(line);
      if (matcher.matches()) {
        parsed.put("statsWindow", matcher.group(1).trim());
        continue;
      }

      if (line.regionMatches(true, 0, "STATE:", 0, 6)) {
        parsed.put("state", line.substring(6).trim());
        continue;
      }

      if (line.regionMatches(true, 0, "MEMSTATS:", 0, 9)) {
        parseKeyValueLine(line.substring(9).trim(), mem);
        continue;
      }

      if (looksLikeCommandLine(line)) {
        commands.add(parseCommandLine(line));
      } else {
        unparsed.add(line);
      }
    }

    parsed.put("threads", threads);
    parsed.put("queue", queue);
    parsed.put("memory", mem);
    parsed.put("commands", commands);
    parsed.put("unparsed", unparsed);
    return parsed;
  }

  private Map<String, String> parseVersion(String version) {
    Map<String, String> parsed = new LinkedHashMap<>();
    parsed.put("raw", safe(version));
    if (version == null || version.isBlank()) {
      return parsed;
    }

    String[] parts = version.split("/", 3);
    if (parts.length > 0) parsed.put("clamav", parts[0].trim());
    if (parts.length > 1) parsed.put("dbVersion", parts[1].trim());
    if (parts.length > 2) parsed.put("dbTime", parts[2].trim());
    return parsed;
  }

  private Map<String, Object> endpointInfo(ClamdEndpoint ep) {
    Map<String, Object> endpoint = new LinkedHashMap<>();
    endpoint.put("id", ep.getId());
    endpoint.put("name", ep.getName());
    endpoint.put("host", ep.getHost());
    endpoint.put("port", ep.getPort());
    endpoint.put("platform", ep.getPlatform() != null ? ep.getPlatform().name() : "");
    endpoint.put("enabled", ep.isEnabled());
    return endpoint;
  }

  private void parseKeyValueLine(String line, Map<String, Object> target) {
    Matcher matcher = KEY_VALUE_PATTERN.matcher(line);
    while (matcher.find()) {
      target.put(matcher.group(1), matcher.group(2));
    }
  }

  private boolean looksLikeCommandLine(String line) {
    String upper = line.toUpperCase();
    return upper.startsWith("SCAN")
        || upper.startsWith("MULTISCAN")
        || upper.startsWith("MULTISCANFILE")
        || upper.startsWith("INSTREAM")
        || upper.startsWith("CONTSCAN")
        || upper.startsWith("FILDES")
        || upper.startsWith("COMMAND");
  }

  private Map<String, String> parseCommandLine(String line) {
    Map<String, String> command = new LinkedHashMap<>();
    String[] parts = line.trim().split("\\s+", 3);
    command.put("command", parts.length > 0 ? parts[0] : "");
    command.put("queuedSince", parts.length > 1 ? parts[1] : "");
    command.put("file", parts.length > 2 ? parts[2] : "");
    command.put("raw", line);
    return command;
  }

  private static void addCount(Map<String, Object> values, String key, int count) {
    values.merge(
        key, count, (a, b) -> Math.addExact(((Number) a).intValue(), ((Number) b).intValue()));
  }

  private int intValue(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Exception e) {
      return 0;
    }
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
