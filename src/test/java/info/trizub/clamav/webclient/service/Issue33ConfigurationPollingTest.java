package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.config.DaemonConfiguration;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TriggerContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xyz.capybara.clamav.Platform;

class Issue33ConfigurationPollingTest {
  private final ClamdEndpointRepository repo = mock(ClamdEndpointRepository.class);
  private final ClamdConfigurationObserver observer = mock(ClamdConfigurationObserver.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ClamavClientProvider clients = mock(ClamavClientProvider.class);

  private EndpointService endpointService() {
    return new EndpointService(
        repo, mock(SettingsService.class), observer, new MockEnvironment(), transactions, clients);
  }

  @Test
  void noCandidateRefreshDoesNotBeginTransactionOrAccessDatabase() {
    when(observer.endpointCandidate()).thenReturn(Optional.empty());
    endpointService().pollConfiguration();
    verify(observer).refresh();
    verifyNoInteractions(transactions, repo);
  }

  @Test
  void unchangedCandidateDoesNotOpenAnotherTransactionAndChangedCandidateDoes() {
    var current =
        new java.util.concurrent.atomic.AtomicReference<>(
            new DaemonConfiguration.Endpoint("127.0.0.1", 3310));
    when(observer.endpointCandidate()).thenAnswer(inv -> Optional.of(current.get()));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(repo.findByName("default")).thenReturn(Optional.empty());
    var service = endpointService();
    service.pollConfiguration();
    service.pollConfiguration();
    service.pollConfiguration();
    verify(transactions, times(1)).getTransaction(any());
    verify(repo, times(1)).findByName("default");
    verify(observer, times(3)).refresh();
    current.set(new DaemonConfiguration.Endpoint("127.0.0.1", 3311));
    service.pollConfiguration();
    verify(transactions, times(2)).getTransaction(any());
    verify(repo, times(2)).findByName("default");
  }

  @Test
  void periodicExternalDbDriftCheckReconcilesWithoutTransactionalIdlePolling() {
    var candidate = new DaemonConfiguration.Endpoint("127.0.0.1", 3310);
    when(observer.endpointCandidate()).thenReturn(Optional.of(candidate));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var persisted = new ClamdEndpoint("default", "127.0.0.1", 3310, Platform.UNIX);
    when(repo.findByName("default")).thenReturn(Optional.of(persisted));
    var service = endpointService();
    service.pollConfiguration();
    verify(transactions, times(1)).getTransaction(any());
    // Frequent unchanged polls must have zero additional DB traffic.
    clearInvocations(repo, transactions);
    service.pollConfiguration();
    service.pollConfiguration();
    verifyNoInteractions(repo, transactions);

    // Simulate passage of the configured five-minute drift-check window.
    ReflectionTestUtils.setField(service, "nextReconciliationNanos", System.nanoTime() - 1L);
    service.pollConfiguration();
    verify(repo, times(1)).findByName("default");
    verifyNoInteractions(transactions);
    clearInvocations(repo, transactions);

    // Another instance/administrator changed the DB directly.
    persisted.setHost("external-host");
    persisted.setPort(4399);
    ReflectionTestUtils.setField(service, "nextReconciliationNanos", System.nanoTime() - 1L);
    service.pollConfiguration();
    verify(transactions, times(1)).getTransaction(any());
    assertThat(persisted.getHost()).isEqualTo("127.0.0.1");
    assertThat(persisted.getPort()).isEqualTo(3310);
    verify(clients).invalidate(persisted.getId());
    clearInvocations(repo, transactions);
    service.pollConfiguration();
    verifyNoInteractions(repo, transactions);
  }

  @Test
  void observedConfigIsRefreshedBeforeOpeningShortTransaction() {
    when(observer.endpointCandidate())
        .thenReturn(Optional.of(new DaemonConfiguration.Endpoint("127.0.0.1", 3310)));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(repo.findByName("default")).thenReturn(Optional.empty());
    endpointService().pollConfiguration();
    var order = inOrder(observer, transactions, repo);
    order.verify(observer).refresh();
    order.verify(transactions).getTransaction(any());
    order.verify(repo).findByName("default");
    order.verify(transactions).commit(any());
  }

  @Test
  void endpointEditAndRemovalInvalidateCachedScanClient() {
    var primary = new ClamdEndpoint("default", "127.0.0.1", 3310, Platform.UNIX);
    var remote = new ClamdEndpoint("remote", "old.example", 3310, Platform.UNIX);
    ReflectionTestUtils.setField(primary, "id", 1L);
    ReflectionTestUtils.setField(remote, "id", 2L);
    when(repo.findByName("default")).thenReturn(Optional.of(primary));
    when(repo.findById(2L)).thenReturn(Optional.of(remote));
    when(repo.findAll()).thenReturn(java.util.List.of(primary, remote));
    when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EndpointService service = endpointService();
    service.update(2L, "remote", "new.example", 3311, Platform.UNIX, true);
    verify(clients).invalidate(2L);
    service.delete(2L);
    verify(clients, times(2)).invalidate(2L);
  }

  @Test
  void nextPollUsesLiveObserverIntervalInsteadOfOneSecondHeartbeat() {
    EndpointConfigurationPollScheduler scheduler =
        new EndpointConfigurationPollScheduler(mock(EndpointService.class), observer, 2000);
    Instant t = Instant.parse("2026-11-12T10:00:00Z");
    TriggerContext context = mock(TriggerContext.class);
    when(context.lastCompletion()).thenReturn(t);
    when(observer.pollMillis()).thenReturn(30000L, 5000L, 1000L, 999999999L);
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(30000));
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(5000));
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(5000));
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(3600000));
  }

  @Test
  void invalidSavedIntervalDoesNotCancelScheduling() {
    EndpointConfigurationPollScheduler scheduler =
        new EndpointConfigurationPollScheduler(mock(EndpointService.class), observer, 1000);
    Instant t = Instant.parse("2026-11-12T10:00:00Z");
    TriggerContext context = mock(TriggerContext.class);
    when(context.lastCompletion()).thenReturn(t);
    when(observer.pollMillis()).thenThrow(new NumberFormatException("invalid saved value"));
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(30000));
  }

  @Test
  void initialDelayUsesSchedulerClock() {
    EndpointConfigurationPollScheduler scheduler =
        new EndpointConfigurationPollScheduler(mock(EndpointService.class), observer, 1200);
    Instant t = Instant.parse("2026-11-12T10:00:00Z");
    TriggerContext context = mock(TriggerContext.class);
    when(context.getClock()).thenReturn(Clock.fixed(t, ZoneOffset.UTC));
    assertThat(scheduler.nextExecution(context)).isEqualTo(t.plusMillis(1200));
    verifyNoInteractions(transactions, repo);
  }
}
