package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import xyz.capybara.clamav.Platform;
import xyz.capybara.clamav.commands.scan.result.ScanResult;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real Unix sockets and wire framing, with a synthetic daemon instead of a mock client. */
class UnixClamdClientTest {
    @TempDir Path dir;
    interface Connection { void run(DataInputStream input,OutputStream output) throws Exception; }
    interface ClientAction { void run(UnixClamdClient client) throws Exception; }

    @BeforeEach void unixSocketsArePermitted() throws IOException {
        try (var probe=ServerSocketChannel.open(StandardProtocolFamily.UNIX)) { /* Capability probe only. */ }
        catch (SocketException ex) {
            if (ex.getMessage()!=null && ex.getMessage().contains("Operation not permitted"))
                Assumptions.abort("This execution environment prohibits AF_UNIX sockets; wire-protocol unit tests still run.");
            throw ex;
        }
    }

    private void exchange(Connection connection, ClientAction action) throws Exception {
        Path socket=dir.resolve(UUID.randomUUID()+".sock");
        var executor=Executors.newSingleThreadExecutor();
        try (var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Future<?> daemon=executor.submit(() -> {
                try (var accepted=server.accept();var in=new DataInputStream(Channels.newInputStream(accepted));var out=Channels.newOutputStream(accepted)) {
                    connection.run(in,out);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            action.run(new UnixClamdClient(socket.toString(),500,1000));
            daemon.get(3,TimeUnit.SECONDS);
        } finally { executor.shutdownNow();Files.deleteIfExists(socket); }
    }

    private static String request(DataInputStream input) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
        while ((value=input.read())>0) bytes.write(value);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void pingVersionAndStatsUseTheMountedSocketAndCorrectTerminators() throws Exception {
        exchange((in,out) -> { assertThat(request(in)).isEqualTo("zPING");out.write("PONG\0".getBytes(StandardCharsets.UTF_8)); }, UnixClamdClient::ping);
        exchange((in,out) -> { assertThat(request(in)).isEqualTo("zVERSION");out.write("ClamAV 1.5.4/27920/date\0".getBytes(StandardCharsets.UTF_8)); },
            client -> assertThat(client.version()).isEqualTo("ClamAV 1.5.4/27920/date"));
        exchange((in,out) -> { assertThat(request(in)).isEqualTo("zSTATS");out.write("POOLS: 1\nQUEUE: 0 items\nEND\0".getBytes(StandardCharsets.UTF_8)); },
            client -> assertThat(client.stats()).contains("QUEUE: 0 items","END"));
        assertThat(new ClamavClientProvider().clientFor(new ClamdEndpoint("default","/run/clamd.sock",0,Platform.UNIX)))
            .isInstanceOf(UnixClamdClient.class);
    }

    @Test void instreamSendsLengthPrefixedChunksAndAZeroTerminatorThenReturnsDetections() throws Exception {
        byte[] payload=new byte[100_000];Arrays.fill(payload,(byte)65);
        exchange((in,out) -> {
            assertThat(request(in)).isEqualTo("zINSTREAM");
            ByteArrayOutputStream received=new ByteArrayOutputStream();int length;
            while ((length=in.readInt())!=0) { assertThat(length).isBetween(1,64*1024);received.write(in.readNBytes(length)); }
            assertThat(received.toByteArray()).isEqualTo(payload);
            out.write("stream: Win.Test.EICAR_HDB-1 FOUND\0".getBytes(StandardCharsets.UTF_8));
        },client -> {
            ScanResult result=client.scan(new ByteArrayInputStream(payload));
            assertThat(result).isInstanceOf(ScanResult.VirusFound.class);
            assertThat(((ScanResult.VirusFound)result).getFoundViruses().get("stream")).containsExactly("Win.Test.EICAR_HDB-1");
        });
    }

    @Test void multiscanReadsAllNullDelimitedResultsUntilConnectionClose() throws Exception {
        exchange((in,out) -> {
            assertThat(request(in)).isEqualTo("zMULTISCAN /scan directory");
            out.write("/scan directory/a: Test.One FOUND\0/scan directory/b: Test.Two FOUND\0".getBytes(StandardCharsets.UTF_8));
        },client -> assertThat(((ScanResult.VirusFound)client.parallelScan(Path.of("/scan directory"))).getFoundViruses()).hasSize(2));
    }

    @Test void cleanErrorsAndEmptyRepliesAreDistinct() throws Exception {
        exchange((in,out) -> { request(in);assertThat(in.readInt()).isZero();out.write("stream: OK\0".getBytes(StandardCharsets.UTF_8)); },
            client -> assertThat(client.scan(new ByteArrayInputStream(new byte[0]))).isSameAs(ScanResult.OK.INSTANCE));
        exchange((in,out) -> { request(in);out.write("/file: Access denied ERROR\0".getBytes(StandardCharsets.UTF_8)); },
            client -> assertThatThrownBy(() -> client.parallelScan(Path.of("/file"))).isInstanceOf(IOException.class).hasMessageContaining("Access denied"));
        exchange((in,out) -> { request(in); },
            client -> assertThatThrownBy(client::version).isInstanceOf(IOException.class).hasMessageContaining("Empty"));
    }

    @Test void nonResponsiveSocketTimesOutAndClosesWithoutHangingAWorker() throws Exception {
        exchange((in,out) -> { request(in);assertThat(in.read()).isEqualTo(-1); },
            client -> assertThatThrownBy(client::version).isInstanceOf(SocketTimeoutException.class));
    }
}
