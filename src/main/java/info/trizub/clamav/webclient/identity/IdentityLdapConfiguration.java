package info.trizub.clamav.webclient.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods=false)
public class IdentityLdapConfiguration {
    /** Always install the LDAP login filter; no LDAP traffic occurs until enabled. */
    @Bean
    UsernamePasswordAuthenticationFilter ldapLoginFilter(IdentityProperties properties, ExternalIdentityMapper mapper,
                AuthenticationSuccessHandler successHandler, LoginAttemptService attempts, AuthenticationSettingsService settings,
                SessionRegistry sessionRegistry) {
        UsernamePasswordAuthenticationFilter filter = new UsernamePasswordAuthenticationFilter(
            new ProviderManager(new DynamicLdapAuthenticationProvider(settings, mapper)));
        filter.setFilterProcessesUrl("/login/ldap");
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        filter.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(), new RegisterSessionAuthenticationStrategy(sessionRegistry))));
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler((request, response, failure) -> {
            attempts.fail("ldap", request.getParameter("username"), request.getRemoteAddr());
            response.sendRedirect(request.getContextPath() + "/login?ldapError");
        });
        return filter;
    }

    /** Built only on a genuine LDAP login or an admin Test Connection action. */
    static LdapContext createContext(IdentityProperties.Ldap config, String bindPassword) throws Exception {
        LdapGroupSearchFilter.validate(config.getGroupSearchFilter(), config.getGroupMemberAttribute(), config.isNestedGroups());
        String url=config.getUrl();
        if (url == null || (!url.startsWith("ldaps://") && !(url.startsWith("ldap://") && config.isStartTls())))
            throw new IllegalArgumentException("LDAP must use LDAPS or StartTLS; insecure ldap:// is forbidden");
        if (config.isStartTls() && !url.startsWith("ldap://"))
            throw new IllegalArgumentException("StartTLS requires ldap:// URL, not LDAPS");
        if (config.getUserSearchFilter().isBlank() || !config.getUserSearchFilter().contains("{0}"))
            throw new IllegalArgumentException("LDAP user filter must include escaped {0} placeholder");
        if (config.getBaseDn().isBlank() || config.getManagerDn().isBlank() || bindPassword.isBlank())
            throw new IllegalArgumentException("LDAP base DN and manager bind secret are required");
        if (!List.of("ignore","follow","throw").contains(config.getReferrals()))
            throw new IllegalArgumentException("Invalid LDAP referral policy");
        DefaultSpringSecurityContextSource ctx = new DefaultSpringSecurityContextSource(List.of(url), config.getBaseDn());
        ctx.setUserDn(config.getManagerDn()); ctx.setPassword(bindPassword);
        ctx.setReferral(config.getReferrals());
        ctx.setPooled(config.isPooled() && !config.isStartTls());
        ctx.setBaseEnvironmentProperties(Map.of(
            "com.sun.jndi.ldap.connect.timeout", Integer.toString(Math.max(1000, config.getConnectTimeoutMillis())),
            "com.sun.jndi.ldap.read.timeout", Integer.toString(Math.max(1000, config.getReadTimeoutMillis()))));
        if (config.isStartTls()) ctx.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy());
        ctx.afterPropertiesSet();
        var search = new FilterBasedLdapUserSearch(config.getUserSearchBase(), config.getUserSearchFilter(), ctx);
        search.setSearchSubtree(true);
        BindAuthenticator bind = new BindAuthenticator(ctx);
        bind.setUserSearch(search);
        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(bind,
            new RecursiveLdapGroupResolver(new LdapTemplate(ctx), config));
        return new LdapContext(ctx, provider);
    }
    record LdapContext(DefaultSpringSecurityContextSource context, LdapAuthenticationProvider provider) {}
    @Bean FilterRegistrationBean<UsernamePasswordAuthenticationFilter> disableStandaloneLdapFilter(
            UsernamePasswordAuthenticationFilter ldapLoginFilter) {
        FilterRegistrationBean<UsernamePasswordAuthenticationFilter> registration = new FilterRegistrationBean<>(ldapLoginFilter);
        registration.setEnabled(false);
        return registration;
    }
}
