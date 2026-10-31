package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import info.trizub.clamav.webclient.config.WebhookNotificationConfiguration;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryClientTest {
    private WebhookNotificationProperties properties() {
        var p = new WebhookNotificationProperties(); p.setAllowPrivateAddresses(true); p.setWorkers(1);
        p.setConnectTimeout(Duration.ofMillis(200)); p.setDnsTimeout(Duration.ofMillis(100));
        p.setReadTimeout(Duration.ofMillis(150)); p.setDeliveryTimeout(Duration.ofSeconds(2)); return p;
    }

    private URI uri(HttpServer server, String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }

    @Test void responseTimeoutLetsTheNextQueuedWebhookRunWhileTheReceiverIsStillHung() throws Exception {
        var properties = properties(); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var fastDelivered = new CountDownLatch(1);
        var serverThreads = Executors.newCachedThreadPool(WebhookNotificationConfiguration.daemonThreads("test-hook-"));
        var notifications = new WebhookNotificationConfiguration().webhookNotificationExecutor(properties);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        server.createContext("/fast", exchange -> {
            try (exchange) { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204, -1); fastDelivered.countDown(); }
        });
        server.start();
        try (var policy = new WebhookAddressPolicy(properties); var client = new WebhookDeliveryClient(policy, properties)) {
            var settings = mock(SettingsService.class); when(settings.webhookEnabled()).thenReturn(true);
            when(settings.webhookUrl()).thenReturn(uri(server, "/slow").toString());
            var service = new NotificationService(settings, new ObjectMapper(), client, notifications);
            var job = new ScanJob(); job.setId("slow-receiver"); job.setVerdict(ScanVerdict.OK);
            long start = System.nanoTime(); service.notifyIfNeeded(job);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            when(settings.webhookUrl()).thenReturn(uri(server, "/fast").toString());
            service.notifyIfNeeded(job);
            assertThat(fastDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(job.getVerdict()).isEqualTo(ScanVerdict.OK);
        } finally { release.countDown(); notifications.shutdownNow(); server.stop(0); serverThreads.shutdownNow(); }
    }

    @Test void totalDeadlineCancelsARequestEvenWithALongerReadTimeout() throws Exception {
        var properties = properties(); properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setDeliveryTimeout(Duration.ofMillis(200));
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> { try (exchange) {
            exchange.getRequestBody().readAllBytes();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }});
        server.start();
        try (var policy = new WebhookAddressPolicy(properties); var client = new WebhookDeliveryClient(policy, properties)) {
            long start = System.nanoTime();
            assertThatIOException().isThrownBy(() -> client.post(uri(server, "/hook"), "{}"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void receiverBodiesAreNotDrainedOrBuffered() throws Exception {
        var properties = properties(); properties.setReadTimeout(Duration.ofSeconds(3));
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> { try (exchange) {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 1_000_000);
            exchange.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }});
        server.start();
        try (var policy = new WebhookAddressPolicy(properties); var client = new WebhookDeliveryClient(policy, properties)) {
            long start = System.nanoTime(); client.post(uri(server, "/hook"), "{}");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void redirectsAreNotFollowedAndFailedDeliveriesAreNotRetried() throws Exception {
        var properties = properties(); var requests = new AtomicInteger(); var redirected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> { try (exchange) {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", uri(server, "/sink").toString()); exchange.sendResponseHeaders(307, -1);
        }});
        server.createContext("/sink", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.createContext("/error", exchange -> { try (exchange) {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet(); exchange.sendResponseHeaders(503, -1);
        }});
        server.start();
        try (var policy = new WebhookAddressPolicy(properties); var client = new WebhookDeliveryClient(policy, properties)) {
            assertThatIOException().isThrownBy(() -> client.post(uri(server, "/redirect"), "{\"secret\":true}"));
            assertThatIOException().isThrownBy(() -> client.post(uri(server, "/error"), "{}"));
            assertThat(requests.get()).isEqualTo(2); assertThat(redirected.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test void hostnameResolvingToPrivateAddressIsBlockedBeforeOpeningASocket() throws Exception {
        var properties = properties(); properties.setAllowPrivateAddresses(false);
        var hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> { hits.incrementAndGet(); exchange.close(); }); server.start();
        try (var policy = new WebhookAddressPolicy(properties, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
             var client = new WebhookDeliveryClient(policy, properties)) {
            var target = URI.create("http://hook.example.test:" + server.getAddress().getPort() + "/hook");
            assertThatIOException().isThrownBy(() -> client.post(target, "{}"));
            assertThat(hits.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test void theConnectionUsesTheValidatedDnsAnswerWithoutAnotherLookup() throws Exception {
        var properties = properties(); var lookups = new AtomicInteger(); var hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> { try (exchange) {
            exchange.getRequestBody().readAllBytes(); hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1);
        }}); server.start();
        try (var policy = new WebhookAddressPolicy(properties, host -> {
            lookups.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        }); var client = new WebhookDeliveryClient(policy, properties)) {
            client.post(URI.create("http://hook.example.test:" + server.getAddress().getPort() + "/hook"), "{}");
            assertThat(lookups.get()).isEqualTo(1); assertThat(hits.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
