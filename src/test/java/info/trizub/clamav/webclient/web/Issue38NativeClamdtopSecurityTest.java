package info.trizub.clamav.webclient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.capybara.clamav.Platform;

@EnabledOnOs({OS.LINUX, OS.MAC})
class Issue38NativeClamdtopSecurityTest {
  private static final Set<String> ALLOWED = Set.of("TERM", "COLUMNS", "LINES", "PATH");

  @Test
  void spawnedProcessCannotInheritApplicationSecrets() throws Exception {
    var builder = NativeClamdtopWebSocket.newClamdtopProcessBuilder(132, 40, "localhost:3310");
    assertThat(builder.environment().keySet()).containsExactlyInAnyOrderElementsOf(ALLOWED);
    assertThat(builder.environment())
        .containsEntry("TERM", "xterm-256color")
        .containsEntry("COLUMNS", "132")
        .containsEntry("LINES", "40")
        .containsEntry("PATH", "/usr/bin:/bin");
    // Run a real child and inspect its actual environment, not only the ProcessBuilder map.
    builder.command("/usr/bin/env");
    var process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).isZero();
    Set<String> keys =
        output
            .lines()
            .filter(s -> s.contains("="))
            .map(s -> s.substring(0, s.indexOf('=')))
            .collect(java.util.stream.Collectors.toSet());
    assertThat(keys).containsExactlyInAnyOrderElementsOf(ALLOWED);
    assertThat(output)
        .doesNotContain(
            "CLAMAV_AUTH_SETTINGS_KEY",
            "CLAMAV_REST_API_KEY",
            "LDAP_BIND_PASSWORD",
            "OIDC_CLIENT_SECRET",
            "JAVA_TOOL_OPTIONS");
  }

  @Test
  void webSocketEnforcesTwoPerUserAndReleasesSlotOnDisconnect() throws Exception {
    EndpointService endpoints = mock(EndpointService.class);
    when(endpoints.defaultEndpointOrEnsure()).thenReturn(endpoint());
    AtomicInteger launched = new AtomicInteger();
    var handler =
        new NativeClamdtopWebSocket(
            endpoints,
            new ObjectMapper(),
            180,
            pb -> {
              assertThat(pb.environment().keySet()).containsExactlyInAnyOrderElementsOf(ALLOWED);
              launched.incrementAndGet();
              pb.command("/bin/cat");
              return pb.start();
            });
    var first = socket("monitor-1", "viewer");
    var second = socket("monitor-2", "viewer");
    var third = socket("monitor-3", "viewer");
    var other = socket("monitor-4", "operator");
    try {
      handler.afterConnectionEstablished(first);
      handler.afterConnectionEstablished(second);
      handler.afterConnectionEstablished(third);
      assertThat(launched).hasValue(2);
      verify(third).close(any(CloseStatus.class));
      handler.afterConnectionEstablished(other);
      assertThat(launched).hasValue(3); // one VIEWER cannot monopolize all 8 slots
      handler.afterConnectionClosed(first, CloseStatus.NORMAL);
      var fourth = socket("monitor-5", "viewer");
      handler.afterConnectionEstablished(fourth);
      assertThat(launched).hasValue(4);
      handler.afterConnectionClosed(fourth, CloseStatus.NORMAL);
    } finally {
      handler.shutdown();
    }
  }

  @Test
  void failedNativeLaunchDoesNotHoldAnySessionSlots() throws Exception {
    EndpointService endpoints = mock(EndpointService.class);
    when(endpoints.defaultEndpointOrEnsure()).thenReturn(endpoint());
    var handler =
        new NativeClamdtopWebSocket(
            endpoints,
            new ObjectMapper(),
            180,
            pb -> {
              throw new java.io.IOException("test-only process failure");
            });
    try {
      for (int i = 0; i < 12; i++) {
        var attempt = socket("failed-" + i, "viewer");
        handler.afterConnectionEstablished(attempt);
        verify(attempt).close(any(CloseStatus.class));
      }
    } finally {
      handler.shutdown();
    }
  }

  @Test
  void passiveMonitorHeartbeatPreventsExpiryButAbandonedTabsExpire() throws Exception {

    EndpointService endpoints = mock(EndpointService.class);
    when(endpoints.defaultEndpointOrEnsure()).thenReturn(endpoint());
    AtomicLong now = new AtomicLong();
    var handler =
        new NativeClamdtopWebSocket(
            endpoints,
            new ObjectMapper(),
            1,
            pb -> {
              pb.command("/bin/cat");
              return pb.start();
            },
            now::get);
    var viewer = socket("idle-1", "viewer");
    try {
      handler.afterConnectionEstablished(viewer);
      now.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
      handler.handleTextMessage(viewer, new TextMessage("{\"type\":\"heartbeat\"}"));
      now.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
      handler.reapIdleSessions();
      verify(viewer, never()).close(any(CloseStatus.class));
      now.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
      handler.reapIdleSessions();
      verify(viewer).close(CloseStatus.POLICY_VIOLATION.withReason("Clamdtop idle timeout"));
    } finally {
      handler.shutdown();
    }
  }

  private static ClamdEndpoint endpoint() {
    return new ClamdEndpoint("test", "localhost", 3310, Platform.UNIX);
  }

  private static WebSocketSession socket(String id, String username) throws Exception {
    WebSocketSession ws = mock(WebSocketSession.class);
    AtomicBoolean open = new AtomicBoolean(true);
    when(ws.getId()).thenReturn(id);
    when(ws.isOpen()).thenAnswer(invocation -> open.get());
    when(ws.getUri()).thenReturn(URI.create("ws://localhost/ws/clamdtop"));
    when(ws.getPrincipal())
        .thenReturn(
            new UsernamePasswordAuthenticationToken(
                username, "unused", List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    doAnswer(
            invocation -> {
              open.set(false);
              return null;
            })
        .when(ws)
        .close(any(CloseStatus.class));
    return ws;
  }
}
