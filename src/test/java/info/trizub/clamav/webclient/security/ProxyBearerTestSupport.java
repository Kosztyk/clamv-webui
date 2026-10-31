package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.service.ApiCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Exercises the actual embedded HTTP server and Boot's forwarded-header filter ordering. */
abstract class ProxyBearerTestSupport {
    @Autowired ApiCredentialService credentials;
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    ApiCredentialService.Created token(String... scopes) {
        return credentials.issue("proxy TLS regression", Set.of(scopes), Set.of(), Set.of(), Instant.now().plusSeconds(3600));
    }

    HttpResponse<String> request(String path, String secret, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + secret);
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
