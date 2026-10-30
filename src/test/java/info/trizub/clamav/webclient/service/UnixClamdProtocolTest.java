package info.trizub.clamav.webclient.service;

import org.junit.jupiter.api.Test;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

/** Exercise production framing and parsing even on runtimes that prohibit all socket syscalls. */
class UnixClamdProtocolTest {
    private static class Wire implements UnixClamdClient.WireConnection {
        final ByteBuffer response;
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        boolean firstWrite=true,closed=false;
        Wire(String response) { this.response=ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)); }
        public int read(ByteBuffer bytes) {
            if (!response.hasRemaining()) return -1;
            int size=Math.min(7,Math.min(bytes.remaining(),response.remaining()));
            for (int i=0;i<size;i++) bytes.put(response.get());return size;
        }
        public int write(ByteBuffer bytes) {
            if (firstWrite) { firstWrite=false;return 0; }
            int size=Math.min(13,bytes.remaining());
            for (int i=0;i<size;i++) sent.write(bytes.get());return size;
        }
        public void await(int operation,long deadline) throws IOException { }
        public void close() { closed=true; }
        UnixClamdClient client() {
            return new UnixClamdClient("/run/clamd.sock",50,100,(path,timeout) -> {
                assertThat(path).isEqualTo(Path.of("/run/clamd.sock"));return this;
            });
        }
    }

    @Test void controlsHandleFragmentedResponsesAndPartialWrites() throws Exception {
        var ping=new Wire("PONG\0");ping.client().ping();assertThat(ping.sent.toString(StandardCharsets.UTF_8)).isEqualTo("zPING\0");
        assertThat(ping.closed).isTrue();
        var version=new Wire("ClamAV 1.5.4/27920/date\0");
        assertThat(version.client().version()).isEqualTo("ClamAV 1.5.4/27920/date");
        var stats=new Wire("POOLS: 1\0THREADS: live 5 idle 3 max 10\0QUEUE: 0 items\0END\0");
        assertThat(stats.client().stats()).isEqualTo("POOLS: 1\nTHREADS: live 5 idle 3 max 10\nQUEUE: 0 items\nEND");
    }

    @Test void streamUsesNetworkByteOrderChunksAndZeroLengthEndMarker() throws Exception {
        byte[] payload=new byte[100_000];Arrays.fill(payload,(byte)65);
        var wire=new Wire("stream: Test.Signature FOUND\0");
        var result=(ScanResult.VirusFound)wire.client().scan(new ByteArrayInputStream(payload));
        var sent=new DataInputStream(new ByteArrayInputStream(wire.sent.toByteArray()));
        assertThat(sent.readNBytes(10)).isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream decoded=new ByteArrayOutputStream();int length;
        while ((length=sent.readInt())!=0) decoded.write(sent.readNBytes(length));
        assertThat(decoded.toByteArray()).isEqualTo(payload);assertThat(sent.read()).isEqualTo(-1);
        assertThat(result.getFoundViruses().get("stream")).containsExactly("Test.Signature");assertThat(wire.closed).isTrue();
    }

    @Test void multiscanCollectsEveryNullDelimitedDetectionRatherThanStoppingAtTheFirst() throws Exception {
        var wire=new Wire("/scan/a: Test.One FOUND\0/scan/b: Test.Two FOUND\0");
        var result=(ScanResult.VirusFound)wire.client().parallelScan(Path.of("/scan"));
        assertThat(result.getFoundViruses()).hasSize(2);
        assertThat(wire.sent.toString(StandardCharsets.UTF_8)).isEqualTo("zMULTISCAN /scan\0");
    }

    @Test void errorsEmptyRepliesAndTimeoutsNeverBecomeCleanResultsAndAlwaysClose() throws Exception {
        for (String response:new String[]{"","/file: Access denied ERROR\0","garbage\0"}) {
            var wire=new Wire(response);
            assertThatThrownBy(() -> wire.client().parallelScan(Path.of("/file"))).isInstanceOf(IOException.class);
            assertThat(wire.closed).isTrue();
        }
        var timeout=new Wire("") {
            @Override public int read(ByteBuffer bytes) { return 0; }
            @Override public void await(int operation,long deadline) throws IOException { throw new SocketTimeoutException("Synthetic timeout"); }
        };
        assertThatThrownBy(() -> timeout.client().version()).isInstanceOf(SocketTimeoutException.class);
        assertThat(timeout.closed).isTrue();
        var clean=new Wire("stream: OK\0");
        assertThat(clean.client().scan(new ByteArrayInputStream(new byte[0]))).isSameAs(ScanResult.OK.INSTANCE);
    }
}
