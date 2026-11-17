package info.trizub.clamav.webclient.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

class CookieLocaleSecurityTest {
  @Test
  void localeCookieIsSecureHttpOnlyAndSameSiteLaxEvenIfBackendRequestIsHttp() {
    CookieLocaleResolver resolver =
        new ClamAVWebClientAppConfig().localeResolver("en", true, "lax");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSecure(false); // TLS may terminate at trusted NGINX.
    MockHttpServletResponse response = new MockHttpServletResponse();
    resolver.setLocale(request, response, Locale.forLanguageTag("ro"));
    String cookie = response.getHeader("Set-Cookie");
    assertThat(cookie)
        .isNotNull()
        .contains("clamav_lang=")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Lax");
  }

  @Test
  void insecureCookieModeRequiresAnExplicitDevelopmentOverride() {
    CookieLocaleResolver resolver =
        new ClamAVWebClientAppConfig().localeResolver("en", false, "lax");
    MockHttpServletResponse response = new MockHttpServletResponse();
    resolver.setLocale(new MockHttpServletRequest(), response, Locale.ENGLISH);
    assertThat(response.getHeader("Set-Cookie"))
        .contains("HttpOnly", "SameSite=Lax")
        .doesNotContain("Secure");
    assertThatThrownBy(() -> new ClamAVWebClientAppConfig().localeResolver("en", false, "none"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SameSite=None");
  }
}
