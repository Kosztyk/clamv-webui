package info.trizub.clamav.webclient.identity;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process interactive password throttling; external IdP must enforce its own global lockouts. */
@Component
public class LoginAttemptService {
    private final ConcurrentHashMap<String, Attempts> records = new ConcurrentHashMap<>();
    private final Clock clock;
    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    record Attempts(int count, Instant until) {}
    public LoginAttemptService() { this(Clock.systemUTC()); }
    LoginAttemptService(Clock clock) { this.clock = clock; }
    private String key(String mechanism, String username, String remote) {
        return mechanism + "|" + String.valueOf(username).trim().toLowerCase(java.util.Locale.ROOT) + "|" + remote;
    }
    public boolean locked(String mechanism, String username, String remote) {
        Attempts state = records.get(key(mechanism, username, remote));
        return state != null && state.count >= LIMIT && clock.instant().isBefore(state.until);
    }
    public void fail(String mechanism, String username, String remote) {
        String key = key(mechanism, username, remote);
        records.compute(key, (k, old) -> {
            Instant now = clock.instant();
            if (old == null || !now.isBefore(old.until)) return new Attempts(1, now.plus(WINDOW));
            return new Attempts(old.count + 1, old.until);
        });
    }
    public void success(String mechanism, String username, String remote) {
        records.remove(key(mechanism, username, remote));
    }
}
