package info.trizub.clamav.webclient.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Tests actual embedded servlet-container Set-Cookie headers, not MockMvc's synthetic session. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cookie_issue27;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "clamav.bootstrap.admin.password=CookieTestOnlyLongPassword12345",
      "spring.h2.console.enabled=false",
      "spring.task.scheduling.enabled=false",
      "server.servlet.session.cookie.secure=true",
      "server.servlet.session.cookie.http-only=true",
      "server.servlet.session.cookie.same-site=lax",
      "server.forward-headers-strategy=none"
    })
class CookieSessionHttpIntegrationTest {
  @Autowired Environment environment;

  private HttpResponse<Void> get(String path) throws Exception {
    int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static void assertCookie(List<String> cookies, String name) {
    String cookie =
        cookies.stream()
            .filter(c -> c.startsWith(name + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + name + ": " + cookies));
    assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void loginSessionCookieCarriesAllAttributesBehindTerminatedHttps() throws Exception {
    HttpResponse<Void> response = get("/login");
    assertThat(response.statusCode()).isEqualTo(200);
    assertCookie(response.headers().allValues("Set-Cookie"), "JSESSIONID");
    assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
  }

  @Test
  void localeSelectionCookieIsProtectedOnTheActualHttpResponse() throws Exception {
    HttpResponse<Void> response = get("/setLocale?lang=ro&returnTo=/login");
    assertThat(response.statusCode()).isBetween(300, 399);
    assertCookie(response.headers().allValues("Set-Cookie"), "clamav_lang");
  }
}
