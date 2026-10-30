package info.trizub.clamav.webclient.service;

import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native ClamD wire protocol over Java's Unix-domain sockets; no local TCP proxy. */
final class UnixClamdClient implements ClamdClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final Path socket;
    private final long commandTimeoutMillis;
    private final long scanTimeoutMillis;
    private final ConnectionFactory connections;

    interface WireConnection extends AutoCloseable {
        int read(ByteBuffer bytes) throws IOException;
        int write(ByteBuffer bytes) throws IOException;
        void await(int operation,long deadline) throws IOException;
        @Override void close() throws IOException;
    }
    interface ConnectionFactory { WireConnection open(Path socket,long timeout) throws IOException; }

    UnixClamdClient(String socket) { this(socket,10_000,TimeUnit.HOURS.toMillis(24)); }
    UnixClamdClient(String socket, long commandTimeoutMillis, long scanTimeoutMillis) {
        this(socket,commandTimeoutMillis,scanTimeoutMillis,NioConnection::open);
    }
    UnixClamdClient(String socket, long commandTimeoutMillis, long scanTimeoutMillis, ConnectionFactory connections) {
        this.socket=Path.of(socket);
        this.commandTimeoutMillis=commandTimeoutMillis;
        this.scanTimeoutMillis=scanTimeoutMillis;
        this.connections=connections;
    }

    @Override public void ping() throws IOException {
        if (!"PONG".equals(command("PING",null,commandTimeoutMillis).trim())) throw new IOException("Unexpected ClamD PING response.");
    }
    @Override public String version() throws IOException { return command("VERSION",null,commandTimeoutMillis); }
    @Override public String stats() throws IOException { return command("STATS",null,commandTimeoutMillis); }
    @Override public ScanResult scan(InputStream input) throws IOException { return parseScan(command("INSTREAM",input,scanTimeoutMillis)); }
    @Override public ScanResult parallelScan(Path path) throws IOException {
        String target=path.toString();
        if (target.indexOf('\0')>=0 || target.indexOf('\n')>=0 || target.indexOf('\r')>=0) throw new IOException("Unsupported scan path characters.");
        return parseScan(command("MULTISCAN " + target,null,scanTimeoutMillis));
    }

    private String command(String command, InputStream input, long readTimeout) throws IOException {
        try (WireConnection channel=connections.open(socket,commandTimeoutMillis)) {
            write(channel,ByteBuffer.wrap(("z"+command+'\0').getBytes(StandardCharsets.UTF_8)));
            if (input!=null) {
                byte[] chunk=new byte[64*1024];
                int count;
                while ((count=input.read(chunk))!=-1) {
                    if (count==0) continue;
                    write(channel,ByteBuffer.allocate(4).putInt(count).flip());
                    write(channel,ByteBuffer.wrap(chunk,0,count));
                }
                write(channel,ByteBuffer.allocate(4));
            }
            return read(channel,readTimeout,command.equals("STATS"),command.startsWith("MULTISCAN "));
        }
    }

    private void write(WireConnection channel, ByteBuffer bytes) throws IOException {
        long deadline=deadline(commandTimeoutMillis);
        while (bytes.hasRemaining()) {
            if (channel.write(bytes)==0) channel.await(SelectionKey.OP_WRITE,deadline);
            else deadline=deadline(commandTimeoutMillis);
        }
    }

    private static String read(WireConnection channel, long timeout, boolean stats, boolean multi) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        ByteBuffer bytes=ByteBuffer.allocate(8192);
        long deadline=deadline(timeout);
        while (true) {
            int count=channel.read(bytes);
            if (count<0) break;
            if (count==0) { channel.await(SelectionKey.OP_READ,deadline); continue; }
            deadline=deadline(timeout);
            bytes.flip();
            boolean end=false;
            while (bytes.hasRemaining()) {
                int value=bytes.get()&255;
                if (value==0) {
                    if (!stats && !multi) { end=true; break; }
                    value='\n';
                }
                output.write(value);
                if (output.size()>MAX_RESPONSE_BYTES) throw new IOException("ClamD response exceeds the size limit.");
            }
            bytes.clear();
            String text=output.toString(StandardCharsets.UTF_8);
            if (end || (stats && (text.endsWith("\nEND\n") || text.equals("END\n")))) break;
        }
        String response=output.toString(StandardCharsets.UTF_8).trim();
        if (response.isEmpty()) throw new IOException("Empty ClamD response.");
        return response;
    }

    private static long deadline(long millis) { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis); }
    private record NioConnection(SocketChannel channel, Selector selector, SelectionKey key) implements WireConnection {
        static NioConnection open(Path socket,long timeout) throws IOException {
            SocketChannel channel=SocketChannel.open(StandardProtocolFamily.UNIX);
            Selector selector=null;
            try {
                selector=Selector.open();
                channel.configureBlocking(false);
                SelectionKey key=channel.register(selector,SelectionKey.OP_CONNECT);
                NioConnection connection=new NioConnection(channel,selector,key);
                if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                    long deadline=deadline(timeout);
                    while (!channel.finishConnect()) connection.await(SelectionKey.OP_CONNECT,deadline);
                }
                return connection;
            } catch (IOException | RuntimeException ex) {
                channel.close();if (selector!=null) selector.close();throw ex;
            }
        }
        public int read(ByteBuffer bytes) throws IOException { return channel.read(bytes); }
        public int write(ByteBuffer bytes) throws IOException { return channel.write(bytes); }
        public void await(int operation,long deadline) throws IOException {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) throw new java.net.SocketTimeoutException("ClamD Unix-socket operation timed out.");
            key.interestOps(operation);
            selector.select(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            selector.selectedKeys().clear();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ClamD command interrupted.");
        }
        public void close() throws IOException { try { channel.close(); } finally { selector.close(); } }
    }

    private static ScanResult parseScan(String response) throws IOException {
        Map<String,Collection<String>> found=new LinkedHashMap<>();
        boolean ok=false;
        for (String raw:response.split("\\R")) {
            String line=raw.trim();
            if (line.endsWith(" FOUND")) {
                int separator=line.lastIndexOf(": ");
                if (separator<1) throw new IOException("Malformed ClamD detection response.");
                found.computeIfAbsent(line.substring(0,separator),k->new LinkedHashSet<>())
                    .add(line.substring(separator+2,line.length()-6));
            } else if (line.endsWith(" OK")) ok=true;
            else if (!line.isBlank()) throw new IOException("ClamD scan failed: "+line);
        }
        if (!found.isEmpty()) return new ScanResult.VirusFound(found);
        if (!ok) throw new IOException("ClamD returned no scan verdict.");
        return ScanResult.OK.INSTANCE;
    }
}
