package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.config.ClamdClientProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClamavClientProvider implements AutoCloseable {
    private final ClamdClientProperties properties;
    private final ClamdAddressResolver resolver;
    public ClamavClientProvider() { this(new ClamdClientProperties()); }
    @Autowired public ClamavClientProvider(ClamdClientProperties properties) {
        this.properties = properties;
        this.resolver = new ClamdAddressResolver();
    }
    public ClamdClient clientFor(ClamdEndpoint endpoint) { return create(endpoint, 0); }
    /** One shared deadline for PING + VERSION + STATS, including all DNS/connect/read/write work. */
    public ClamdClient probeFor(ClamdEndpoint endpoint) {
        return create(endpoint, SocketClamdClient.deadline(properties.getCommandTimeout().toMillis()));
    }
    private ClamdClient create(ClamdEndpoint endpoint, long probeDeadline) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getHost().isBlank())
            throw new IllegalArgumentException("ClamD endpoint host is required");
        String host = endpoint.getHost().trim();
        long connect = properties.getConnectTimeout().toMillis();
        long command = properties.getCommandTimeout().toMillis();
        long scan = properties.getScanTimeout().toMillis();
        if (host.startsWith("/")) return new UnixClamdClient(host, connect, command, scan, probeDeadline);
        return new TcpClamdClient(host, endpoint.getPort(), endpoint.getPlatform(), connect, command, scan, probeDeadline, resolver);
    }
    @Override @PreDestroy public void close() { resolver.close(); }
}
