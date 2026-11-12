package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.model.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.PrintWriter;
import java.io.Writer;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ScanRateLimitFilterTest {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T12:00:59Z"));
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    int request(ScanRateLimitFilter filter, String header, String remote) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/scans/paths"); request.setRemoteAddr(remote);
        if (header != null) request.addHeader("Authorization", header);
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain()); return response.getStatus();
    }
    void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
    @Test void oneMillionRotatingAnonymousHeadersUseOneIpBucketAndCannotResetWindow() throws Exception {
        var filter = new ScanRateLimitFilter(2, 8, clock);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var request = new MockHttpServletRequest("POST", "/api/v1/scans/paths"); request.setRemoteAddr("192.0.2.1");
        var accepted = new AtomicInteger();
        var response = new MockHttpServletResponse() {
            final PrintWriter sink = new PrintWriter(Writer.nullWriter());
            @Override public PrintWriter getWriter() { return sink; }
        };
        for (int i = 0; i < 1_000_000; i++) {
            request.removeHeader("Authorization"); request.addHeader("Authorization", "Unrecognized random-" + i);
            filter.doFilterInternal(request, response, (a, b) -> accepted.incrementAndGet());
        }
        assertThat(accepted.get()).isEqualTo(2); assertThat(filter.trackedBuckets()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(429);
    }
    @Test void oneMillionDistinctPeersCannotGrowMemoryOrEvictExistingBudget() throws Exception {
        var filter = new ScanRateLimitFilter(1, 8, clock);
        assertThat(request(filter, null, "protected-peer")).isEqualTo(200);
        var request = new MockHttpServletRequest("POST", "/api/v1/scans/paths");
        var response = new MockHttpServletResponse() {
            final PrintWriter sink = new PrintWriter(Writer.nullWriter());
            @Override public PrintWriter getWriter() { return sink; }
        };
        for (int i = 0; i < 1_000_000; i++) { request.setRemoteAddr("peer-" + i); filter.doFilterInternal(request, response, (a, b) -> {}); }
        assertThat(filter.trackedBuckets()).isEqualTo(8); assertThat(request(filter, "rotated", "protected-peer")).isEqualTo(429);
    }
    @Test void tokenIdSharesBudgetAcrossHeaderSpellingAndClientAddresses() throws Exception {
        var filter = new ScanRateLimitFilter(2, 8, clock);
        authenticate(new ApiTokenPrincipal(7L, "token", Set.of("scan:path"), Set.of(), Set.of()));
        assertThat(request(filter, "Bearer original", "192.0.2.1")).isEqualTo(200);
        assertThat(request(filter, "bEaReR original", "192.0.2.2")).isEqualTo(200);
        assertThat(request(filter, "BEARER original ", "192.0.2.3")).isEqualTo(429);
        authenticate(new ApiTokenPrincipal(8L, "other", Set.of("scan:path"), Set.of(), Set.of()));
        assertThat(request(filter, "Bearer other", "192.0.2.1")).isEqualTo(200);
    }
    @Test void localAccountIdSharesBudgetAcrossFormAndBasicAndPeers() throws Exception {
        var filter = new ScanRateLimitFilter(1, 8, clock);
        var user = new AppUser("user", "encoded", Set.of("ROLE_OPERATOR")); ReflectionTestUtils.setField(user, "id", 10L);
        authenticate(new LocalUserDetails(user)); assertThat(request(filter, null, "192.0.2.1")).isEqualTo(200);
        authenticate(new LocalUserDetails(user)); assertThat(request(filter, "Basic rotated", "192.0.2.2")).isEqualTo(429);
    }
    @Test void authenticatedSubjectsAreIndependentBehindSameIpAndIgnoreRawHeaders() throws Exception {
        var filter = new ScanRateLimitFilter(1, 8, clock);
        authenticate("ldap:alice"); assertThat(request(filter, "ignored one", "192.0.2.1")).isEqualTo(200);
        assertThat(request(filter, "ignored two", "192.0.2.2")).isEqualTo(429);
        authenticate("oidc:alice"); assertThat(request(filter, "ignored one", "192.0.2.1")).isEqualTo(200);
    }
    @Test void windowLastsSixtySecondsRatherThanResettingAtWallClockMinute() throws Exception {
        var filter = new ScanRateLimitFilter(1, 8, clock);
        assertThat(request(filter, null, "192.0.2.1")).isEqualTo(200);
        now.set(now.get().plusSeconds(1)); assertThat(request(filter, null, "192.0.2.1")).isEqualTo(429);
        now.set(now.get().plusSeconds(59)); assertThat(request(filter, null, "192.0.2.1")).isEqualTo(200);
    }
    @Test void fullStoreRecoversAfterExpiryAndMaintenanceReclaimsIdleState() throws Exception {
        var filter = new ScanRateLimitFilter(1, 1, clock);
        assertThat(request(filter, null, "192.0.2.1")).isEqualTo(200);
        assertThat(request(filter, null, "192.0.2.2")).isEqualTo(429);
        now.set(now.get().plusSeconds(60)); filter.pruneExpired(); assertThat(filter.trackedBuckets()).isZero();
        assertThat(request(filter, null, "192.0.2.2")).isEqualTo(200);
    }
    @Test void rejectedRequestsHaveAccurateRetryAfterAndDoNotExtendExpiry() throws Exception {
        var filter = new ScanRateLimitFilter(1, 8, clock); request(filter, null, "192.0.2.1");
        now.set(now.get().plusSeconds(59));
        var request = new MockHttpServletRequest("POST", "/api/v1/scans/paths"); request.setRemoteAddr("192.0.2.1");
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429); assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void readsAndNonScanPostsAreUnaffected() throws Exception {
        var filter = new ScanRateLimitFilter(1, 1, clock);
        for (String path : List.of("/api/v1/jobs", "/login", "/admin/users/create")) {
            var response = new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest("POST", path), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var response = new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/scans/paths"), response, new MockFilterChain());
        assertThat(filter.trackedBuckets()).isZero();
    }
}
