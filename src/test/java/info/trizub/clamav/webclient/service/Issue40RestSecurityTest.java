package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import info.trizub.clamav.webclient.config.RestApiConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Reproduces issue #40 without contacting any external scanner or requiring credentials. */
class Issue40RestSecurityTest {
  private static RestApiBridge bridge(String url, String key, boolean optIn) {
    return new RestApiBridge(
        new ObjectMapper(),
        url,
        key,
        optIn,
        "bearer",
        "FILES",
        5,
        5,
        100,
        "/api/v1/stats",
        "/api/v1/jobs",
        "/api/v1/history",
        "/api/v1/webhooks",
        "/api/v1/asyncscan",
        "/api/v1/scan-history",
        "webhookId");
  }

  @Test
  void authenticatedRemoteHttpFailsFastInBothClientsWithoutOptIn() {
    for (String url :
        new String[] {
          "http://scanner.internal:3000",
          "http://192.0.2.144:3000",
          "http://127.0.0.1.attacker.test:3000"
        }) {
      assertThatThrownBy(() -> bridge(url, "sensitive-key", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CLAMAV_REST_ALLOW_INSECURE")
          .hasMessageNotContaining("sensitive-key");
      assertThatThrownBy(() -> new RestApiConfig(url, "sensitive-key", "FILES", 5, 5, 100))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CLAMAV_REST_ALLOW_INSECURE");
    }
  }

  @Test
  void allowsAuthenticatedHttpsAndLoopbackAndAnonymousHttpWithoutOptIn() {
    for (String url :
        new String[] {
          "https://scanner.internal:3000",
          "http://localhost:3000",
          "http://127.0.0.1:3000",
          "http://127.17.2.3:3000",
          "http://[::1]:3000"
        }) {
      assertThatCode(() -> bridge(url, "key", false)).doesNotThrowAnyException();
      assertThatCode(() -> new RestApiConfig(url, "key", "FILES", 5, 5, 100))
          .doesNotThrowAnyException();
    }
    assertThatCode(() -> bridge("http://scanner.internal", "", false)).doesNotThrowAnyException();
  }

  @Test
  void explicitOptInPermitsNonLoopbackHttpWithoutLeakingSecretInErrors() {
    assertThatCode(() -> bridge("http://scanner.internal:3000", "key", true))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new RestApiConfig("http://scanner.internal:3000", "key", "FILES", 5, 5, 100, true))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> bridge("http://user:password@scanner.internal:3000", "key", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("password");
  }

  @Test
  void errorAndRedirectResponsesNeverExposeUpstreamBodyOrSecrets() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> capturedAuth = new AtomicReference<>();
    server.createContext(
        "/api/v1/jobs",
        exchange -> {
          capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes =
              "{\"serverPath\":\"/private/scan/file.txt\",\"apiKey\":\"private-key\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Location", "http://attacker.invalid/collect");
          exchange.sendResponseHeaders(502, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    try {
      var response =
          bridge("http://127.0.0.1:" + server.getAddress().getPort(), "private-key", false)
              .read("jobs", Map.of());
      assertThat(response.status()).isEqualTo(502);
      assertThat(response.json().toString())
          .doesNotContain("private-key", "/private/scan", "attacker.invalid");
      assertThat(capturedAuth.get()).isEqualTo("Bearer private-key");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void secondaryClientRejectsOversizedDeclaredAndChunkedResponses() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/jobs",
        exchange -> sendOversizedResponse(exchange, RestApiClient.MAX_RESPONSE_BYTES + 1L));
    server.createContext("/api/v1/stats", exchange -> sendOversizedResponse(exchange, 0));
    server.start();
    try {
      var client =
          new RestApiClient(
              new RestApiConfig(
                  "http://127.0.0.1:" + server.getAddress().getPort(), "key", "FILES", 5, 5, 100),
              new ObjectMapper());
      for (String path : new String[] {"jobs", "stats"})
        assertThatThrownBy(() -> client.get(path))
            .isInstanceOf(RestApiClient.ApiException.class)
            .hasMessageContaining("size limit");
    } finally {
      server.stop(0);
    }
  }

  private static void sendOversizedResponse(HttpExchange exchange, long declaredLength)
      throws IOException {
    exchange.sendResponseHeaders(200, declaredLength);
    try (var output = exchange.getResponseBody()) {
      byte[] bytes = new byte[8192];
      int remaining = RestApiClient.MAX_RESPONSE_BYTES + 1;
      while (remaining > 0) {
        int count = Math.min(bytes.length, remaining);
        output.write(bytes, 0, count);
        remaining -= count;
      }
    } catch (IOException closedEarly) {
      // The client can close the connection once it has detected the size violation.
    } finally {
      exchange.close();
    }
  }
}
