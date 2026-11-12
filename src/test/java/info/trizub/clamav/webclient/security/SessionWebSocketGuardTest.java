package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.web.socket.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionWebSocketGuardTest {
    final SessionAccessService access = mock(SessionAccessService.class);
    final SessionWebSocketGuard guard = new SessionWebSocketGuard(access);
    final WebSocketHandler delegate = mock(WebSocketHandler.class);
    final WebSocketHandler handler = guard.decorate(delegate);
    final MockHttpSession session = new MockHttpSession();
    final Authentication auth = UsernamePasswordAuthenticationToken.authenticated("test", null, java.util.List.of());

    WebSocketSession connect() throws Exception {
        var request = new MockHttpServletRequest(); request.setSession(session);
        Map<String, Object> attributes = new HashMap<>();
        assertThat(guard.beforeHandshake(new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()), handler, attributes)).isTrue();
        var socket = mock(WebSocketSession.class); var open = new AtomicBoolean(true);
        when(socket.getId()).thenReturn("socket-1"); when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getPrincipal()).thenReturn(auth); when(socket.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> { open.set(false); return null; }).when(socket).close(any(CloseStatus.class));
        when(access.valid(auth, session)).thenReturn(true);
        handler.afterConnectionEstablished(socket); verify(delegate).afterConnectionEstablished(socket); return socket;
    }
    @Test void explicitRevocationClosesAlreadyUpgradedConnection() throws Exception {
        var socket = connect(); guard.revoked(new SessionRevoked(session.getId()));
        verify(socket).close(argThat(status -> status.getCode() == 1008)); assertThat(socket.isOpen()).isFalse();
    }
    @Test void logoutOrIdleSessionDestructionClosesConnection() throws Exception {
        var socket = connect(); guard.destroyed(new HttpSessionDestroyedEvent(session));
        assertThat(socket.isOpen()).isFalse();
    }
    @Test void absoluteExpiryOrAccountChangeClosesIdleConnectionDuringPeriodicCheck() throws Exception {
        var socket = connect(); when(access.valid(auth, session)).thenReturn(false); guard.validateConnections();
        assertThat(socket.isOpen()).isFalse();
    }
    @Test void inputIsBlockedAsSoonAsSessionBecomesInvalid() throws Exception {
        var socket = connect(); when(access.valid(auth, session)).thenReturn(false);
        var message = new TextMessage("input"); handler.handleMessage(socket, message);
        verify(delegate, never()).handleMessage(any(), any()); assertThat(socket.isOpen()).isFalse();
    }
    @Test void databaseFailureClosesConnection() throws Exception {
        var socket = connect(); when(access.valid(auth, session)).thenThrow(new IllegalStateException("unavailable"));
        guard.validateConnections(); assertThat(socket.isOpen()).isFalse();
    }
    @Test void anonymousHandshakeCannotCreateSession() {
        var request = new MockHttpServletRequest();
        assertThat(guard.beforeHandshake(new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()), handler, new HashMap<>())).isFalse();
        assertThat(request.getSession(false)).isNull();
    }
}
