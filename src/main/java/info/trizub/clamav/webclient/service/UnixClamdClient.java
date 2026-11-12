package info.trizub.clamav.webclient.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Native Unix sockets share the TCP client's framing, limits and monotonic deadlines. */
final class UnixClamdClient extends SocketClamdClient {
    interface ConnectionFactory { WireConnection open(Path socket, long timeout) throws IOException; }
    UnixClamdClient(String socket) { this(socket, 3000, 5000, TimeUnit.HOURS.toMillis(24), 0); }
    UnixClamdClient(String socket, long commandTimeoutMillis, long scanTimeoutMillis) {
        this(socket, commandTimeoutMillis, commandTimeoutMillis, scanTimeoutMillis, 0);
    }
    UnixClamdClient(String socket, long connectTimeoutMillis, long commandTimeoutMillis, long scanTimeoutMillis, long probeDeadline) {
        super(connectTimeoutMillis, commandTimeoutMillis, scanTimeoutMillis, probeDeadline,
                deadline -> NioClamdConnection.unix(Path.of(socket), deadline));
    }
    UnixClamdClient(String socket, long commandTimeoutMillis, long scanTimeoutMillis, ConnectionFactory connections) {
        super(commandTimeoutMillis, commandTimeoutMillis, scanTimeoutMillis, 0,
                deadline -> connections.open(Path.of(socket), remainingMillis(deadline)));
    }
}
