package info.trizub.clamav.webclient.identity;

import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.security.SessionAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LdapSessionRegistrationTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    @Test void customLdapFilterRotatesSessionIdRegistersPrincipalAndStampsAbsoluteLoginTime() throws Exception {
        var properties = new IdentityProperties(); properties.getLdap().setEnabled(true);
        properties.getLdap().getGroups().setViewer("readers");
        var mapper = new ExternalIdentityMapper(properties);
        var settings = mock(AuthenticationSettingsService.class); when(settings.revision()).thenReturn(7L);
        when(settings.properties()).thenReturn(properties);
        var registry = new SessionRegistryImpl();
        var sessions = new SessionAccessService(mock(AppUserRepository.class), registry, settings, Duration.ofHours(1));
        var filter = new IdentityLdapConfiguration().ldapLoginFilter(properties, mapper,
            (request, response, auth) -> sessions.authenticated(request.getSession()), mock(LoginAttemptService.class), settings, registry);
        var identity = mapper.map(ExternalIdentityMapper.Source.LDAP, "ldaps://directory.example.test", "uid=alice,dc=example", Set.of("readers"));
        var principal = new ExternalLdapPrincipal(identity, "alice");
        filter.setAuthenticationManager(auth -> UsernamePasswordAuthenticationToken.authenticated(principal, null, identity.authorities()));
        var request = new MockHttpServletRequest("POST", "/login/ldap"); request.setServletPath("/login/ldap");
        request.setParameter("username", "alice"); request.setParameter("password", "synthetic-password");
        var session = new MockHttpSession(); var originalId = session.getId(); request.setSession(session);
        session.setNew(false); request.setRequestedSessionId(originalId); request.setRequestedSessionIdValid(true);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(session.getId()).isNotEqualTo(originalId);
        assertThat(registry.getSessionInformation(session.getId()).getPrincipal()).isSameAs(principal);
        assertThat(session.getAttribute(SessionAccessService.AUTHENTICATED_AT)).isInstanceOf(Long.class);
        assertThat(sessions.valid(SecurityContextHolder.getContext().getAuthentication(), session)).isTrue();
    }
}
