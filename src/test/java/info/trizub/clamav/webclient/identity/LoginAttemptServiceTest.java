package info.trizub.clamav.webclient.identity;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class LoginAttemptServiceTest {
    @Test void localLockoutDoesNotBlockLdapAndExpires() {
        java.util.concurrent.atomic.AtomicReference<Instant> now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
        Clock clock=new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        LoginAttemptService attempts=new LoginAttemptService(clock);
        for (int i=0;i<5;i++) attempts.fail("local","admin","127.0.0.1");
        assertThat(attempts.locked("local","admin","127.0.0.1")).isTrue();
        assertThat(attempts.locked("ldap","admin","127.0.0.1")).isFalse();
        assertThat(attempts.locked("local","admin","192.168.68.10")).isFalse();
        attempts.success("local","admin","127.0.0.1");
        assertThat(attempts.locked("local","admin","127.0.0.1")).isFalse();
        for(int i=0;i<5;i++) attempts.fail("ldap","user","127.0.0.1");
        assertThat(attempts.locked("ldap","user","127.0.0.1")).isTrue();
        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(attempts.locked("ldap","user","127.0.0.1")).isFalse();
    }
}
