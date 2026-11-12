package info.trizub.clamav.webclient.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import java.io.IOException;

public final class SessionRejectionHandler {
    private SessionRejectionHandler() { }
    public static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        new SecurityContextLogoutHandler().logout(request, response,
            SecurityContextHolder.getContext().getAuthentication());
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/") || path.equals("/ws/clamdtop") ||
                "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"session_expired\",\"message\":\"Your session ended. Sign in again.\"}");
        } else response.sendRedirect(request.getContextPath() + "/login?expired");
    }
}
