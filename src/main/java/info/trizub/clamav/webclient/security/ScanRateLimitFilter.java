package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.service.ApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Post-authentication scan budget. Credentials never become bucket keys; state is strictly bounded. */
@Component
public class ScanRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ScanRateLimitFilter.class);
    private final BoundedExpiringStore<String, Integer> windows;
    private final Clock clock;
    private final AtomicLong nextCapacityWarning = new AtomicLong();
    private final int limit;
    @Autowired public ScanRateLimitFilter(@Value("${api.v1.scan-rate-limit-per-minute:30}") int limit,
            @Value("${api.v1.scan-rate-limit-max-entries:10000}") int capacity) {
        this(limit, capacity, Clock.systemUTC());
    }
    ScanRateLimitFilter(int limit, int capacity, Clock clock) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("Scan rate limit must be 1–10000 per minute");
        this.limit = limit; this.clock = clock; this.windows = new BoundedExpiringStore<>(capacity, clock);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) return true;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return !(path.equals("/api/scan/upload") || path.equals("/api/scan/path") ||
            path.equals("/scan/upload") || path.equals("/scan/path") || path.startsWith("/api/v1/scans/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var lookup = windows.update(subject(req), (old, now) -> old == null
            ? new BoundedExpiringStore.Entry<>(1, now.plus(Duration.ofMinutes(1)))
            : new BoundedExpiringStore.Entry<>(Math.min(limit + 1, old.value() + 1), old.expiresAt()));
        if (lookup.saturated() || lookup.entry().value() > limit) {
            if (lookup.saturated()) warnCapacity();
            long retry = lookup.saturated() ? lookup.retryAfterSeconds()
                : BoundedExpiringStore.remaining(clock.instant(), lookup.entry().expiresAt());
            res.setStatus(429); res.setContentType("application/json"); res.setHeader("Cache-Control", "no-store");
            res.setHeader("Retry-After", Long.toString(retry));
            res.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Scan submission limit exceeded\"}"); return;
        }
        chain.doFilter(req, res);
    }

    private String subject(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            if (auth.getPrincipal() instanceof ApiTokenPrincipal token) return "token:" + token.id();
            if (auth.getPrincipal() instanceof LocalUserDetails user) return "local:" + user.accountId();
            return "user:" + ApiCredentialService.sha256(auth.getName());
        }
        return "ip:" + ApiCredentialService.sha256(request.getRemoteAddr());
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void pruneExpired() { windows.pruneExpired(1024); }
    int trackedBuckets() { return windows.size(); }
    private void warnCapacity() {
        long now = clock.millis(), next = nextCapacityWarning.get();
        if (now >= next && nextCapacityWarning.compareAndSet(next, now + 60_000))
            log.warn("Scan limiter capacity reached; new subjects are temporarily throttled until a slot expires");
    }
}
