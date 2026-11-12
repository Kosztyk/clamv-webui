package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.service.EndpointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import xyz.capybara.clamav.Platform;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:clamd-probe-timeouts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "clamav.bootstrap.admin.password=ProbeTimeoutLocalAdmin456789",
        "clamav.client.connect-timeout=100ms", "clamav.client.command-timeout=200ms", "clamav.client.scan-timeout=5s",
        "clamav.metrics.clamd-initial-delay-ms=3600000", "clamav.config.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class ClamdProbeTimeoutIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EndpointService endpoints;

    @Test void apiMainAndV1HealthReturnPromptlyWhenTheRealTcpPeerNeverAnswers() throws Exception {
        var workers = Executors.newCachedThreadPool();
        var closed = new CountDownLatch(3);
        try (var server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(4000);
            var accepting = workers.submit(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        var socket = server.accept(); socket.setSoTimeout(4000);
                        workers.submit(() -> {
                            try (socket; var input = socket.getInputStream()) {
                                int value; while ((value = input.read()) > 0) { }
                                assertThat(input.read()).isEqualTo(-1); closed.countDown();
                            } catch (Exception ex) { throw new RuntimeException(ex); }
                        });
                    }
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var endpoint = endpoints.create("silent timeout fixture", "127.0.0.1", server.getLocalPort(), Platform.UNIX, true);
            long start = System.nanoTime();
            mvc.perform(get("/api/clamdtop").param("endpointId", endpoint.getId().toString()).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.error").value("ClamD operation timed out."));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            start = System.nanoTime();
            mvc.perform(get("/api/v1/health/clamd").param("endpointId", endpoint.getId().toString()).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.connectivity").value("DOWN"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            start = System.nanoTime();
            mvc.perform(get("/main").param("endpointId", endpoint.getId().toString()).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(model().attribute("pingOk", false)).andExpect(model().attribute("error", "ClamD operation timed out."));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue(); accepting.get(1, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    @Test void aNonRoutableEndpointAlsoReturnsABoundedFailure() throws Exception {
        var endpoint = endpoints.create("TEST-NET unreachable", "203.0.113.1", 3310, Platform.UNIX, true);
        long start = System.nanoTime();
        mvc.perform(get("/api/clamdtop").param("endpointId", endpoint.getId().toString()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
}
