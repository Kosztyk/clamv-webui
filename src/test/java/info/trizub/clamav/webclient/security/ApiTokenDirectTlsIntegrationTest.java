package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** No strategy override: verify the defaults packaged by a normal Docker/JAR build. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
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

    @Test void spoofedXForwardedHeadersCannotChangeClientIpOrEnableTlsByDefault() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "X-Forwarded-For", "1.2.3.4", "X-Forwarded-Proto", "https");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
        assertThat(response.headers().firstValue("X-Test-Remote-Addr")).contains("127.0.0.1");
        assertThat(response.headers().firstValue("X-Test-Secure")).contains("false");
        assertThat(response.headers().firstValue("X-Test-Scheme")).contains("http");
    }

    @Test void spoofedRfcForwardedCannotChangeClientIpOrEnableTlsByDefault() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "Forwarded", "for=192.0.2.10;proto=https;host=clamav.example.test");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
        assertThat(response.headers().firstValue("X-Test-Remote-Addr")).contains("127.0.0.1");
        assertThat(response.headers().firstValue("X-Test-Secure")).contains("false");
    }
}
