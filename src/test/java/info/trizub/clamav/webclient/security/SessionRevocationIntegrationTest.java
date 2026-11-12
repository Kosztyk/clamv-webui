package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:session-revocation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "clamav.bootstrap.admin.password=SessionRegressionBootstrap123456",
    "clamav.config.initial-delay-ms=3600000", "clamav.metrics.clamd-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class SessionRevocationIntegrationTest {
    static final String PASSWORD = "SessionRegressionUserPassword123";
    static final String NEW_PASSWORD = "ReplacedSessionUserPassword456";
    static final Set<String> VIEWER = Set.of("ROLE_VIEWER");
    static final Set<String> ADMIN = Set.of("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN");
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired AppUserRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    final List<Long> created = new ArrayList<>();
    final List<String> sessions = new ArrayList<>();

    @AfterEach void cleanUp() {
        sessions.forEach(registry::removeSessionInformation);
        created.forEach(id -> accounts.findById(id).ifPresent(accounts::delete));
    }
    AppUser account(Set<String> roles) {
        var account = users.createUser("session-" + UUID.randomUUID(), PASSWORD, roles, true);
        created.add(account.getId()); return account;
    }
    MockHttpSession login(AppUser account) throws Exception { return login(account, PASSWORD); }
    MockHttpSession login(AppUser account, String password) throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).param("username", account.getUsername())
                .param("password", password)).andExpect(status().isFound()).andExpect(redirectedUrl("/dashboard")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        sessions.add(session.getId());
        assertThat(registry.getSessionInformation(session.getId())).isNotNull();
        return session;
    }
    void accepted(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/v1/health/live").session(session)).andExpect(status().isOk());
    }
    void rejected(MockHttpSession session) throws Exception {
        mvc.perform(get("/api/v1/health/live").session(session)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("session_expired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void deletingAccountRevokesEverySessionAndPreservesOtherUsers() throws Exception {
        var victim = account(VIEWER); var first = login(victim); var second = login(victim);
        var control = login(account(VIEWER));
        users.deleteUser(victim.getId());
        assertThat(registry.getSessionInformation(first.getId()).isExpired()).isTrue();
        rejected(first); rejected(second); accepted(control);
    }
    @Test void resettingPasswordRevokesEverySessionAndAllowsFreshLoginWithNewPassword() throws Exception {
        var victim = account(VIEWER); var first = login(victim); var second = login(victim);
        users.resetPassword(victim.getId(), NEW_PASSWORD);
        rejected(first); rejected(second); accepted(login(victim, NEW_PASSWORD));
        mvc.perform(post("/login").with(csrf()).param("username", victim.getUsername()).param("password", PASSWORD))
            .andExpect(redirectedUrl("/login?error"));
    }
    @Test void disablingThenReenablingDoesNotRestoreOldSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        users.updateAccess(victim.getId(), VIEWER, false);
        mvc.perform(post("/login").with(csrf()).param("username", victim.getUsername()).param("password", PASSWORD))
            .andExpect(redirectedUrl("/login?error"));
        users.updateAccess(victim.getId(), VIEWER, true);
        rejected(session); accepted(login(victim));
    }
    @Test void demotedAdminLosesCachedPrivilegesAndMustSignInAgain() throws Exception {
        var victim = account(ADMIN); var session = login(victim);
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isOk());
        users.updateAccess(victim.getId(), VIEWER, true);
        rejected(session);
        var fresh = login(victim);
        mvc.perform(get("/admin/users").session(fresh)).andExpect(status().isForbidden());
        accepted(fresh);
    }
    @Test void unchangedAccessAndLastLoginUpdatesKeepSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        users.markLogin(victim.getUsername()); users.updateAccess(victim.getId(), VIEWER, true);
        accepted(session);
    }
    @Test void directDatabaseDisableIsDetectedOnNextRequest() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        victim.setEnabled(false); accounts.save(victim);
        rejected(session);
    }
    @Test void directDatabaseRoleChangeIsDetectedOnNextRequest() throws Exception {
        var victim = account(ADMIN); var session = login(victim);
        victim.setRoles(VIEWER); accounts.save(victim);
        rejected(session);
    }
    @Test void directDatabasePasswordChangeIsDetectedAfterCredentialErasure() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(((LocalUserDetails) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        victim.setPasswordHash(encoder.encode(NEW_PASSWORD)); accounts.save(victim);
        rejected(session);
    }
    @Test void deletionAndRecreationWithSameUsernameCannotRestoreSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        accounts.deleteById(victim.getId());
        var replacement = users.createUser(victim.getUsername(), PASSWORD, VIEWER, true); created.add(replacement.getId());
        assertThat(replacement.getId()).isNotEqualTo(victim.getId());
        rejected(session); accepted(login(replacement));
    }
    @Test void rolledBackPasswordResetDoesNotRevokeSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.resetPassword(victim.getId(), NEW_PASSWORD); tx.setRollbackOnly();
        });
        assertThat(registry.getSessionInformation(session.getId()).isExpired()).isFalse();
        accepted(session);
    }
    @Test void rolledBackDeletionDoesNotRevokeSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.deleteUser(victim.getId()); tx.setRollbackOnly();
        });
        accepted(session);
    }
    @Test void invalidPasswordResetDoesNotRevokeSession() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        assertThatThrownBy(() -> users.resetPassword(victim.getId(), "short")).isInstanceOf(IllegalArgumentException.class);
        accepted(session);
    }
    @Test void absoluteLifetimeDoesNotSlideWithActivity() throws Exception {
        var session = login(account(VIEWER));
        long started = (Long) session.getAttribute(SessionAccessService.AUTHENTICATED_AT);
        accepted(session); accepted(session);
        assertThat(session.getAttribute(SessionAccessService.AUTHENTICATED_AT)).isEqualTo(started);
        session.setAttribute(SessionAccessService.AUTHENTICATED_AT, System.currentTimeMillis() - 3_600_001L);
        rejected(session);
    }
    @Test void revokedBrowserSessionRedirectsToLogin() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        users.resetPassword(victim.getId(), NEW_PASSWORD);
        mvc.perform(get("/dashboard").session(session)).andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void revocationRunsBeforeCsrfSoExpiredSessionCannotPerformAnAction() throws Exception {
        var victim = account(ADMIN); var session = login(victim);
        users.resetPassword(victim.getId(), NEW_PASSWORD);
        mvc.perform(post("/admin/users/create").session(session).param("username", "must-not-be-created"))
            .andExpect(redirectedUrl("/login?expired"));
        assertThat(accounts.existsByUsername("must-not-be-created")).isFalse();
    }
    @Test void adminAccessEditorRevokesTargetSessionAndRetainsAdminSession() throws Exception {
        var administrator = login(account(ADMIN)); var victim = account(ADMIN); var session = login(victim);
        mvc.perform(post("/admin/users/{id}/access", victim.getId()).session(administrator).with(csrf())
                .param("role", "VIEWER").param("enabled", "false")).andExpect(redirectedUrl("/admin/users"));
        rejected(session); accepted(administrator);
        assertThat(accounts.findById(victim.getId()).orElseThrow().isEnabled()).isFalse();
    }
    @Test void manualRevocationRequiresAdminAndCsrf() throws Exception {
        var victim = account(VIEWER); var session = login(victim); var administrator = login(account(ADMIN));
        mvc.perform(post("/admin/users/{id}/sessions/revoke", victim.getId()).session(administrator))
            .andExpect(status().isForbidden()); accepted(session);
        mvc.perform(post("/admin/users/{id}/sessions/revoke", victim.getId()).session(session).with(csrf()))
            .andExpect(status().isForbidden()); accepted(session);
        mvc.perform(post("/admin/users/{id}/sessions/revoke", victim.getId()).session(administrator).with(csrf()))
            .andExpect(redirectedUrl("/admin/users"));
        rejected(session); accepted(administrator); accepted(login(victim));
    }
    @Test void explicitBasicCredentialsCannotReuseCachedSameUsernameAuthentication() throws Exception {
        var victim = account(VIEWER); var session = login(victim);
        mvc.perform(get("/api/v1/health/live").session(session).secure(true)
            .with(httpBasic(victim.getUsername(), "wrong-password"))).andExpect(status().isUnauthorized());
    }
    @Test void lastLocalAdministratorCannotBeDisabledDemotedOrDeleted() {
        var administrator = accounts.findByUsername("admin").orElseThrow();
        assertThatThrownBy(() -> users.updateAccess(administrator.getId(), ADMIN, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.updateAccess(administrator.getId(), VIEWER, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> users.deleteUser(administrator.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.findById(administrator.getId()).orElseThrow().isEnabled()).isTrue();
    }
    @Test void accessEditorRequiresAdminAndCsrf() throws Exception {
        var victim = account(VIEWER); var session = login(victim); var administrator = login(account(ADMIN));
        mvc.perform(post("/admin/users/{id}/access", victim.getId()).session(administrator)
            .param("role", "ADMIN").param("enabled", "true")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/access", victim.getId()).session(session).with(csrf())
            .param("role", "ADMIN").param("enabled", "true")).andExpect(status().isForbidden());
        assertThat(accounts.findById(victim.getId()).orElseThrow().getRoles()).isEqualTo(VIEWER);
        accepted(session);
    }
}
