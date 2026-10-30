package info.trizub.clamav.webclient.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import info.trizub.clamav.webclient.service.IntegrationSettingsService;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

/** Independent fail-closed credential gate, including requests on a management listener. */
@Component
@Order(-100)
public class PrometheusAccessFilter extends OncePerRequestFilter {
    private final Supplier<IntegrationSettingsService.ScrapeGate> gate;

    @Autowired public PrometheusAccessFilter(IntegrationSettingsService settings) { gate=settings::scrapeGate; }
    public PrometheusAccessFilter(String token) {
        String normalized=token==null ? "" : token.trim();
        gate=() -> new IntegrationSettingsService.ScrapeGate(normalized.length()>=24,IntegrationSettingsService.hash(normalized));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/actuator/prometheus") && !path.equals("/actuator/prometheus/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String header = request.getHeader("Authorization");
        String supplied = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        var active=gate.get();
        boolean accepted = active.enabled() && !active.hash().isBlank() && !supplied.isBlank() && supplied.length()<=4096
                && MessageDigest.isEqual(active.hash().getBytes(StandardCharsets.UTF_8),
                    IntegrationSettingsService.hash(supplied).getBytes(StandardCharsets.UTF_8));
        if (!accepted) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
