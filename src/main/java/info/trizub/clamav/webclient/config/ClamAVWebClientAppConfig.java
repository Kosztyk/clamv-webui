/**
 * Class Name: ClamAVWebClientAppConfig Description: This class defines beans to customize the
 * Java-based configuration for Spring MVC.
 *
 * <p>Author: Ruslan Huzii Email: ruslan@trizub.info Date: 2024-11-04
 *
 * <p>License: GNU Lesser General Public License v2.1
 */
package info.trizub.clamav.webclient.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

@Configuration
public class ClamAVWebClientAppConfig implements WebMvcConfigurer {

  @Bean
  CookieLocaleResolver localeResolver(
      @org.springframework.beans.factory.annotation.Value("${clamav.ui.default-language:en}")
          String language,
      @org.springframework.beans.factory.annotation.Value(
              "${server.servlet.session.cookie.secure:true}")
          boolean secure,
      @org.springframework.beans.factory.annotation.Value(
              "${server.servlet.session.cookie.same-site:lax}")
          String sameSite) {
    CookieLocaleResolver slr = new CookieLocaleResolver("clamav_lang");
    slr.setDefaultLocale(Locale.forLanguageTag(language));
    // Both cookies use the deployment's explicit HTTPS policy rather than
    // request.isSecure(), which may be false behind a TLS-terminating proxy.
    // Keep HttpOnly unconditional: JavaScript never needs the locale cookie.
    // Normalize the case used in Set-Cookie and reject unsupported raw overrides.
    String cookieSameSite =
        switch (sameSite.toLowerCase(Locale.ROOT)) {
          case "lax" -> "Lax";
          case "strict" -> "Strict";
          case "none" -> "None";
          default -> throw new IllegalArgumentException("Unsupported SameSite cookie policy");
        };
    if (!secure && "None".equals(cookieSameSite))
      throw new IllegalArgumentException("SameSite=None requires Secure cookies");
    slr.setCookieSecure(secure);
    slr.setCookieHttpOnly(true);
    slr.setCookieSameSite(cookieSameSite);
    slr.setCookieMaxAge(Duration.ofDays(365));
    return slr;
  }

  @Bean
  ResourceBundleMessageSource messageSource() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasenames("messages");
    source.setDefaultEncoding("UTF-8");
    source.setUseCodeAsDefaultMessage(true);
    return source;
  }
}
