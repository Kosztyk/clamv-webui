package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.web.NativeClamdtopWebSocket;
import info.trizub.clamav.webclient.security.SessionWebSocketGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registered with same-origin handshake policy; Spring Security checks the login session. */
@Configuration
@EnableWebSocket
public class NativeClamdtopWebSocketConfig implements WebSocketConfigurer {
    private final NativeClamdtopWebSocket handler;
    private final SessionWebSocketGuard sessions;
    public NativeClamdtopWebSocketConfig(NativeClamdtopWebSocket handler, SessionWebSocketGuard sessions){
        this.handler=handler;this.sessions=sessions;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessions.decorate(handler),"/ws/clamdtop").addInterceptors(sessions); // no wildcard origins
    }
}
