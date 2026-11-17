package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Schedules one configuration refresh per configured interval, not a 1-second transactional no-op.
 * Interval changes made in Configuration Center are read at the next reschedule (after the current
 * poll finishes).
 */
@Component
public final class EndpointConfigurationPollScheduler implements SchedulingConfigurer {
  private static final Logger log =
      LoggerFactory.getLogger(EndpointConfigurationPollScheduler.class);
  private static final long MIN_POLL_MS = 5_000;
  private static final long MAX_POLL_MS = 3_600_000;
  private final EndpointService endpoints;
  private final ClamdConfigurationObserver observer;
  private final long initialDelayMillis;

  public EndpointConfigurationPollScheduler(
      EndpointService endpoints,
      ClamdConfigurationObserver observer,
      @Value("${clamav.config.initial-delay-ms:1000}") long initialDelayMillis) {
    this.endpoints = endpoints;
    this.observer = observer;
    this.initialDelayMillis = Math.max(0, initialDelayMillis);
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addTriggerTask(endpoints::pollConfiguration, this::nextExecution);
  }

  // Package-visible for deterministic tests with a stub TriggerContext.
  Instant nextExecution(TriggerContext context) {
    Instant last = context.lastCompletion();
    if (last == null) return context.getClock().instant().plusMillis(initialDelayMillis);
    long interval;
    try {
      interval = observer.pollMillis();
    } catch (RuntimeException invalid) {
      log.warn(
          "Invalid configuration observation interval; using 30 seconds until corrected", invalid);
      interval = 30_000;
    }
    return last.plusMillis(Math.max(MIN_POLL_MS, Math.min(MAX_POLL_MS, interval)));
  }
}
