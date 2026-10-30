package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.web.NativeClamdtopWebSocket;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registered with same-origin handshake policy; Spring Security checks the login session. */
@Configuration
@EnableWebSocket
public class NativeClamdtopWebSocketConfig implements WebSocketConfigurer {
    private final NativeClamdtopWebSocket handler;
    public NativeClamdtopWebSocketConfig(NativeClamdtopWebSocket handler){this.handler=handler;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler,"/ws/clamdtop"); // no wildcard origins
    }
}
