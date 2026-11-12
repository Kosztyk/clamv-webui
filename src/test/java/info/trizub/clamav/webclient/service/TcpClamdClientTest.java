package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdClientProperties;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.junit.jupiter.api.Test;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real TCP peers exercise production sockets, rather than replacing the network client with a mock. */
class TcpClamdClientTest {
    interface Peer { void run(DataInputStream input, OutputStream output) throws Exception; }
    interface Action { void run(TcpClamdClient client) throws Exception; }
    private void exchange(Peer peer, Action action) throws Exception { exchange(1000, 3000, peer, action); }
    private void exchange(long commandMillis, long scanMillis, Peer peer, Action action) throws Exception {
        exchange(Platform.UNIX, commandMillis, scanMillis, peer, action);
    }
    private void exchange(Platform platform, long commandMillis, long scanMillis, Peer peer, Action action) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); var resolver = new ClamdAddressResolver()) {
            server.setSoTimeout(4000);
            Future<?> daemon = worker.submit(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream()); var output = socket.getOutputStream()) {
                    socket.setSoTimeout(4000);
                    peer.run(input, output);
                } catch (SocketException expectedDisconnect) { /* Timeout/cap tests close the client while the peer is writing. */ }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            action.run(new TcpClamdClient("127.0.0.1", server.getLocalPort(), platform, 1000, commandMillis, scanMillis, 0, resolver));
            daemon.get(5, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
    private static String request(DataInputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) > 0 && value != '\n') bytes.write(value);
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private static void send(OutputStream output, String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8)); output.flush(); }

    @Test void pingVersionAndStatsHonorFramingAndDoNotWaitForEofAfterEnd() throws Exception {
        exchange((in, out) -> { assertThat(request(in)).isEqualTo("zPING"); send(out, "PONG\0"); assertThat(in.read()).isEqualTo(-1); }, TcpClamdClient::ping);
        exchange((in, out) -> {
            assertThat(request(in)).isEqualTo("zVERSION");
            for (byte value : "ClamAV 1.4.6/27920/date\0".getBytes(StandardCharsets.UTF_8)) out.write(value);
        }, client -> assertThat(client.version()).isEqualTo("ClamAV 1.4.6/27920/date"));
        exchange((in, out) -> {
            assertThat(request(in)).isEqualTo("zSTATS");
            send(out, "POOLS: 1\0THREADS: live 5 idle 3 max 10\nQUEUE: 0 items\0END\0");
            assertThat(in.read()).isEqualTo(-1);
        }, client -> assertThat(client.stats()).contains("QUEUE: 0 items\nEND"));
        exchange((in, out) -> { request(in); send(out, "POOLS: 1\r\nEND\r\n"); assertThat(in.read()).isEqualTo(-1); },
                client -> assertThat(client.stats()).contains("POOLS: 1"));
    }

    @Test void tcpRetainsTheConfiguredWindowsPathConversion() throws Exception {
        exchange(Platform.WINDOWS, 1000, 3000, (in, out) -> {
            assertThat(request(in)).isEqualTo("zMULTISCAN \\scan directory\\sample.txt");
            send(out, "file: OK\0");
        }, client -> assertThat(client.parallelScan(Path.of("/scan directory/sample.txt"))).isSameAs(ScanResult.OK.INSTANCE));
    }

    @Test void instreamUsesNetworkByteOrderAndPreservesDetectionSignatures() throws Exception {
        byte[] payload = new byte[100_000]; Arrays.fill(payload, (byte) 65);
        exchange((in, out) -> {
            assertThat(request(in)).isEqualTo("zINSTREAM");
            var received = new ByteArrayOutputStream(); int count;
            while ((count = in.readInt()) != 0) {
                assertThat(count).isBetween(1, 64 * 1024);
                received.write(in.readNBytes(count));
            }
            assertThat(received.toByteArray()).isEqualTo(payload);
            send(out, "stream: Test.EICAR FOUND\0");
        }, client -> {
            var result = (ScanResult.VirusFound) client.scan(new ByteArrayInputStream(payload));
            assertThat(result.getFoundViruses().get("stream")).containsExactly("Test.EICAR");
        });
    }

    @Test void multiscanCollectsAllRecordsAndRejectsMalformedOrIncompleteVerdicts() throws Exception {
        exchange((in, out) -> {
            assertThat(request(in)).isEqualTo("zMULTISCAN /scan directory");
            send(out, "/scan/a: Test.One FOUND\0/scan/b: Test.Two FOUND\0");
        }, client -> assertThat(((ScanResult.VirusFound) client.parallelScan(Path.of("/scan directory"))).getFoundViruses()).hasSize(2));
        for (String response : List.of("", "garbage\0", "/scan/a: Access denied ERROR\0", "/scan/a: OK\0/scan/b: denied ERROR\0")) {
            exchange((in, out) -> { request(in); send(out, response); }, client ->
                    assertThatThrownBy(() -> client.parallelScan(Path.of("/scan/a"))).isInstanceOf(IOException.class));
        }
        exchange((in, out) -> { request(in); send(out, "/scan/a: OK\0"); }, client ->
                assertThat(client.parallelScan(Path.of("/scan/a"))).isSameAs(ScanResult.OK.INSTANCE));
    }

    @Test void nonRespondingPeerTimesOutAndSeesTheConnectionClose() throws Exception {
        exchange(150, 3000, (in, out) -> { assertThat(request(in)).isEqualTo("zPING"); assertThat(in.read()).isEqualTo(-1); }, client -> {
            long start = System.nanoTime();
            assertThatThrownBy(client::ping).isInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        });
    }

    @Test void trickledRepliesCannotExtendTheTotalCommandDeadline() throws Exception {
        exchange(150, 3000, (in, out) -> {
            request(in);
            for (int i = 0; i < 100; i++) { out.write('A'); out.flush(); Thread.sleep(20); }
        }, client -> {
            long start = System.nanoTime();
            assertThatThrownBy(client::version).isInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        });
    }

    @Test void scanHasItsOwnLongerBudgetAndAlsoTimesOut() throws Exception {
        exchange(50, 500, (in, out) -> {
            request(in); assertThat(in.readInt()).isZero(); Thread.sleep(100); send(out, "stream: OK\0");
        }, client -> assertThat(client.scan(new ByteArrayInputStream(new byte[0]))).isSameAs(ScanResult.OK.INSTANCE));
        exchange(50, 150, (in, out) -> { request(in); assertThat(in.read()).isEqualTo(-1); }, client ->
                assertThatThrownBy(() -> client.parallelScan(Path.of("/scan/a"))).isInstanceOf(SocketTimeoutException.class));
    }

    @Test void aPeerThatStopsReadingCannotBlockAnInstreamWriteIndefinitely() throws Exception {
        var finished = new CountDownLatch(1);
        exchange(150, 3000, (in, out) -> { request(in); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue(); }, client -> {
            var payload = new InputStream() {
                long remaining = 64L * 1024 * 1024;
                @Override public int read() { return remaining-- > 0 ? 65 : -1; }
                @Override public int read(byte[] bytes, int off, int len) {
                    int count = (int) Math.min(remaining, len);
                    if (count == 0) return -1;
                    Arrays.fill(bytes, off, off + count, (byte) 65); remaining -= count; return count;
                }
            };
            long start = System.nanoTime();
            try {
                assertThatThrownBy(() -> client.scan(payload)).isInstanceOf(SocketTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            } finally { finished.countDown(); }
        });
    }

    @Test void oversizedRepliesAreCappedAndPathCommandInjectionIsRejectedBeforeConnecting() throws Exception {
        exchange((in, out) -> {
            request(in); byte[] oversized = new byte[SocketClamdClient.MAX_RESPONSE_BYTES + 1];
            Arrays.fill(oversized, (byte) 'A'); out.write(oversized);
        }, client -> {
            assertThatThrownBy(client::version).isInstanceOf(IOException.class).hasMessageContaining("size limit");
        });
        try (var provider = new ClamavClientProvider()) {
            var client = provider.clientFor(new ClamdEndpoint("test", "127.0.0.1", 1, Platform.UNIX));
            assertThatThrownBy(() -> client.parallelScan(Path.of("/scan\ninjected")))
                    .isInstanceOf(IOException.class).hasMessageContaining("Unsupported scan path");
        }
    }

    @Test void aProbeSharesOneBudgetAcrossAllCommands() throws Exception {
        var properties = new ClamdClientProperties(); properties.setCommandTimeout(Duration.ofMillis(250));
        var worker = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); var provider = new ClamavClientProvider(properties)) {
            server.setSoTimeout(3000);
            var peer = worker.submit(() -> {
                try {
                    for (String response : List.of("PONG\0", "ClamAV test\0")) {
                        try (var socket = server.accept()) { request(new DataInputStream(socket.getInputStream())); Thread.sleep(160); send(socket.getOutputStream(), response); }
                    }
                } catch (SocketException expectedDisconnect) { }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var client = provider.probeFor(new ClamdEndpoint("slow", "127.0.0.1", server.getLocalPort(), Platform.UNIX));
            long start = System.nanoTime(); client.ping();
            assertThatThrownBy(client::version).isInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(400));
            peer.get(3, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    @Test void stalledDnsHasBoundedCapacityAndDoesNotBlockLiteralAddresses() throws Exception {
        var release = new CountDownLatch(1); var started = new CountDownLatch(2); var calls = new AtomicInteger();
        var callers = Executors.newFixedThreadPool(20);
        try (var resolver = new ClamdAddressResolver(host -> {
            calls.incrementAndGet(); started.countDown();
            boolean done = false;
            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { /* Model a native resolver ignoring interruption. */ }
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        })) {
            var requests = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) requests.add(callers.submit(() -> {
                assertThatThrownBy(() -> resolver.resolve("unresponsive.example.test", 3310, SocketClamdClient.deadline(200)))
                        .isInstanceOf(IOException.class);
            }));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(resolver.resolve("127.0.0.1", 3310, SocketClamdClient.deadline(100)).getAddress().isLoopbackAddress()).isTrue();
            for (var request : requests) request.get(2, TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(2);
        } finally { release.countDown(); callers.shutdownNow(); }
    }
}
