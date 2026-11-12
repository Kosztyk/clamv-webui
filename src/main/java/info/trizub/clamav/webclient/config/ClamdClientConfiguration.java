package info.trizub.clamav.webclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClamdClientProperties.class)
public class ClamdClientConfiguration {
    /** Use a real pool even when virtual-thread auto-configuration would serialize fixed-delay work. */
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(TaskScheduler.class)
    public ThreadPoolTaskScheduler taskScheduler(@Value("${spring.task.scheduling.pool.size:4}") int size) {
        if (size < 4 || size > 64) throw new IllegalArgumentException("Scheduler pool size must be between 4 and 64");
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix("clamav-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
