package info.trizub.clamav.webclient.service;

import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared bounded ClamD framing, streaming and result parsing for TCP and Unix sockets. */
abstract class SocketClamdClient implements ClamdClient {
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final long connectTimeoutMillis, commandTimeoutMillis, scanTimeoutMillis, probeDeadline;
    private final ConnectionFactory connections;

    interface WireConnection extends AutoCloseable {
        int read(ByteBuffer bytes) throws IOException;
        int write(ByteBuffer bytes) throws IOException;
        void await(int operation, long deadline) throws IOException;
        @Override void close() throws IOException;
    }
    interface ConnectionFactory { WireConnection open(long connectDeadline) throws IOException; }

    SocketClamdClient(long connectTimeoutMillis, long commandTimeoutMillis, long scanTimeoutMillis,
                      long probeDeadline, ConnectionFactory connections) {
        if (connectTimeoutMillis < 1 || commandTimeoutMillis < 1 || scanTimeoutMillis < 1)
            throw new IllegalArgumentException("ClamD timeouts must be positive");
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.commandTimeoutMillis = commandTimeoutMillis;
        this.scanTimeoutMillis = scanTimeoutMillis;
        this.probeDeadline = probeDeadline;
        this.connections = connections;
    }
    @Override public void ping() throws IOException {
        if (!"PONG".equals(command("PING", null, false).trim())) throw new IOException("Unexpected ClamD PING response.");
    }
    @Override public String version() throws IOException { return command("VERSION", null, false); }
    @Override public String stats() throws IOException { return command("STATS", null, false); }
    @Override public ScanResult scan(InputStream input) throws IOException { return parseScan(command("INSTREAM", Objects.requireNonNull(input), true)); }
    @Override public ScanResult parallelScan(Path path) throws IOException { return scanPath(path.toString()); }
    protected ScanResult scanPath(String target) throws IOException {
        if (target.indexOf('\0') >= 0 || target.indexOf('\n') >= 0 || target.indexOf('\r') >= 0)
            throw new IOException("Unsupported scan path characters.");
        return parseScan(command("MULTISCAN " + target, null, true));
    }

    private String command(String command, InputStream input, boolean scan) throws IOException {
        long totalDeadline = scan ? deadline(scanTimeoutMillis) : probeDeadline != 0 ? probeDeadline : deadline(commandTimeoutMillis);
        checkDeadline(totalDeadline);
        long connectDeadline = Math.min(totalDeadline, deadline(connectTimeoutMillis));
        try (WireConnection channel = connections.open(connectDeadline)) {
            write(channel, ByteBuffer.wrap(("z" + command + '\0').getBytes(StandardCharsets.UTF_8)), totalDeadline);
            if (input != null) {
                byte[] chunk = new byte[64 * 1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    checkDeadline(totalDeadline);
                    if (count == 0) continue;
                    write(channel, ByteBuffer.allocate(4).putInt(count).flip(), totalDeadline);
                    write(channel, ByteBuffer.wrap(chunk, 0, count), totalDeadline);
                }
                write(channel, ByteBuffer.allocate(4), totalDeadline);
            }
            return read(channel, totalDeadline, command.equals("STATS"), command.startsWith("MULTISCAN "));
        }
    }

    private void write(WireConnection channel, ByteBuffer bytes, long totalDeadline) throws IOException {
        long writeDeadline = Math.min(totalDeadline, deadline(commandTimeoutMillis));
        while (bytes.hasRemaining()) {
            checkDeadline(writeDeadline);
            if (channel.write(bytes) == 0) channel.await(SelectionKey.OP_WRITE, writeDeadline);
            else writeDeadline = Math.min(totalDeadline, deadline(commandTimeoutMillis));
        }
    }

    private static String read(WireConnection channel, long deadline, boolean stats, boolean multi) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer bytes = ByteBuffer.allocate(8192);
        int recordLength = 0;
        boolean endRecord = true;
        while (true) {
            checkDeadline(deadline);
            int count = channel.read(bytes);
            if (count < 0) break;
            if (count == 0) { channel.await(SelectionKey.OP_READ, deadline); continue; }
            bytes.flip();
            boolean end = false;
            while (bytes.hasRemaining()) {
                int value = bytes.get() & 255;
                if (value == 0) {
                    if (!stats && !multi) { end = true; break; }
                    value = '\n';
                }
                if (output.size() >= MAX_RESPONSE_BYTES) throw new IOException("ClamD response exceeds the size limit.");
                output.write(value);
                if (stats) {
                    if (value == '\n') {
                        if (endRecord && recordLength == 3) { end = true; break; }
                        recordLength = 0;
                        endRecord = true;
                    } else if (value != '\r') {
                        endRecord = endRecord && recordLength < 3 && value == "END".charAt(recordLength);
                        recordLength++;
                    }
                }
            }
            bytes.clear();
            if (end) break;
        }
        String response = output.toString(StandardCharsets.UTF_8).trim();
        if (response.isEmpty()) throw new IOException("Empty ClamD response.");
        return response;
    }

    static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ClamD command interrupted.");
        if (deadline - System.nanoTime() <= 0) throw new SocketTimeoutException("ClamD operation timed out.");
    }
    static long remainingMillis(long deadline) throws IOException {
        checkDeadline(deadline);
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static ScanResult parseScan(String response) throws IOException {
        Map<String, Collection<String>> found = new LinkedHashMap<>();
        boolean ok = false;
        for (String raw : response.split("\\R")) {
            String line = raw.trim();
            if (line.endsWith(" FOUND")) {
                int separator = line.lastIndexOf(": ");
                if (separator < 1) throw new IOException("Malformed ClamD detection response.");
                found.computeIfAbsent(line.substring(0, separator), key -> new LinkedHashSet<>())
                        .add(line.substring(separator + 2, line.length() - 6));
            } else if (line.endsWith(" OK")) ok = true;
            else if (!line.isBlank()) throw new IOException("ClamD scan failed: " + line);
        }
        if (!found.isEmpty()) return new ScanResult.VirusFound(found);
        if (!ok) throw new IOException("ClamD returned no scan verdict.");
        return ScanResult.OK.INSTANCE;
    }
}
