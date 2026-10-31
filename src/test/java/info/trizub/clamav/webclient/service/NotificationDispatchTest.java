package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.config.WebhookNotificationConfiguration;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NotificationDispatchTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final WebhookDeliveryClient client = mock(WebhookDeliveryClient.class);

    private NotificationService service(ThreadPoolExecutor executor) {
        when(settings.webhookEnabled()).thenReturn(true);
        when(settings.webhookUrl()).thenReturn("https://example.org/secret-token");
        when(client.validateUrl(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
        return new NotificationService(settings, new ObjectMapper(), client, executor);
    }

    private ThreadPoolExecutor executor() {
        var properties = new WebhookNotificationProperties();
        properties.setWorkers(1); properties.setQueueCapacity(1);
        return (ThreadPoolExecutor) new WebhookNotificationConfiguration().webhookNotificationExecutor(properties);
    }

    private ScanJob job(String target) {
        var job = new ScanJob(); job.setId("test-job"); job.setTarget(target); job.setVerdict(ScanVerdict.OK); return job;
    }

    @Test void hungDeliveryAndFullQueueNeverExecuteOnTheCallerAndJobsAreSnapshotted(CapturedOutput output) throws Exception {
        var executor = executor();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var completed = new CountDownLatch(2);
        var bodies = Collections.synchronizedList(new ArrayList<String>());
        var threads = Collections.synchronizedList(new ArrayList<String>());
        doAnswer(call -> {
            threads.add(Thread.currentThread().getName()); entered.countDown();
            release.await(3, TimeUnit.SECONDS); bodies.add(call.getArgument(1)); completed.countDown(); return null;
        }).when(client).post(any(), anyString());
        try {
            var notifications = service(executor);
            notifications.notifyIfNeeded(job("first"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var queued = job("original target");
            notifications.notifyIfNeeded(queued);
            queued.setTarget("changed after completion");
            long start = System.nanoTime();
            notifications.notifyIfNeeded(job("dropped"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(executor.getQueue()).hasSize(1);
            assertThat(output.getAll()).contains("delivery queue is full").doesNotContain("secret-token");
            release.countDown();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(bodies).hasSize(2).anyMatch(body -> body.contains("original target"))
                .noneMatch(body -> body.contains("changed after completion") || body.contains("dropped"));
            assertThat(threads).allMatch(name -> name.startsWith("webhook-notify-"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void failuresAreIsolatedAndTheirSecretMessagesAreNotLogged(CapturedOutput output) throws Exception {
        var executor = executor();
        doThrow(new IllegalStateException("https://example.org/secret-token private-filename.txt"))
            .when(client).post(any(), anyString());
        try {
            var notifications = service(executor);
            var completed = job("private-filename.txt");
            assertThatCode(() -> notifications.notifyIfNeeded(completed)).doesNotThrowAnyException();
            executor.shutdown(); assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.getVerdict()).isEqualTo(ScanVerdict.OK);
            verify(client, times(1)).post(any(), anyString());
            assertThat(output.getAll()).contains("IllegalStateException")
                .doesNotContain("secret-token", "private-filename.txt");
        } finally { executor.shutdownNow(); }
    }

    @Test void disabledNotificationsAndShutdownCannotFailScanCompletion() throws Exception {
        var executor = executor();
        try {
            var notifications = service(executor);
            when(settings.webhookEnabled()).thenReturn(false);
            notifications.notifyIfNeeded(job("disabled"));
            verifyNoInteractions(client);
            when(settings.webhookEnabled()).thenReturn(true);
            executor.shutdownNow();
            assertThatCode(() -> notifications.notifyIfNeeded(job("shutdown"))).doesNotThrowAnyException();
            verify(client, never()).post(any(), anyString());
        } finally { executor.shutdownNow(); }
    }
}
