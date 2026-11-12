package info.trizub.clamav.webclient.security;

/** Also closes upgraded WebSocket connections belonging to the revoked HTTP session. */
public record SessionRevoked(String sessionId) { }
