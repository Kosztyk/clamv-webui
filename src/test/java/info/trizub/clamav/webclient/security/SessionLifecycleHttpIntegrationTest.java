package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.session.SessionRegistry;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Real servlet events prove registry entries disappear on invalidation and logout. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:session-lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=SessionLifecycleBootstrap123456",
    "clamav.config.initial-delay-ms=3600000", "clamav.metrics.clamd-initial-delay-ms=3600000"
})
class SessionLifecycleHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired AppUserRepository accounts;
    @Autowired SessionRegistry registry;
    record Login(HttpClient client, String sessionId, String csrf) { }
    HttpResponse<String> request(HttpClient client, String path, String form) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (form != null) builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    Login login(String username) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        var page = request(client, "/login", null);
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertThat(match.find()).isTrue();
        String before = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        var response = request(client, "/login", "username=" + username + "&password=SessionLifecycleUser123456&_csrf="
            + URLEncoder.encode(match.group(1), StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        String id = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertThat(id).isNotEqualTo(before); assertThat(registry.getSessionInformation(id)).isNotNull();
        var csrf = request(client, "/api/v1/csrf", null);
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(csrf.body()).get("token").asText();
        return new Login(client, id, token);
    }
    @Test void realLogoutRemovesRegisteredSessionAndCannotReuseItsCookie() throws Exception {
        var user = users.createUser("lifecycle-" + UUID.randomUUID(), "SessionLifecycleUser123456", Set.of("ROLE_VIEWER"), true);
        try {
            var login = login(user.getUsername());
            assertThat(request(login.client(), "/logout", "_csrf=" + URLEncoder.encode(login.csrf(), StandardCharsets.UTF_8)).statusCode()).isEqualTo(302);
            assertThat(registry.getSessionInformation(login.sessionId())).isNull();
            assertThat(request(login.client(), "/api/v1/health/live", null).statusCode()).isEqualTo(401);
        } finally { accounts.deleteById(user.getId()); }
    }
    @Test void revocationResponseInvalidatesRealServletSessionAndRemovesRegistryEntry() throws Exception {
        var user = users.createUser("lifecycle-" + UUID.randomUUID(), "SessionLifecycleUser123456", Set.of("ROLE_VIEWER"), true);
        try {
            var login = login(user.getUsername()); users.resetPassword(user.getId(), "SessionLifecycleReplacement123456");
            assertThat(registry.getSessionInformation(login.sessionId()).isExpired()).isTrue();
            assertThat(request(login.client(), "/api/v1/health/live", null).statusCode()).isEqualTo(401);
            assertThat(registry.getSessionInformation(login.sessionId())).isNull();
        } finally { accounts.deleteById(user.getId()); }
    }
}
