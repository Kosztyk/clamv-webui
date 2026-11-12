package info.trizub.clamav.webclient.identity;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import info.trizub.clamav.webclient.security.BoundedExpiringStore;
import info.trizub.clamav.webclient.service.ApiCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Per-process, account-wide password throttling. Local form and Basic share the local realm. */
@Component
public class LoginAttemptService {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private final BoundedExpiringStore<Account, Integer> records;
    private final AtomicLong nextCapacityWarning = new AtomicLong();
    private final Clock clock;
    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    record Account(String mechanism, String usernameDigest) {}
    @Autowired public LoginAttemptService(@Value("${clamav.security.login.max-entries:10000}") int capacity) {
        this(Clock.systemUTC(), capacity);
    }
    LoginAttemptService(Clock clock) { this(clock, 10_000); }
    LoginAttemptService(Clock clock, int capacity) {
        this.clock = clock; this.records = new BoundedExpiringStore<>(capacity, clock);
    }
    private Account key(String mechanism, String username) {
        if (!("local".equals(mechanism) || "ldap".equals(mechanism)) || username != null && username.length() > 1024) return null;
        return new Account(mechanism, username == null ? null :
            ApiCredentialService.sha256(username.trim().toLowerCase(java.util.Locale.ROOT)));
    }
    public boolean locked(String mechanism, String username, String remote) {
        // The caller's address is deliberately excluded: rotating peers must not reset an account's budget.
        Account key = key(mechanism, username);
        if (key == null) return true;
        var lookup = records.lookup(key);
        if (lookup.saturated()) { warnCapacity(); return true; }
        return lookup.entry() != null && lookup.entry().value() >= LIMIT;
    }
    public void fail(String mechanism, String username, String remote) {
        Account key = key(mechanism, username);
        if (key == null) return;
        var lookup = records.update(key, (old, now) -> {
            if (old == null) return new BoundedExpiringStore.Entry<>(1, now.plus(WINDOW));
            if (old.value() >= LIMIT) return old; // Requests during lockout never extend it.
            int count = old.value() + 1;
            return new BoundedExpiringStore.Entry<>(count, count == LIMIT ? now.plus(WINDOW) : old.expiresAt());
        });
        if (lookup.saturated()) warnCapacity();
    }
    public void success(String mechanism, String username, String remote) {
        Account key = key(mechanism, username);
        if (key != null) records.remove(key);
    }

    public long retryAfterSeconds(String mechanism, String username, String remote) {
        Account key = key(mechanism, username);
        if (key == null) return WINDOW.toSeconds();
        var lookup = records.lookup(key);
        if (lookup.saturated()) return lookup.retryAfterSeconds();
        if (lookup.entry() == null || lookup.entry().value() < LIMIT) return 0;
        return BoundedExpiringStore.remaining(clock.instant(), lookup.entry().expiresAt());
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void pruneExpired() { records.pruneExpired(1024); }
    int trackedAccounts() { return records.size(); }
    private void warnCapacity() {
        long now = clock.millis(), next = nextCapacityWarning.get();
        if (now >= next && nextCapacityWarning.compareAndSet(next, now + 60_000))
            log.warn("Login attempt capacity reached; untracked accounts are temporarily throttled until a slot expires");
    }
}
