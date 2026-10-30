package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestApiBridgeTest {
    @Test
    void forwardsStatsAndStreamsMultiFileUploadWithoutReturningTheKeyToTheClient() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> capturedBody=new AtomicReference<>();
        AtomicReference<String> capturedKey=new AtomicReference<>();
        server.createContext("/api/v1/stats", exchange -> {
            capturedKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] bytes="{\"totals\":{\"files_scanned\":5}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.createContext("/api/v1/asyncscan", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes="{\"jobId\":\"mock-123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(202,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            RestApiBridge client=new RestApiBridge(new ObjectMapper(),
                "http://127.0.0.1:"+server.getAddress().getPort(),"secret-not-for-browser","x-api-key","FILES",
                5,10,100, "/api/v1/stats","/api/v1/jobs","/api/v1/history","/api/v1/webhooks",
                "/api/v1/asyncscan","/api/v1/scan-history","webhookId");
            var stats=client.read("summary",Map.of());
            assertThat(stats.status()).isEqualTo(200);
            assertThat(stats.json().at("/totals/files_scanned").asInt()).isEqualTo(5);
            assertThat(capturedKey.get()).isEqualTo("secret-not-for-browser");
            assertThat(stats.json().toString()).doesNotContain("secret-not-for-browser");
            var scan=client.upload(List.of(new MockMultipartFile("files","one.txt","text/plain","one".getBytes()),
                new MockMultipartFile("files","two.txt","text/plain","two".getBytes())),"abc");
            assertThat(scan.status()).isEqualTo(202);
            assertThat(capturedBody.get()).contains("name=\"FILES\"; filename=\"one.txt\"")
                .contains("name=\"FILES\"; filename=\"two.txt\"")
                .contains("name=\"webhookId\"").contains("abc");
        } finally { server.stop(0); }
    }

    @Test
    void rejectsInvalidIdsBeforeContactingExternalApi() {
        RestApiBridge api=new RestApiBridge(new ObjectMapper(),"http://127.0.0.1:1","","x-api-key","FILES",
            1,1,100,"/api/v1/stats","/api/v1/jobs","/api/v1/history","/api/v1/webhooks",
            "/api/v1/asyncscan","/api/v1/scan-history","webhookId");
        assertThatThrownBy(()->api.detail("../../admin")).isInstanceOf(ResponseStatusException.class);
    }
}
