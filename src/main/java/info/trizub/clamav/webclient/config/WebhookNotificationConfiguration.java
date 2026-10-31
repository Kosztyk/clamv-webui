package info.trizub.clamav.webclient.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookNotificationProperties.class)
public class WebhookNotificationConfiguration {
    @Bean(name = "webhookNotificationExecutor", destroyMethod = "shutdownNow")
    public ExecutorService webhookNotificationExecutor(WebhookNotificationProperties properties) {
        // Never use CallerRunsPolicy: queue pressure must not run HTTP on a scan worker.
        return new ThreadPoolExecutor(properties.getWorkers(), properties.getWorkers(),
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()),
            daemonThreads("webhook-notify-"), new ThreadPoolExecutor.AbortPolicy());
    }

    public static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
