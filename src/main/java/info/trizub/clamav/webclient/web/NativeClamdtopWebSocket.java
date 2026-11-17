package info.trizub.clamav.webclient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Real ncurses clamdtop, not a STATS imitation. Never takes commands or hosts from the client. */
@Component
public final class NativeClamdtopWebSocket extends TextWebSocketHandler {
  private static final Logger log = LoggerFactory.getLogger(NativeClamdtopWebSocket.class);
  private static final int MAX_CONNECTIONS = 8;
  private static final int MAX_PER_USER = 2;
  private final EndpointService endpoints;
  private final ObjectMapper mapper;
  private final ProcessStarter processStarter;
  private final ClamdtopSessionBudget budget =
      new ClamdtopSessionBudget(MAX_CONNECTIONS, MAX_PER_USER);
  private final Map<String, MonitorSession> running = new ConcurrentHashMap<>();
  private final long idleTimeoutNanos;
  private final LongSupplier nanoTime;

  @FunctionalInterface
  interface ProcessStarter {
    Process start(ProcessBuilder builder) throws IOException;
  }

  @Autowired
  public NativeClamdtopWebSocket(
      EndpointService endpoints,
      ObjectMapper mapper,
      @Value("${clamav.clamdtop.idle-timeout-seconds:180}") long idleTimeoutSeconds) {
    this(endpoints, mapper, idleTimeoutSeconds, ProcessBuilder::start);
  }

  // Package-private injection allows process start and all lifecycle paths to be exercised without
  // clamdtop.
  NativeClamdtopWebSocket(
      EndpointService endpoints,
      ObjectMapper mapper,
      long idleTimeoutSeconds,
      ProcessStarter processStarter) {
    this(endpoints, mapper, idleTimeoutSeconds, processStarter, System::nanoTime);
  }

  NativeClamdtopWebSocket(
      EndpointService endpoints,
      ObjectMapper mapper,
      long idleTimeoutSeconds,
      ProcessStarter processStarter,
      LongSupplier nanoTime) {
    if (idleTimeoutSeconds < 1 || idleTimeoutSeconds > 86_400) {
      throw new IllegalArgumentException("clamav.clamdtop.idle-timeout-seconds must be 1..86400");
    }
    this.endpoints = endpoints;
    this.mapper = mapper;
    this.processStarter = processStarter;
    this.nanoTime = nanoTime;
    this.idleTimeoutNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    if (!(session.getPrincipal() instanceof Authentication auth)
        || !auth.isAuthenticated()
        || auth.getName() == null
        || auth.getName().isBlank()) {
      reject(session, "Authentication required");
      return;
    }
    String id = session.getId();
    ClamdtopSessionBudget.Result admission = budget.reserve(id, auth.getName());
    if (admission != ClamdtopSessionBudget.Result.GRANTED) {
      reject(
          session,
          switch (admission) {
            case USER_LIMIT -> "Maximum 2 clamdtop sessions per user";
            case GLOBAL_LIMIT -> "All 8 clamdtop monitor slots are in use";
            case DUPLICATE -> "Clamdtop session is already registered";
            default -> "Clamdtop session unavailable";
          });
      return;
    }

    MonitorSession monitor = new MonitorSession(session, nanoTime.getAsLong());
    if (running.putIfAbsent(id, monitor) != null) {
      budget.release(id);
      reject(session, "Clamdtop session is already registered");
      return;
    }
    boolean launched = false;
    try {
      if (!session.isOpen()) return;
      URI uri = session.getUri();
      Map<String, String> query = parameters(uri == null ? null : uri.getRawQuery());
      int cols = bounded(query.get("cols"), 150, 30, 400);
      int rows = bounded(query.get("rows"), 38, 16, 160);
      ClamdEndpoint ep;
      try {
        String endpointId = query.get("endpointId");
        ep =
            endpointId == null || endpointId.isBlank()
                ? endpoints.defaultEndpointOrEnsure()
                : endpoints.get(Long.parseLong(endpointId));
      } catch (Exception ex) {
        reject(session, "Unknown clamd endpoint");
        return;
      }
      if (ep == null || !ep.isEnabled()) {
        reject(session, "Endpoint is not enabled");
        return;
      }
      String spec;
      try {
        spec = connectionSpec(ep);
      } catch (IllegalArgumentException ex) {
        reject(session, "Invalid configured clamd host or port");
        return;
      }

      ProcessBuilder builder = newClamdtopProcessBuilder(cols, rows, spec);
      Process process;
      // Serialize process launch with close/reaper. A slot is never freed while a launch is in
      // flight.
      synchronized (monitor) {
        if (monitor.stopped || !session.isOpen()) return;
        process = processStarter.start(builder);
        monitor.process = process;
      }
      send(session, "status", "Connected to clamdtop (" + spec + ")");
      Thread reader = new Thread(() -> relay(session, process), "clamdtop-pty-" + id);
      reader.setDaemon(true);
      reader.start();
      launched = true;
    } catch (Exception ex) {
      // Log only the error class, never its potentially sensitive message or subprocess
      // environment.
      log.warn("Native ClamdTop session startup failed ({})", ex.getClass().getSimpleName());
      // Do not reflect process-launch exceptions to clients: they may contain deployment paths.
      try {
        reject(session, "Could not start clamdtop. Check the server logs and native dependencies");
      } catch (Exception deliveryFailure) {
        // Cleanup is mandatory even if client delivery fails.
        log.debug("Failed to deliver ClamdTop startup error to session {}", session.getId());
      }
    } finally {
      // A failed status send or failed reader startup must not strand a started process.
      if (!launched || !session.isOpen()) cleanup(session.getId(), null);
    }
  }

  /**
   * No inherited JVM secrets, locale, provider tokens or CLAMAV_* variables reach script/clamdtop.
   */
  static ProcessBuilder newClamdtopProcessBuilder(int cols, int rows, String spec) {
    String command =
        "stty cols " + cols + " rows " + rows + "; exec /usr/bin/clamdtop " + shellQuote(spec);
    ProcessBuilder builder =
        new ProcessBuilder("/usr/bin/script", "-q", "-c", command, "/dev/null");
    builder.redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.clear();
    environment.put("TERM", "xterm-256color");
    environment.put("COLUMNS", Integer.toString(cols));
    environment.put("LINES", Integer.toString(rows));
    environment.put("PATH", "/usr/bin:/bin");
    return builder;
  }

  private void relay(WebSocketSession session, Process process) {
    try (InputStream input = process.getInputStream()) {
      byte[] buffer = new byte[8192];
      int len;
      while ((len = input.read(buffer)) != -1 && session.isOpen()) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "data");
        msg.put("base64", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
        write(session, msg.toString());
      }
    } catch (Exception readFailure) {
      // PTY shutdown normally interrupts the reader, so this is not a warning.
      log.debug("ClamdTop PTY reader stopped for session {}", session.getId());
    } finally {
      cleanup(session.getId(), CloseStatus.NORMAL);
    }
  }

  static String connectionSpec(ClamdEndpoint endpoint) {
    String host = endpoint.getHost();
    if (host == null || host.isBlank() || host.length() > 255 || host.chars().anyMatch(c -> c < 32))
      throw new IllegalArgumentException("Invalid endpoint");
    if (host.startsWith("/")) return host;
    if (!host.matches("[A-Za-z0-9_:\\[][A-Za-z0-9_.:%\\-\\[\\]]{0,254}")
        || endpoint.getPort() < 1
        || endpoint.getPort() > 65535) throw new IllegalArgumentException("Invalid endpoint");
    return (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
        + ":"
        + endpoint.getPort();
  }

  static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    MonitorSession monitor = running.get(session.getId());
    if (monitor == null || message.getPayloadLength() > 4096) return;
    JsonNode json;
    try {
      json = mapper.readTree(message.getPayload());
    } catch (Exception ex) {
      return;
    }
    if (json == null || !json.isObject()) return;
    String type = json.path("type").asText();
    // A passive monitor sends heartbeats. Terminal output is NOT a proxy for client activity.
    if ("heartbeat".equals(type)) {
      monitor.lastClientActivityNanos = nanoTime.getAsLong();
      return;
    }
    if (!"input".equals(type)) return;
    String input = json.path("data").asText("");
    if (input.length() > 128) return;
    Process process = monitor.process;
    if (process == null || !process.isAlive()) return;
    monitor.lastClientActivityNanos = nanoTime.getAsLong();
    try {
      process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().flush();
    } catch (IOException ex) {
      cleanup(session.getId(), CloseStatus.SERVER_ERROR);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    cleanup(session.getId(), null);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    cleanup(session.getId(), CloseStatus.SERVER_ERROR);
  }

  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  public void reapIdleSessions() {
    long now = nanoTime.getAsLong();
    running.forEach(
        (id, monitor) -> {
          if (!monitor.session.isOpen()
              || now - monitor.lastClientActivityNanos >= idleTimeoutNanos) {
            cleanup(id, CloseStatus.POLICY_VIOLATION.withReason("Clamdtop idle timeout"));
          }
        });
  }

  @PreDestroy
  public void shutdown() {
    running.forEach(
        (id, monitor) -> {
          cleanup(id, CloseStatus.GOING_AWAY);
        });
  }

  private void cleanup(String sessionId, CloseStatus closeStatus) {
    // Exactly one closer owns the PTY and socket after removal. A dying reader
    // must not race the idle reaper and overwrite its close reason.
    MonitorSession monitor = running.remove(sessionId);
    if (monitor == null) return;
    Process process;
    synchronized (monitor) {
      monitor.stopped = true;
      process = monitor.process;
    }
    try {
      if (process != null) stopProcess(process);
    } finally {
      budget.release(sessionId);
      if (closeStatus != null) closeQuietly(monitor.session, closeStatus);
    }
  }

  private static void stopProcess(Process process) {
    // script(1) forks the PTY command; terminate descendants so the native child cannot survive.
    java.util.List<ProcessHandle> children;
    try {
      children = process.toHandle().descendants().toList();
    } catch (UnsupportedOperationException | IllegalStateException ex) {
      children = java.util.List.of();
    }
    children.forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
      if (process.isAlive()) process.waitFor(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    } finally {
      children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
  }

  private void reject(WebSocketSession session, String message) throws Exception {
    try {
      send(session, "error", message);
    } finally {
      closeQuietly(session, CloseStatus.POLICY_VIOLATION);
    }
  }

  private void write(WebSocketSession session, String value) throws IOException {
    synchronized (session) {
      if (session.isOpen()) session.sendMessage(new TextMessage(value));
    }
  }

  private void send(WebSocketSession session, String type, String value) throws IOException {
    ObjectNode msg = mapper.createObjectNode();
    msg.put("type", type);
    msg.put("message", value);
    write(session, msg.toString());
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) session.close(status);
    } catch (Exception ignored) {
      // Cleanup is independent of the client transport state.
    }
  }

  private static int bounded(String raw, int fallback, int min, int max) {
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(raw)));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static Map<String, String> parameters(String value) {
    Map<String, String> result = new java.util.HashMap<>();
    if (value == null) return result;
    for (String item : value.split("&")) {
      String[] parts = item.split("=", 2);
      if (parts.length == 2) {
        result.put(
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  private static final class MonitorSession {
    final WebSocketSession session;
    volatile long lastClientActivityNanos;
    volatile Process process;
    boolean stopped; // guarded by synchronized(this)

    MonitorSession(WebSocketSession session, long startedNanos) {
      this.session = session;
      lastClientActivityNanos = startedNanos;
    }
  }
}
