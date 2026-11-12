package info.trizub.clamav.webclient.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP-session revocation must also end already-upgraded native monitor connections. */
@Component
public class SessionWebSocketGuard implements HandshakeInterceptor {
    private static final String HTTP_SESSION = SessionWebSocketGuard.class.getName() + ".httpSession";
    private record Active(WebSocketSession socket, HttpSession session, Authentication authentication) { }
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final SessionAccessService sessions;
    public SessionWebSocketGuard(SessionAccessService sessions) { this.sessions = sessions; }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return false;
        HttpSession session = servlet.getServletRequest().getSession(false);
        if (session == null) return false;
        attributes.put(HTTP_SESSION, session);
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }

    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                Object attribute = socket.getAttributes().get(HTTP_SESSION);
                if (!(attribute instanceof HttpSession session) || !(socket.getPrincipal() instanceof Authentication auth)) {
                    close(socket); return;
                }
                Active connection = new Active(socket, session, auth);
                active.put(socket.getId(), connection);
                if (!valid(connection)) { active.remove(socket.getId()); close(socket); return; }
                try { super.afterConnectionEstablished(socket); }
                catch (Exception e) { active.remove(socket.getId()); close(socket); throw e; }
            }
            @Override public void handleMessage(WebSocketSession socket, WebSocketMessage<?> message) throws Exception {
                Active connection = active.get(socket.getId());
                if (connection == null || !valid(connection)) { close(socket); return; }
                super.handleMessage(socket, message);
            }
            @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                active.remove(socket.getId());
                super.afterConnectionClosed(socket, status);
            }
        };
    }

    @EventListener public void revoked(SessionRevoked event) { closeSession(event.sessionId()); }
    @EventListener public void destroyed(HttpSessionDestroyedEvent event) { closeSession(event.getId()); }

    private void closeSession(String id) {
        active.forEach((key, connection) -> {
            try {
                if (id.equals(connection.session().getId())) close(connection.socket());
            } catch (IllegalStateException alreadyInvalidated) { close(connection.socket()); }
        });
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void validateConnections() {
        active.forEach((key, connection) -> {
            if (!connection.socket().isOpen() || !valid(connection)) {
                active.remove(key);
                close(connection.socket());
            }
        });
    }

    private boolean valid(Active connection) {
        try { return sessions.valid(connection.authentication(), connection.session()); }
        catch (RuntimeException unavailableOrExpired) { return false; }
    }
    private static void close(WebSocketSession socket) {
        try { if (socket.isOpen()) socket.close(CloseStatus.POLICY_VIOLATION.withReason("Session ended")); }
        catch (IOException | IllegalStateException ignored) { /* Container completes transport cleanup. */ }
    }
}
