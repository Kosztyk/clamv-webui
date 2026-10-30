package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.UserService;
import info.trizub.clamav.webclient.service.AuditService;
import info.trizub.clamav.webclient.service.ExternalIdentityService;
import info.trizub.clamav.webclient.identity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import info.trizub.clamav.webclient.security.ApiTokenAuthenticationFilter;
import info.trizub.clamav.webclient.security.LegacyBasicTlsFilter;
import info.trizub.clamav.webclient.security.ScanRateLimitFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationSuccessHandler authenticationSuccessHandler(UserService userService, AuditService audit,
            LoginAttemptService attempts, ExternalIdentityService externalIdentities) {
        return (request, response, authentication) -> {
            attempts.success(request.getServletPath().equals("/login/ldap") ? "ldap" : "local",
                    request.getParameter("username"), request.getRemoteAddr());
            userService.markLogin(authentication.getName());
            Object principal = authentication.getPrincipal();
            ExternalIdentityMapper.MappedIdentity external = principal instanceof ExternalOidcUser oidc
                ? oidc.identity() : principal instanceof ExternalLdapPrincipal ldap ? ldap.identity() : null;
            if (external != null) {
                externalIdentities.recordLogin(external);
                audit.record(authentication, request, "EXTERNAL_LOGIN", "source=" + external.source(), "SUCCESS", null);
                if (authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))) {
                    audit.record(authentication, request, "EXTERNAL_ADMIN_GRANTED", "mapped privileged group", "SUCCESS", null);
                }
            }
            response.sendRedirect(request.getContextPath() + "/dashboard");
        };
    }

    // Spring Boot would otherwise register @Component filters twice (outside the Spring Security chain).
    @Bean FilterRegistrationBean<ApiTokenAuthenticationFilter> tokenFilterRegistration(ApiTokenAuthenticationFilter filter) {
        FilterRegistrationBean<ApiTokenAuthenticationFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false); return bean;
    }
    @Bean FilterRegistrationBean<LegacyBasicTlsFilter> basicTlsFilterRegistration(LegacyBasicTlsFilter filter) {
        FilterRegistrationBean<LegacyBasicTlsFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false); return bean;
    }
    @Bean FilterRegistrationBean<ScanRateLimitFilter> rateFilterRegistration(ScanRateLimitFilter filter) {
        FilterRegistrationBean<ScanRateLimitFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false); return bean;
    }

    @Bean FilterRegistrationBean<LoginLockoutFilter> loginLockoutRegistration(LoginLockoutFilter filter) {
        FilterRegistrationBean<LoginLockoutFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false); return bean;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationSuccessHandler successHandler,
             ApiTokenAuthenticationFilter tokenFilter, LegacyBasicTlsFilter tlsFilter, ScanRateLimitFilter rateFilter,
             IdentityProperties identity, ExternalIdentityMapper mapper,
             ObjectProvider<UsernamePasswordAuthenticationFilter> ldapFilter,
             ObjectProvider<ClientRegistrationRepository> clientRegistrations,
             LoginLockoutFilter loginLockoutFilter, LoginAttemptService attempts) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/icons/**", "/webfonts/**", "/flags/**", "/favicon.ico", "/error", "/setLocale", "/login/ldap", "/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll() // Fail-closed standalone scrape token filter.
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/h2/**").hasRole("ADMIN")
                .requestMatchers("/ws/clamdtop").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/endpoints", "/api/clamdtop").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.GET, "/rest-monitor/summary").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .requestMatchers("/rest-monitor/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/api/v1/tokens", "/api/v1/tokens/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/api-scan", "/api-scan/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/api-reports", "/api-reports/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/api/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/jobs/**", "/scan/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/", "/dashboard", "/settings", "/main", "/clamdtop", "/ping", "/version", "/stats").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
                .successHandler(successHandler)
                .failureHandler((request, response, error) -> {
                    attempts.fail("local", request.getParameter("username"), request.getRemoteAddr());
                    response.sendRedirect(request.getContextPath() + "/login?error");
                })
            )
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor((req, res, err) -> {
                    res.setStatus(401); res.setContentType("application/json"); res.setHeader("Cache-Control", "no-store");
                    res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
                }, PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**"))
                .defaultAccessDeniedHandlerFor((req, res, err) -> {
                    res.setStatus(403); res.setContentType("application/json"); res.setHeader("Cache-Control", "no-store");
                    res.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Access denied\"}");
                }, PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**")))
            // Keep browser CSRF protection on token-management and v1 session actions.
            // Bearer requests have no cookies and do not require CSRF.
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.equals("/h2") || path.startsWith("/h2/")) return true;
                String authorization = request.getHeader("Authorization");
                // Legacy browser-session API state changes must have CSRF. Only explicit
                // credential-bearing machine requests bypass the browser CSRF token.
                if (path.startsWith("/api/") && !path.startsWith("/api/v1/"))
                    return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
                return path.startsWith("/api/v1/") && authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
            }))
            .addFilterBefore(loginLockoutFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(tlsFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateFilter, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())); // H2 console

        // Both filters are always wired; runtime provider settings may be activated via Admin UI.
        UsernamePasswordAuthenticationFilter customFilter = ldapFilter.getObject();
        http.addFilterBefore(customFilter, UsernamePasswordAuthenticationFilter.class);
        http.oauth2Login(oauth -> oauth
            .loginPage("/login")
            .userInfoEndpoint(info -> info.oidcUserService(new ExternalOidcUserService(mapper, identity)))
            .successHandler(successHandler)
            .failureUrl("/login?oidcError"));

        // A browser logout must always navigate away from the authenticated page.
        // The explicit handler prevents an OAuth2/OIDC configuration from replacing
        // the local logout redirect with a 204 No Content response.
        LogoutSuccessHandler localLogout = (request, response, authentication) ->
            response.sendRedirect(request.getContextPath() + "/login?logout");
        OidcClientInitiatedLogoutSuccessHandler oidcLogout =
            new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations.getObject());
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/login?logout");
        LogoutSuccessHandler logoutSuccess = (request, response, authentication) -> {
            if (identity.getOidc().isEnabled() && "provider".equalsIgnoreCase(identity.getOidc().getLogoutMode())
                    && authentication!=null && authentication.getPrincipal() instanceof OidcUser) {
                oidcLogout.onLogoutSuccess(request,response,authentication);
            } else localLogout.onLogoutSuccess(request,response,authentication);
        };
        LogoutSuccessHandler finalLogoutSuccess = logoutSuccess;
        http.logout(options -> options.logoutUrl("/logout")
                .logoutSuccessHandler(finalLogoutSuccess).permitAll());
        return http.build();
    }
}
