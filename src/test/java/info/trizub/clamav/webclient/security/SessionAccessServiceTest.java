package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.identity.*;
import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAccessServiceTest {
    final AppUserRepository users = mock(AppUserRepository.class);
    final SessionRegistryImpl registry = new SessionRegistryImpl();
    final AuthenticationSettingsService settings = mock(AuthenticationSettingsService.class);
    final IdentityProperties properties = new IdentityProperties();
    final SessionAccessService access;
    SessionAccessServiceTest() {
        properties.getLdap().setEnabled(true); properties.getOidc().setEnabled(true);
        properties.getLdap().getGroups().setViewer("readers"); properties.getOidc().getGroups().setViewer("readers");
        when(settings.properties()).thenReturn(properties); when(settings.revision()).thenReturn(1L);
        access = new SessionAccessService(users, registry, settings, Duration.ofHours(1));
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    Authentication external(ExternalIdentityMapper.Source source, String subject) {
        var identity = new ExternalIdentityMapper(properties).map(source, "https://id.example.test", subject, Set.of("readers"));
        Object principal = source == ExternalIdentityMapper.Source.LDAP
            ? new ExternalLdapPrincipal(identity, subject) : new ExternalOidcUser(mock(OidcUser.class), identity);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, identity.authorities());
    }
    MockHttpSession session(Authentication authentication) {
        var session = new MockHttpSession(); registry.registerNewSession(session.getId(), authentication.getPrincipal());
        access.authenticated(session); return session;
    }
    AppUser account() {
        AppUser user = new AppUser("account", "encoded-password", Set.of("ROLE_VIEWER"));
        ReflectionTestUtils.setField(user, "id", 17L); return user;
    }

    @Test void erasedCredentialsStillDetectResetAndRoleSnapshotIsImmutable() {
        var current = account(); var principal = new LocalUserDetails(current); int hash = principal.hashCode();
        principal.eraseCredentials(); assertThat(principal.getPassword()).isNull(); assertThat(principal.matches(current)).isTrue();
        assertThat(principal.hashCode()).isEqualTo(hash);
        current.setPasswordHash("different-encoded-password"); assertThat(principal.matches(current)).isFalse();
        current.setPasswordHash("encoded-password"); current.setRoles(Set.of("ROLE_ADMIN"));
        assertThat(principal.matches(current)).isFalse();
        assertThat(principal.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_VIEWER");
    }
    @Test void unknownOrUnregisteredSessionFailsClosed() {
        var auth = external(ExternalIdentityMapper.Source.LDAP, "alice"); var session = session(auth);
        registry.removeSessionInformation(session.getId());
        assertThat(access.valid(auth, session)).isFalse();
    }
    @Test void missingOrFutureAuthenticationTimeFailsClosed() {
        var auth = external(ExternalIdentityMapper.Source.OIDC, "alice"); var session = session(auth);
        session.removeAttribute(SessionAccessService.AUTHENTICATED_AT); assertThat(access.valid(auth, session)).isFalse();
        session.setAttribute(SessionAccessService.AUTHENTICATED_AT, Long.MAX_VALUE); assertThat(access.valid(auth, session)).isFalse();
    }
    @Test void externalProviderRevisionChangeRevokesLdapAndOidcButDoesNotQueryLocalUsers() {
        var ldap = external(ExternalIdentityMapper.Source.LDAP, "alice"); var oidc = external(ExternalIdentityMapper.Source.OIDC, "alice");
        var ldapSession = session(ldap); var oidcSession = session(oidc);
        assertThat(access.valid(ldap, ldapSession)).isTrue(); assertThat(access.valid(oidc, oidcSession)).isTrue();
        when(settings.revision()).thenReturn(2L);
        assertThat(access.valid(ldap, ldapSession)).isFalse(); assertThat(access.valid(oidc, oidcSession)).isFalse();
        verifyNoInteractions(users);
    }
    @Test void disabledExternalProviderInvalidatesItsExistingSessions() {
        var ldap = external(ExternalIdentityMapper.Source.LDAP, "alice"); var oidc = external(ExternalIdentityMapper.Source.OIDC, "alice");
        var ldapSession = session(ldap); var oidcSession = session(oidc);
        properties.getLdap().setEnabled(false);
        assertThat(access.valid(ldap, ldapSession)).isFalse(); assertThat(access.valid(oidc, oidcSession)).isTrue();
        properties.getOidc().setEnabled(false); assertThat(access.valid(oidc, oidcSession)).isFalse();
    }
    @Test void externalSessionsHaveTheSameAbsoluteLifetime() {
        for (var source : ExternalIdentityMapper.Source.values()) {
            var auth = external(source, "alice"); var session = session(auth);
            session.setAttribute(SessionAccessService.AUTHENTICATED_AT, System.currentTimeMillis() - 3_600_001L);
            assertThat(access.valid(auth, session)).isFalse();
        }
    }
    @Test void revokingExternalIdentityExpiresAllItsSessionsWithoutNameCollisions() {
        var ldap = external(ExternalIdentityMapper.Source.LDAP, "alice"); var oidc = external(ExternalIdentityMapper.Source.OIDC, "alice");
        var first = session(ldap); var second = session(ldap); var other = session(oidc);
        var events = mock(ApplicationEventPublisher.class); var revocation = new SessionRevocationService(registry, events);
        assertThat(revocation.revokeExternal(ldap.getName())).isEqualTo(2);
        assertThat(access.valid(ldap, first)).isFalse(); assertThat(access.valid(ldap, second)).isFalse();
        assertThat(access.valid(oidc, other)).isTrue();
        verify(events).publishEvent(new SessionRevoked(first.getId())); verify(events).publishEvent(new SessionRevoked(second.getId()));
    }
    @Test void localUsernameCannotBeUsedAsAnExternalRevocationTarget() {
        var revocation = new SessionRevocationService(registry, mock(ApplicationEventPublisher.class));
        assertThatThrownBy(() -> revocation.revokeExternal("admin")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseFailureRejectsTheRequestWithoutDestroyingRecoverableSession() throws Exception {
        var principal = new LocalUserDetails(account()); principal.eraseCredentials();
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var session = session(auth); SecurityContextHolder.getContext().setAuthentication(auth);
        when(users.findById(17L)).thenThrow(new DataAccessResourceFailureException("fixture DB unavailable"));
        var request = new MockHttpServletRequest("GET", "/api/v1/health/live"); request.setSession(session);
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        new SessionValidationFilter(access).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503); assertThat(response.getContentAsString()).contains("authentication_unavailable");
        assertThat(session.isInvalid()).isFalse(); verifyNoInteractions(chain);
    }
    @Test void statelessAuthenticationDoesNotCreateSessionOrQueryAccountTwice() throws Exception {
        var principal = new LocalUserDetails(account());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest("GET", "/api/v1/health/live"); var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class); new SessionValidationFilter(access).doFilter(request, response, chain);
        verify(chain).doFilter(request, response); assertThat(request.getSession(false)).isNull(); verifyNoInteractions(users);
    }
    @Test void invalidAbsoluteLifetimeIsRejected() {
        for (var duration : new Duration[]{Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(25)}) {
            assertThatThrownBy(() -> new SessionAccessService(users, registry, settings, duration)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
