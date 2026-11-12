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
import jakarta.servlet.Filter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Exercises the actual embedded HTTP server, including Tomcat's native proxy handling. */
@Import(ProxyBearerTestSupport.RequestCaptureConfiguration.class)
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
    /** Test-only observation of the servlet request consumed by security, rate limits and audit. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RequestCaptureConfiguration {
        @Bean FilterRegistrationBean<Filter> requestCapture() {
            Filter filter = (request, response, chain) -> {
                var req = (HttpServletRequest) request;
                var res = (HttpServletResponse) response;
                res.setHeader("X-Test-Remote-Addr", req.getRemoteAddr());
                res.setHeader("X-Test-Secure", Boolean.toString(req.isSecure()));
                res.setHeader("X-Test-Scheme", req.getScheme());
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            return registration;
        }
    }
}
