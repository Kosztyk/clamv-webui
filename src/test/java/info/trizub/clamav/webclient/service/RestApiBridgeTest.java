package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class RestApiBridgeTest {
  @Test
  void forwardsStatsAndStreamsMultiFileUploadWithoutReturningTheKeyToTheClient() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> capturedBody = new AtomicReference<>();
    AtomicReference<String> capturedKey = new AtomicReference<>();
    server.createContext(
        "/api/v1/stats",
        exchange -> {
          capturedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
          byte[] bytes = "{\"totals\":{\"files_scanned\":5}}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.createContext(
        "/api/v1/asyncscan",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = "{\"jobId\":\"mock-123\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(202, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      RestApiBridge client =
          new RestApiBridge(
              new ObjectMapper(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "secret-not-for-browser",
              "x-api-key",
              "FILES",
              5,
              10,
              100,
              "/api/v1/stats",
              "/api/v1/jobs",
              "/api/v1/history",
              "/api/v1/webhooks",
              "/api/v1/asyncscan",
              "/api/v1/scan-history",
              "webhookId");
      var stats = client.read("summary", Map.of());
      assertThat(stats.status()).isEqualTo(200);
      assertThat(stats.json().at("/totals/files_scanned").asInt()).isEqualTo(5);
      assertThat(capturedKey.get()).isEqualTo("secret-not-for-browser");
      assertThat(stats.json().toString()).doesNotContain("secret-not-for-browser");
      var scan =
          client.upload(
              List.of(
                  new MockMultipartFile("files", "one.txt", "text/plain", "one".getBytes()),
                  new MockMultipartFile("files", "two.txt", "text/plain", "two".getBytes())),
              "abc");
      assertThat(scan.status()).isEqualTo(202);
      assertThat(capturedBody.get())
          .contains("name=\"FILES\"; filename=\"one.txt\"")
          .contains("name=\"FILES\"; filename=\"two.txt\"")
          .contains("name=\"webhookId\"")
          .contains("abc");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void keepsRawPathAndEncodesQueryValuesOnTheConfiguredOrigin() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<URI> target = new AtomicReference<>();
    server.createContext(
        "/",
        exchange -> {
          target.set(exchange.getRequestURI());
          exchange.sendResponseHeaders(200, 2);
          try (var out = exchange.getResponseBody()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      RestApiBridge bridge = fixture(server, "/tenant%2Fblue");
      var response = bridge.read("jobs", Map.of("search", "//127.0.0.1:1/path?x=1&extra=#item"));
      assertThat(response.status()).isEqualTo(200);
      assertThat(target.get().getRawPath()).isEqualTo("/tenant%2Fblue/api/v1/jobs");
      assertThat(target.get().getRawQuery())
          .isEqualTo("search=%2F%2F127.0.0.1%3A1%2Fpath%3Fx%3D1%26extra%3D%23item");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsOversizedDeclaredAndChunkedUpstreamResponsesWith502() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/jobs",
        exchange -> {
          exchange.sendResponseHeaders(200, RestApiBridge.MAX_RESPONSE_BYTES + 1L);
          exchange.close();
        });
    server.createContext(
        "/api/v1/history",
        exchange -> {
          exchange.sendResponseHeaders(200, 0); // Chunked: no Content-Length.
          try (var out = exchange.getResponseBody()) {
            byte[] chunk = new byte[8192];
            for (int i = 0; i < (RestApiBridge.MAX_RESPONSE_BYTES / chunk.length) + 2; i++) {
              out.write(chunk);
            }
          } catch (java.io.IOException closedByClient) {
            /* Bounded reader closed early. */
          }
        });
    server.start();
    try {
      RestApiBridge bridge = fixture(server);
      assertThatThrownBy(() -> bridge.read("jobs", Map.of()))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
      assertThatThrownBy(() -> bridge.read("history", Map.of()))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void upstreamErrorBodyNeverReachesBrowser() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/jobs",
        exchange -> {
          byte[] body =
              "{\"secret\":\"UPSTREAM_TOKEN_SECRET\",\"stack\":\"internal details\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      var response = fixture(server).read("jobs", Map.of());
      assertThat(response.status()).isEqualTo(502);
      assertThat(response.json().toString())
          .doesNotContain("UPSTREAM_TOKEN_SECRET", "internal details");
      assertThat(response.json().get("error").asText()).isEqualTo("UPSTREAM_ERROR");
    } finally {
      server.stop(0);
    }
  }

  private static RestApiBridge fixture(HttpServer server) {
    return fixture(server, "");
  }

  private static RestApiBridge fixture(HttpServer server, String basePath) {
    return new RestApiBridge(
        new ObjectMapper(),
        "http://127.0.0.1:" + server.getAddress().getPort() + basePath,
        "",
        "x-api-key",
        "FILES",
        5,
        10,
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
  void rejectsInvalidIdsBeforeContactingExternalApi() {
    RestApiBridge api =
        new RestApiBridge(
            new ObjectMapper(),
            "http://127.0.0.1:1",
            "",
            "x-api-key",
            "FILES",
            1,
            1,
            100,
            "/api/v1/stats",
            "/api/v1/jobs",
            "/api/v1/history",
            "/api/v1/webhooks",
            "/api/v1/asyncscan",
            "/api/v1/scan-history",
            "webhookId");
    assertThatThrownBy(() -> api.detail("../../admin")).isInstanceOf(ResponseStatusException.class);
  }
}
