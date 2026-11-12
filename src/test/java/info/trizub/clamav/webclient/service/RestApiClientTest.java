package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import info.trizub.clamav.webclient.config.RestApiConfig;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class RestApiClientTest {
    private HttpServer server;
    private RestApiClient client;
    private final AtomicReference<String> header = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"success\":true,\"data\":{\"jobs\":[],\"total\":0}}";
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            body.set(exchange.getRequestBody().readAllBytes());
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        client = new RestApiClient(new RestApiConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-private-key", "FILES", 2, 2, 100), new ObjectMapper());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void authenticatesAndEncodesFilters() {
        assertThat(client.get("jobs", Map.of("request_id", "a & b")).path("total").asInt()).isZero();
        assertThat(header.get()).isEqualTo("test-private-key");
        assertThat(query.get()).isEqualTo("request_id=a+%26+b");
    }
    @Test void streamsMultipartAndSanitizesFilenameHeaders() {
        byte[] payload = new byte[]{0,1,2,(byte)255,10,13,42};
        var file = new MockMultipartFile("files", "evil\"\r\nInjected: yes.bin", "application/octet-stream", payload);
        client.upload(List.of(file), "notify", "webgui-request", 100);
        String text = new String(body.get(), StandardCharsets.ISO_8859_1);
        assertThat(text).contains("name=\"FILES\"", "name=\"request_id\"", "webgui-request", "name=\"webhook_id\"", "notify");
        assertThat(text).doesNotContain("\r\nInjected:");
        assertThat(text).contains(new String(payload, StandardCharsets.ISO_8859_1));
    }
    @Test void checksUploadLimitBeforeSending() {
        assertThatThrownBy(()->client.upload(List.of(new MockMultipartFile("files","test", "text/plain",new byte[11])), "", "req", 10))
                .isInstanceOf(RestApiClient.ApiException.class).hasMessageContaining("upload limit");
        assertThat(body.get()).isNull();
    }
    @Test void doesNotLeakUpstreamErrorsOrCredentials() {
        status=401;response="test-private-key https://secret-webhook.invalid";
        assertThatThrownBy(()->client.get("stats")).hasMessageContaining("authentication").hasMessageNotContaining("test-private-key");
        status=200;response="<html>old API</html>";
        assertThatThrownBy(()->client.get("stats")).isInstanceOf(RestApiClient.ApiException.class);
    }
    @Test void rejectsMisconfiguredUrlAndPathTraversal() {
        assertThatThrownBy(()->new RestApiConfig("http://user:password@localhost", "", "FILES", 1, 1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->client.retry("../stats")).isInstanceOf(RestApiClient.ApiException.class);
    }
}
