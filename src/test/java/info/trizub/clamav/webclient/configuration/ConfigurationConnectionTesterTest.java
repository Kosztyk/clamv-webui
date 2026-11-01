package info.trizub.clamav.webclient.configuration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ConfigurationConnectionTesterTest {
    @Test void daemonTestUsesPingAndDoesNotExecuteAScan() throws Exception {
        try(var server=new ServerSocket(0);var tester=new ConfigurationConnectionTester()) {
            var received=new CompletableFuture<String>();
            Thread peer=new Thread(()->{try(var socket=server.accept()) {
                received.complete(new String(socket.getInputStream().readNBytes(6),StandardCharsets.US_ASCII));
                socket.getOutputStream().write("PONG\0".getBytes(StandardCharsets.US_ASCII));
            } catch(Exception e) {received.completeExceptionally(e);} });peer.setDaemon(true);peer.start();
            var values=new HashMap<>(ConfigurationCatalog.defaults());values.put("clamav.service.host","127.0.0.1");values.put("clamav.service.port",Integer.toString(server.getLocalPort()));
            assertThat(tester.test("clamd",values)).containsEntry("ok",true);
            assertThat(received.get(1,TimeUnit.SECONDS)).isEqualTo("zPING\0");
        }
    }
    @Test void hungDaemonReadIsBoundedAndErrorsDoNotExposeConnectionValues() throws Exception {
        try(var server=new ServerSocket(0);var tester=new ConfigurationConnectionTester()) {
            var release=new CountDownLatch(1);
            Thread peer=new Thread(()->{try(var socket=server.accept()) {release.await(6,TimeUnit.SECONDS);}catch(Exception ignored){} });peer.setDaemon(true);peer.start();
            var values=new HashMap<>(ConfigurationCatalog.defaults());values.put("clamav.service.host","127.0.0.1");values.put("clamav.service.port",Integer.toString(server.getLocalPort()));
            long start=System.nanoTime();
            try {assertThatThrownBy(()->tester.test("clamd",values)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("127.0.0.1");}
            finally {release.countDown();}
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(6000);
        }
    }
    @Test void restConnectionTestDoesNotFollowRedirectsOrSendCredentialsToTheirTargets() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);AtomicInteger target=new AtomicInteger();
        server.createContext("/stats",exchange->{exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{target.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try(var tester=new ConfigurationConnectionTester()) {
            var values=new HashMap<>(ConfigurationCatalog.defaults());values.put("CLAMAV_REST_URL","http://127.0.0.1:"+server.getAddress().getPort());values.put("CLAMAV_REST_STATS_PATH","/stats");values.put("CLAMAV_REST_API_KEY","private-test-key");
            assertThatThrownBy(()->tester.test("rest",values)).hasMessageNotContaining("private-test-key");
            assertThat(target).hasValue(0);
        } finally {server.stop(0);}
    }
    @Test void databaseTestDoesNotCreateAnH2FileOrRunMigrations() {
        Path path=Path.of("data","connection-test-"+UUID.randomUUID());
        try(var tester=new ConfigurationConnectionTester()) {
            var values=new HashMap<>(ConfigurationCatalog.defaults());values.put("spring.datasource.url","jdbc:h2:file:./"+path);
            assertThat(tester.test("database",values)).containsEntry("ok",false).containsEntry("status","not_initialized");
            assertThat(Path.of(path+".mv.db")).doesNotExist();
        }
    }
}
