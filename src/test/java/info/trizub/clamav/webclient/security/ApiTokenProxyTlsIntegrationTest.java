package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Proxy support requires explicit native mode and an allowlisted socket peer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=127.0.0.1/32",
    "spring.datasource.url=jdbc:h2:mem:proxy-tls-enabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.bootstrap.admin.password=ProxyTlsIntegrationTestOnlyPassword123"
})
class ApiTokenProxyTlsIntegrationTest extends ProxyBearerTestSupport {
    @Test void forwardedHttpsAcceptsAValidBearerToken() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "X-Forwarded-For", "192.0.2.10", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "clamav.example.test", "X-Forwarded-Port", "443");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.headers().firstValue("X-Test-Remote-Addr")).contains("192.0.2.10");
        assertThat(response.headers().firstValue("X-Test-Secure")).contains("true");
    }

    @Test void rfcForwardedAloneDoesNotEnableNativeProxyTls() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(),
            "Forwarded", "for=192.0.2.10;proto=https;host=clamav.example.test");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
    }

    @Test void plainHttpStillRejectsBearerCredentials() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required", "allowlist of trusted proxy addresses");
    }

    @Test void forwardedHttpStillRejectsBearerCredentials() throws Exception {
        var response = request("/api/v1/health/live", token("health:read").secret(), "X-Forwarded-Proto", "http");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("tls_required");
    }

    @Test void forwardedHttpsStillRequiresAValidToken() throws Exception {
        var response = request("/api/v1/health/live", "invalid-token", "X-Forwarded-Proto", "https");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_token");
    }

    @Test void forwardedHttpsStillRequiresTheRequestedScope() throws Exception {
        var response = request("/api/v1/jobs", token("health:read").secret(), "X-Forwarded-Proto", "https");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("tls_required");
    }

    @Test void revocationRemainsImmediateBehindAProxy() throws Exception {
        var created = token("health:read");
        assertThat(request("/api/v1/health/live", created.secret(), "X-Forwarded-Proto", "https").statusCode()).isEqualTo(200);
        assertThat(credentials.revoke(created.credential().getId())).isTrue();
        assertThat(request("/api/v1/health/live", created.secret(), "X-Forwarded-Proto", "https").statusCode()).isEqualTo(401);
    }
}
