package info.trizub.clamav.webclient.service;

import java.io.IOException;
import java.nio.file.Path;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/** The same bounded wire protocol as UnixClamdClient, without the library's unbounded TCP sockets. */
final class TcpClamdClient extends SocketClamdClient {
    private final Platform platform;
    TcpClamdClient(String host, int port, Platform platform, long connectTimeoutMillis, long commandTimeoutMillis,
                   long scanTimeoutMillis, long probeDeadline, ClamdAddressResolver resolver) {
        super(connectTimeoutMillis, commandTimeoutMillis, scanTimeoutMillis, probeDeadline,
                deadline -> NioClamdConnection.tcp(resolver.resolve(host, port, deadline), deadline));
        this.platform = platform == null ? Platform.JVM_PLATFORM : platform;
    }
    @Override public ScanResult parallelScan(Path path) throws IOException { return scanPath(platform.toServerPath(path)); }
}
