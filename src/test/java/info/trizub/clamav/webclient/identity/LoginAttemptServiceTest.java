package info.trizub.clamav.webclient.identity;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class LoginAttemptServiceTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T09:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginAttemptService attempts = new LoginAttemptService(clock);

    @Test void distributedFailuresLockTheAccountAcrossPeersButNotOtherProvidersOrUsers() {
        for (int i = 0; i < 5; i++) attempts.fail("local", "admin", "192.0.2." + i);
        assertThat(attempts.locked("local", "admin", "198.51.100.20")).isTrue();
        assertThat(attempts.locked("local", " ADMIN ", "198.51.100.21")).isTrue();
        assertThat(attempts.locked("ldap", "admin", "192.0.2.1")).isFalse();
        assertThat(attempts.locked("local", "other", "192.0.2.1")).isFalse();
    }

    @Test void successfulAuthenticationClearsFailuresAcrossAllPeers() {
        for (int i = 0; i < 4; i++) attempts.fail("local", "user", "192.0.2." + i);
        attempts.success("local", "user", "198.51.100.10");
        for (int i = 0; i < 4; i++) attempts.fail("local", "user", "203.0.113." + i);
        assertThat(attempts.locked("local", "user", "198.51.100.20")).isFalse();
        attempts.fail("local", "user", "203.0.113.10");
        assertThat(attempts.locked("local", "user", "198.51.100.20")).isTrue();
    }

    @Test void anOidcSuccessWithoutAFormUsernameCannotResetTheLiteralNullAccount() {
        for (int i = 0; i < 5; i++) attempts.fail("local", "null", "192.0.2.1");
        attempts.success("local", null, "198.51.100.10");
        assertThat(attempts.locked("local", "null", "203.0.113.10")).isTrue();
    }

    @Test void partialFailureWindowExpiresWithoutSleeping() {
        for (int i = 0; i < 4; i++) attempts.fail("local", "user", "192.0.2.1");
        now.set(now.get().plus(Duration.ofMinutes(15)));
        attempts.fail("local", "user", "198.51.100.1");
        assertThat(attempts.locked("local", "user", "198.51.100.2")).isFalse();
        assertThat(attempts.retryAfterSeconds("local", "user", "198.51.100.2")).isZero();
    }

    @Test void lockoutLastsFifteenMinutesFromThresholdAndBlockedAttemptsDoNotExtendIt() {
        for (int i = 0; i < 4; i++) attempts.fail("local", "user", "192.0.2.1");
        now.set(now.get().plus(Duration.ofMinutes(14)));
        attempts.fail("local", "user", "198.51.100.1");
        assertThat(attempts.retryAfterSeconds("local", "user", "203.0.113.1")).isEqualTo(900);
        now.set(now.get().plus(Duration.ofMinutes(14)));
        attempts.fail("local", "user", "203.0.113.1");
        assertThat(attempts.retryAfterSeconds("local", "user", "203.0.113.1")).isEqualTo(60);
        now.set(now.get().plusSeconds(60));
        assertThat(attempts.locked("local", "user", "192.0.2.1")).isFalse();
        assertThat(attempts.retryAfterSeconds("local", "user", "192.0.2.1")).isZero();
    }
    @Test void millionDistinctNamesStayBoundedAndCannotEvictALockedAccount() {
        var bounded = new LoginAttemptService(clock, 8);
        for (int i = 0; i < 5; i++) bounded.fail("local", "protected-account", "192.0.2." + i);
        for (int i = 0; i < 1_000_000; i++) bounded.fail("local", "random-user-" + i, "192.0.2.1");
        assertThat(bounded.trackedAccounts()).isEqualTo(8);
        assertThat(bounded.locked("local", "protected-account", "198.51.100.1")).isTrue();
        assertThat(bounded.locked("local", "another-new-user", "192.0.2.1")).isTrue();
        assertThat(bounded.retryAfterSeconds("local", "another-new-user", "192.0.2.1")).isEqualTo(900);
    }
    @Test void scheduledExpiryReclaimsIdleRecordsWithoutASuccessfulLogin() {
        var bounded = new LoginAttemptService(clock, 8);
        for (int i = 0; i < 8; i++) bounded.fail("local", "user-" + i, "192.0.2.1");
        now.set(now.get().plus(Duration.ofMinutes(15))); bounded.pruneExpired();
        assertThat(bounded.trackedAccounts()).isZero();
        assertThat(bounded.locked("local", "new-user", "192.0.2.1")).isFalse();
    }
    @Test void fullStoreKeepsTrackedAccountsUsableAndSuccessFreesASlot() {
        var bounded = new LoginAttemptService(clock, 2);
        bounded.fail("local", "known", "192.0.2.1"); bounded.fail("local", "other", "192.0.2.1");
        assertThat(bounded.locked("local", "known", "192.0.2.1")).isFalse();
        assertThat(bounded.locked("local", "untracked", "192.0.2.1")).isTrue();
        bounded.success("local", "known", "198.51.100.1");
        assertThat(bounded.locked("local", "untracked", "192.0.2.1")).isFalse();
    }
    @Test void expiryMakesRoomOnDemandEvenBeforeMaintenanceRuns() {
        var bounded = new LoginAttemptService(clock, 1); bounded.fail("local", "old", "192.0.2.1");
        now.set(now.get().plus(Duration.ofMinutes(15)));
        assertThat(bounded.locked("local", "new", "192.0.2.1")).isFalse();
        bounded.fail("local", "new", "192.0.2.1"); assertThat(bounded.trackedAccounts()).isEqualTo(1);
    }
    @Test void oversizedNamesAndUnknownRealmsAreRejectedWithoutAllocatingState() {
        var bounded = new LoginAttemptService(clock, 2);
        assertThat(bounded.locked("local", "x".repeat(1025), "192.0.2.1")).isTrue();
        bounded.fail("unexpected-realm", "user", "192.0.2.1");
        bounded.fail("local", "x".repeat(1025), "192.0.2.1");
        assertThat(bounded.trackedAccounts()).isZero();
    }
}
