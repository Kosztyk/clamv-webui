package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Direct-access operators can explicitly disable all forwarded-header trust. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "server.forward-headers-strategy=none",
    "spring.datasource.url=jdbc:h2:mem:proxy-tls-direct-default;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.bootstrap.admin.password=DirectTlsIntegrationTestOnlyPassword123"
})
class ApiTokenDirectTlsIntegrationTest extends ProxyBearerTestSupport {
    @Test void directHttpRejectsBearerCredentials() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
    }

    @Test void spoofedXForwardedProtoDoesNotEnableTlsWhenForwardingIsDisabled() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(), "X-Forwarded-Proto", "https");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
    }

    @Test void spoofedRfcForwardedDoesNotEnableTlsWhenForwardingIsDisabled() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "Forwarded", "for=192.0.2.10;proto=https;host=clamav.example.test");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
    }
}
