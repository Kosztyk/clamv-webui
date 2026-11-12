package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.identity.LoginAttemptService;
import info.trizub.clamav.webclient.service.UserService;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP reaches native proxy handling and the complete Spring Security Basic authentication chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=127.0.0.1/32",
    "spring.datasource.url=jdbc:h2:mem:basic-lockout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.bootstrap.admin.password=BasicLockoutBootstrapOnlyPassword123"
})
@AutoConfigureMockMvc
class BasicAuthenticationLockoutIntegrationTest {
    static final String PASSWORD = "SyntheticBasicTestPassword123";
    @Autowired UserService users;
    @Autowired LoginAttemptService attempts;
    @Autowired ApiCredentialService credentials;
    @Autowired MockMvc mvc;
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    String user() {
        String name = "basic-" + UUID.randomUUID();
        users.createUser(name, PASSWORD, Set.of("ROLE_ADMIN", "ROLE_VIEWER", "ROLE_OPERATOR"), true);
        return name;
    }
    HttpResponse<String> basic(String username, String password, String path, String peer) throws Exception {
        String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return send(path, "Basic " + encoded, peer, true);
    }
    HttpResponse<String> send(String path, String authorization, String peer, boolean https) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).header("X-Forwarded-For", peer);
        if (https) request.header("X-Forwarded-Proto", "https");
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    void fail(String user, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            assertThat(basic(user, "wrong-password", "/api/health", "192.0.2." + (i + 1)).statusCode()).isEqualTo(401);
            if (i < 4) assertThat(attempts.locked("local", user, "198.51.100.10")).isFalse();
        }
    }

    @Test void fiveFailedBasicAttemptsLockTheAccountEvenWithTheCorrectPasswordAndDifferentIpOrRoute() throws Exception {
        String user = user();
        fail(user, 5);
        assertThat(attempts.locked("local", user, "198.51.100.10")).isTrue();
        var locked = basic(user, PASSWORD, "/api/health", "198.51.100.10");
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.body()).contains("login_locked").doesNotContain(PASSWORD);
        assertThat(Long.parseLong(locked.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 900L);
        assertThat(basic(user, PASSWORD, "/dashboard", "203.0.113.10").statusCode()).isEqualTo(429);
        assertThat(basic(user(), PASSWORD, "/api/v1/health/live", "192.0.2.1").statusCode()).isEqualTo(200);
    }

    @Test void successfulBasicAuthenticationResetsTheFailureBudget() throws Exception {
        String user = user();
        fail(user, 4);
        assertThat(basic(user, PASSWORD, "/api/v1/health/live", "198.51.100.10").statusCode()).isEqualTo(200);
        fail(user, 4);
        assertThat(basic(user, PASSWORD, "/api/v1/health/live", "203.0.113.10").statusCode()).isEqualTo(200);
    }

    @Test void localFormAndBasicFailuresShareOneAccountBudget() throws Exception {
        String user = user();
        fail(user, 3);
        for (int i = 0; i < 2; i++) mvc.perform(post("/login").secure(true).with(csrf())
                .param("username", user).param("password", "wrong-password")).andExpect(status().isFound());
        assertThat(basic(user, PASSWORD, "/api/v1/health/live", "203.0.113.10").statusCode()).isEqualTo(429);
        mvc.perform(post("/login").secure(true).with(csrf()).param("username", user).param("password", PASSWORD))
                .andExpect(status().isFound()).andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login?locked"));
        assertThat(attempts.locked("ldap", user, "127.0.0.1")).isFalse();
    }

    @Test void successfulLocalFormLoginResetsBasicFailures() throws Exception {
        String user = user();
        fail(user, 4);
        mvc.perform(post("/login").secure(true).with(csrf()).param("username", user).param("password", PASSWORD))
                .andExpect(status().isFound()).andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("/dashboard"));
        fail(user, 4);
        assertThat(basic(user, PASSWORD, "/api/v1/health/live", "203.0.113.10").statusCode()).isEqualTo(200);
    }

    @Test void plaintextAndMalformedBasicCannotResetOrPoisonAnAccountBudget() throws Exception {
        String user = user();
        fail(user, 4);
        String valid = "Basic " + Base64.getEncoder().encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        var plain = send("/api/health", valid, "198.51.100.10", false);
        assertThat(plain.statusCode()).isEqualTo(403);
        assertThat(plain.body()).contains("tls_required");
        for (int i = 0; i < 5; i++) assertThat(send("/api/health", "Basic not-valid-base64!", "198.51.100.10", true).statusCode()).isEqualTo(401);
        assertThat(basic(user, "wrong-password", "/api/health", "203.0.113.10").statusCode()).isEqualTo(401);
        assertThat(basic(user, PASSWORD, "/api/health", "203.0.113.11").statusCode()).isEqualTo(429);
    }

    @Test void bearerTokensRemainUsableDuringAnAccountPasswordLockout() throws Exception {
        String user = user();
        fail(user, 5);
        var token = credentials.issue("basic lockout regression", Set.of("health:read"), Set.of(), Set.of(), Instant.now().plusSeconds(60));
        assertThat(send("/api/v1/health/live", "Bearer " + token.secret(), "203.0.113.10", true).statusCode()).isEqualTo(200);
    }
}
