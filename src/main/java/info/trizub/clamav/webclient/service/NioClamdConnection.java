package info.trizub.clamav.webclient.service;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Nonblocking sockets make both reads and writes cancellable at a finite deadline. */
final class NioClamdConnection implements SocketClamdClient.WireConnection {
    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private NioClamdConnection(SocketChannel channel, Selector selector, SelectionKey key) {
        this.channel = channel; this.selector = selector; this.key = key;
    }
    static NioClamdConnection tcp(InetSocketAddress address, long deadline) throws IOException {
        return open(SocketChannel.open(), address, deadline);
    }
    static NioClamdConnection unix(Path path, long deadline) throws IOException {
        return open(SocketChannel.open(StandardProtocolFamily.UNIX), UnixDomainSocketAddress.of(path), deadline);
    }
    private static NioClamdConnection open(SocketChannel channel, SocketAddress address, long deadline) throws IOException {
        Selector selector = null;
        try {
            SocketClamdClient.checkDeadline(deadline);
            selector = Selector.open();
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            var connection = new NioClamdConnection(channel, selector, key);
            if (!channel.connect(address)) {
                while (!channel.finishConnect()) connection.await(SelectionKey.OP_CONNECT, deadline);
            }
            SocketClamdClient.checkDeadline(deadline);
            return connection;
        } catch (IOException | RuntimeException ex) {
            try { channel.close(); } finally { if (selector != null) selector.close(); }
            throw ex;
        }
    }
    @Override public int read(ByteBuffer bytes) throws IOException { return channel.read(bytes); }
    @Override public int write(ByteBuffer bytes) throws IOException { return channel.write(bytes); }
    @Override public void await(int operation, long deadline) throws IOException {
        SocketClamdClient.checkDeadline(deadline);
        key.interestOps(operation);
        selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        selector.selectedKeys().clear();
        SocketClamdClient.checkDeadline(deadline);
    }
    @Override public void close() throws IOException { try { channel.close(); } finally { selector.close(); } }
}
