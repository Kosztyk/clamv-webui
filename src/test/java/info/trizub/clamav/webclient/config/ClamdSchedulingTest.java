package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.metrics.ClamdMetricsCollector;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.capybara.clamav.Platform;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClamdSchedulingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ClamdClientConfiguration.class);

    @Test void defaultsUseFourSchedulerThreadsAndSeparateValidatedBudgets() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            var properties = context.getBean(ClamdClientProperties.class);
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getScanTimeout()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test void deploymentCanAdjustThePoolAndBudgetsEvenWithVirtualThreadsEnabled() {
        runner.withPropertyValues("spring.task.scheduling.pool.size=6", "spring.threads.virtual.enabled=true",
                "clamav.client.connect-timeout=1200ms", "clamav.client.command-timeout=2s", "clamav.client.scan-timeout=1h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(6);
                    var properties = context.getBean(ClamdClientProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(1200));
                    assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getScanTimeout()).isEqualTo(Duration.ofHours(1));
                });
    }

    @ParameterizedTest @ValueSource(strings = {"0", "1", "65"})
    void unsafeSchedulerSizesFailAtStartup(String size) {
        runner.withPropertyValues("spring.task.scheduling.pool.size=" + size).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest @CsvSource({"connect-timeout,0ms", "connect-timeout,61s", "command-timeout,-1s", "command-timeout,61s", "scan-timeout,0s", "scan-timeout,8d"})
    void unboundedOrInvalidTimeoutsFailAtStartup(String property, String value) {
        runner.withPropertyValues("clamav.client." + property + "=" + value).run(context -> assertThat(context).hasFailed());
    }

    static class Witness {
        volatile CountDownLatch ticks = new CountDownLatch(0);
        @Scheduled(fixedDelay = 20) public void tick() { ticks.countDown(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(ClamdMetricsCollector.class)
    static class Polling {
        @Bean ClamavClientProvider clients(ClamdClientProperties properties) { return new ClamavClientProvider(properties); }
        @Bean ClamdTopService top(EndpointService endpoints, ClamavClientProvider clients) { return new ClamdTopService(endpoints, clients); }
        @Bean SimpleMeterRegistry registry() { return new SimpleMeterRegistry(); }
        @Bean Witness witness() { return new Witness(); }
    }

    @Test void aRealBlockedMetricsPollDoesNotStopOtherScheduledTasks() throws Exception {
        var accepted = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(4000);
            var endpoint = new ClamdEndpoint("silent", "127.0.0.1", server.getLocalPort(), Platform.UNIX);
            ReflectionTestUtils.setField(endpoint, "id", 1L);
            var endpoints = mock(EndpointService.class);
            when(endpoints.all()).thenReturn(List.of(endpoint)); when(endpoints.get(1L)).thenReturn(endpoint);
            Future<?> peer = worker.submit(() -> {
                try (var socket = server.accept(); var input = socket.getInputStream()) {
                    socket.setSoTimeout(4000);
                    int value; while ((value = input.read()) > 0) { }
                    accepted.countDown();
                    assertThat(input.read()).isEqualTo(-1);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            runner.withUserConfiguration(Polling.class).withBean(EndpointService.class, () -> endpoints)
                    .withPropertyValues("clamav.client.command-timeout=2s", "clamav.metrics.clamd-initial-delay-ms=20", "clamav.metrics.clamd-poll-ms=3600000")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
                        var witness = context.getBean(Witness.class); witness.ticks = new CountDownLatch(3);
                        assertThat(witness.ticks.await(500, TimeUnit.MILLISECONDS)).isTrue();
                        assertThat(peer.isDone()).isFalse();
                        peer.get(3, TimeUnit.SECONDS);
                    });
        } finally { worker.shutdownNow(); }
    }
}
