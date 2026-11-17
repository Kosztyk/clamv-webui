package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdClientProperties;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.Platform;

@Service
public class ClamavClientProvider implements AutoCloseable {
  private final ClamdClientProperties properties;
  private final ClamdAddressResolver resolver;
  // SocketClamdClient holds immutable transport settings, not a live socket.
  // A new socket is opened and closed by every command, even when the wrapper is reused.
  private final ConcurrentHashMap<Long, CachedClient> scanClients = new ConcurrentHashMap<>();

  private record ConnectionKey(
      String host,
      int port,
      Platform platform,
      long connectMillis,
      long commandMillis,
      long scanMillis) {}

  private record CachedClient(ConnectionKey key, ClamdClient client) {}

  public ClamavClientProvider() {
    this(new ClamdClientProperties());
  }

  @Autowired
  public ClamavClientProvider(ClamdClientProperties properties) {
    this.properties = properties;
    this.resolver = new ClamdAddressResolver();
  }

  public ClamdClient clientFor(ClamdEndpoint endpoint) {
    ConnectionKey key = key(endpoint);
    Long id = endpoint.getId();
    // Unsaved endpoint objects have no persistent identity; do not cache them.
    if (id == null) return create(key, 0);
    return scanClients
        .compute(
            id,
            (ignored, previous) ->
                previous != null && previous.key().equals(key)
                    ? previous
                    : new CachedClient(key, create(key, 0)))
        .client();
  }

  /** Invalidate on any endpoint edit/removal, including Configuration Center changes. */
  public void invalidate(Long endpointId) {
    if (endpointId != null) scanClients.remove(endpointId);
  }

  /** One shared deadline for PING + VERSION + STATS, including all DNS/connect/read/write work. */
  public ClamdClient probeFor(ClamdEndpoint endpoint) {
    // A probe carries one deadline spanning VERSION + STATS. Never cache a used deadline.
    return create(
        key(endpoint), SocketClamdClient.deadline(properties.getCommandTimeout().toMillis()));
  }

  private ConnectionKey key(ClamdEndpoint endpoint) {
    if (endpoint == null || endpoint.getHost() == null || endpoint.getHost().isBlank())
      throw new IllegalArgumentException("ClamD endpoint host is required");
    return new ConnectionKey(
        endpoint.getHost().trim(),
        endpoint.getPort(),
        endpoint.getPlatform(),
        properties.getConnectTimeout().toMillis(),
        properties.getCommandTimeout().toMillis(),
        properties.getScanTimeout().toMillis());
  }

  private ClamdClient create(ConnectionKey key, long probeDeadline) {
    if (key.host().startsWith("/"))
      return new UnixClamdClient(
          key.host(), key.connectMillis(), key.commandMillis(), key.scanMillis(), probeDeadline);
    return new TcpClamdClient(
        key.host(),
        key.port(),
        key.platform(),
        key.connectMillis(),
        key.commandMillis(),
        key.scanMillis(),
        probeDeadline,
        resolver);
  }

  @Override
  @PreDestroy
  public void close() {
    scanClients.clear();
    resolver.close();
  }
}
