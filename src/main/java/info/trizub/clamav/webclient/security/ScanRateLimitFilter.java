package info.trizub.clamav.webclient.security;

import info.trizub.clamav.webclient.service.ApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Conservative per-client scan submission limiter; per instance, not distributed. */
@Component
public class ScanRateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, AtomicInteger calls) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    public ScanRateLimitFilter(@Value("${api.v1.scan-rate-limit-per-minute:30}") int limit) { this.limit = Math.max(1, limit); }
    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) return true;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return !(path.equals("/api/scan/upload") || path.equals("/api/scan/path") ||
            path.equals("/scan/upload") || path.equals("/scan/path") || path.startsWith("/api/v1/scans/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60000L;
        String key = req.getRemoteAddr();
        String auth = req.getHeader("Authorization");
        if (auth != null) key += ":" + ApiCredentialService.sha256(auth);
        final long now = minute;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != now ? new Window(now, new AtomicInteger(1)) : new Window(now, new AtomicInteger(old.calls().incrementAndGet())));
        if (windows.size() > 20000) windows.entrySet().removeIf(e -> e.getValue().minute() < minute - 2);
        if (w.calls().get() > limit) {
            res.setStatus(429); res.setContentType("application/json"); res.setHeader("Retry-After", "60");
            res.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Scan submission limit exceeded\"}"); return;
        }
        chain.doFilter(req, res);
    }
}
