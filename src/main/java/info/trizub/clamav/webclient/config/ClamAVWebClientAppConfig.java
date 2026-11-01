/**
 * Class Name: ClamAVWebClientAppConfig
 * Description: This class defines beans to customize the Java-based configuration for Spring MVC.
 * 
 * Author: Ruslan Huzii
 * Email:  ruslan@trizub.info
 * Date: 2024-11-04
 * 
 * License: GNU Lesser General Public License v2.1
 */
package info.trizub.clamav.webclient.config;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import java.time.Duration;

@Configuration
public class ClamAVWebClientAppConfig implements WebMvcConfigurer {

	@Bean
	CookieLocaleResolver localeResolver(@org.springframework.beans.factory.annotation.Value("${clamav.ui.default-language:en}") String language) {
		CookieLocaleResolver slr = new CookieLocaleResolver("clamav_lang");
		slr.setDefaultLocale(Locale.forLanguageTag(language));
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
