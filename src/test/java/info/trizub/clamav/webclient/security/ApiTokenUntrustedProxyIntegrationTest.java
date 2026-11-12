package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=192.0.2.10/32",
    "spring.datasource.url=jdbc:h2:mem:proxy-tls-untrusted;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.bootstrap.admin.password=ProxyTrustIntegrationTestOnlyPassword123"
})
class ApiTokenUntrustedProxyIntegrationTest extends ProxyBearerTestSupport {
    @Test void unlistedSocketPeerCannotSpoofIpOrHttpsEvenWithNativeEnabled() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "X-Forwarded-For", "1.2.3.4", "X-Forwarded-Proto", "https",
            "Forwarded", "for=1.2.3.4;proto=https");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
        assertThat(response.headers().firstValue("X-Test-Remote-Addr")).contains("127.0.0.1");
        assertThat(response.headers().firstValue("X-Test-Secure")).contains("false");
    }
}
