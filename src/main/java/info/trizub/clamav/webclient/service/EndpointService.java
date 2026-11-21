package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.capybara.clamav.Platform;

@Service
public class EndpointService {

  private final ClamdEndpointRepository repo;
  private final SettingsService settings;
  private final info.trizub.clamav.webclient.config.ClamdConfigurationObserver configObserver;
  private final Environment environment;
  private volatile Long primaryId;

  // Last candidate verified against the default endpoint. Never mark it as synced
  // until the database transaction has committed successfully.
  private record SyncedCandidate(
      long revision, info.trizub.clamav.webclient.config.DaemonConfiguration.Endpoint endpoint) {}

  private final java.util.concurrent.atomic.AtomicLong syncRevision =
      new java.util.concurrent.atomic.AtomicLong();
  private volatile SyncedCandidate lastSyncedCandidate;
  // A read-only drift check at most once every five minutes catches edits made by another
  // application instance or directly in the database. Idle polls still open no write transaction.
  private static final long RECONCILE_INTERVAL_NANOS =
      java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
  private volatile long nextReconciliationNanos;
  private final TransactionTemplate synchronizationTransaction;
  private final ClamavClientProvider clients;

  /** Constructor retained for direct, repository-mocked service tests. */
  public EndpointService(
      ClamdEndpointRepository repo,
      SettingsService settings,
      info.trizub.clamav.webclient.config.ClamdConfigurationObserver configObserver,
      Environment environment) {
    this(repo, settings, configObserver, environment, null, null);
  }

  /** Production constructor: the transaction starts only after the config files have been read. */
  @Autowired
  public EndpointService(
      ClamdEndpointRepository repo,
      SettingsService settings,
      info.trizub.clamav.webclient.config.ClamdConfigurationObserver configObserver,
      Environment environment,
      PlatformTransactionManager transactions,
      ClamavClientProvider clients) {
    this.repo = repo;
    this.settings = settings;
    this.configObserver = configObserver;
    this.environment = environment;
    this.synchronizationTransaction =
        transactions == null ? null : new TransactionTemplate(transactions);
    this.clients = clients;
  }

  @Transactional
  public void ensureDefaultEndpoint() {
    synchronizeDefaultEndpoint();
    if (repo.count() == 0) {
      Platform p;
      try {
        p = Platform.valueOf(settings.legacyPlatform());
      } catch (Exception e) {
        p = Platform.UNIX;
      }
      String host = settings.legacyHost();
      int port = settings.legacyPort();
      ClamdEndpoint ep = new ClamdEndpoint("default", host, port, p);
      repo.save(ep);
    }
  }

  /** Also called directly when an administrator saves live observer settings. */
  public synchronized void pollConfiguration() {
    // File parsing can touch slow or unavailable mounts: never do it in a DB transaction.
    configObserver.refresh();
    // No candidate means no database synchronization. The scheduler never starts a transaction
    // here.
    var candidate = configObserver.endpointCandidate().orElse(null);
    if (candidate == null || hasConnectionOverride()) {
      // A temporary override or paused observation must not leave a stale
      // synchronization marker when the discovered endpoint is enabled again.
      clearSynchronizationMarker();
      return;
    }
    // Do not begin an otherwise-empty transaction for each unchanged poll.
    long revision = syncRevision.get();
    SyncedCandidate previous = lastSyncedCandidate;
    if (previous != null
        && previous.revision() == revision
        && candidate.equals(previous.endpoint())) {
      long now = System.nanoTime();
      if (now - nextReconciliationNanos < 0) return;
      // Read only, no TransactionTemplate. A DB drift triggers a normal short write transaction.
      var persisted = repo.findByName("default").orElse(null);
      if (sameConnection(persisted, candidate)) {
        nextReconciliationNanos = System.nanoTime() + RECONCILE_INTERVAL_NANOS;
        return;
      }
      if (syncRevision.get() != revision) return; // local edit raced the read
    }
    if (synchronizationTransaction == null) {
      // Pure service tests pass mocked repositories without a transaction manager.
      synchronizeDefaultEndpoint();
    } else {
      synchronizationTransaction.executeWithoutResult(status -> synchronizeDefaultEndpoint());
    }
    // If an administrator modified the endpoint during the transaction,
    // the revision changes and this stale candidate cannot mask that edit.
    if (syncRevision.get() == revision) {
      lastSyncedCandidate = new SyncedCandidate(revision, candidate);
      nextReconciliationNanos = System.nanoTime() + RECONCILE_INTERVAL_NANOS;
    }
  }

  private static boolean sameConnection(
      ClamdEndpoint persisted,
      info.trizub.clamav.webclient.config.DaemonConfiguration.Endpoint candidate) {
    return persisted != null
        && candidate.host().equals(persisted.getHost())
        && candidate.port() == persisted.getPort()
        && persisted.getPlatform() == Platform.UNIX;
  }

  private void synchronizeDefaultEndpoint() {
    if (hasConnectionOverride()) return;
    configObserver
        .endpointCandidate()
        .ifPresent(
            candidate -> {
              ClamdEndpoint endpoint = repo.findByName("default").orElse(null);
              if (endpoint == null) {
                repo.save(
                    new ClamdEndpoint(
                        "default", candidate.host(), candidate.port(), Platform.UNIX));
              } else if (!candidate.host().equals(endpoint.getHost())
                  || candidate.port() != endpoint.getPort()
                  || endpoint.getPlatform() != Platform.UNIX) {
                endpoint.setHost(candidate.host());
                endpoint.setPort(candidate.port());
                endpoint.setPlatform(Platform.UNIX);
                repo.save(endpoint);
                invalidateClient(endpoint.getId());
              }
            });
  }

  public boolean hasConnectionOverride() {
    return info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all().stream()
        .filter(f -> f.section().equals("clamd"))
        .anyMatch(
            f ->
                info.trizub.clamav.webclient.configuration.ConfigurationSource.override(
                            environment, f)
                        .isPresent()
                    || info.trizub.clamav.webclient.configuration.ConfigurationSource.store(
                            environment)
                        .flatMap(s -> s.saved(f.key()))
                        .isPresent());
  }

  public List<ClamdEndpoint> all() {
    return repo.findAll();
  }

  public ClamdEndpoint get(Long id) {
    return repo.findById(id).orElseThrow();
  }

  @Transactional
  public ClamdEndpoint create(
      String name, String host, int port, Platform platform, boolean enabled) {
    EndpointInputValidator.validate(name, host, port, platform);
    host = EndpointInputValidator.host(host);
    if ("default".equalsIgnoreCase(name))
      throw new IllegalArgumentException(
          "Default engine is configured only in Configuration Center");
    if (repo.findAll().stream().anyMatch(e -> e.getName().equalsIgnoreCase(name)))
      throw new IllegalArgumentException("Endpoint name is already in use");
    ClamdEndpoint ep = new ClamdEndpoint(name, host, port, platform);
    ep.setEnabled(enabled);
    return repo.save(ep);
  }

  @Transactional
  public ClamdEndpoint update(
      Long id, String name, String host, int port, Platform platform, boolean enabled) {
    ClamdEndpoint ep = repo.findById(id).orElseThrow();
    ClamdEndpoint primary = defaultEndpoint();
    // The primary endpoint is managed by Configuration Center, which intentionally
    // supports Unix socket paths. Additional endpoints use TCP hostname/IP only.
    boolean isPrimary = primary != null && primary.getId().equals(id);
    if (isPrimary)
      info.trizub.clamav.webclient.configuration.ConfigurationCatalog.validate(
          info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get("center.clamd.name"),
          name);
    else EndpointInputValidator.name(name);
    if (isPrimary && host != null && host.startsWith("/")) {
      if (port != 0) EndpointInputValidator.port(port);
    } else {
      EndpointInputValidator.port(port);
    }
    if (!isPrimary) host = EndpointInputValidator.host(host);
    if (platform == null) throw new IllegalArgumentException("Endpoint platform is required");
    if (repo.findAll().stream()
        .anyMatch(
            e -> !java.util.Objects.equals(e.getId(), id) && e.getName().equalsIgnoreCase(name)))
      throw new IllegalArgumentException("Endpoint name is already in use");
    if (!isPrimary && "default".equalsIgnoreCase(name))
      throw new IllegalArgumentException(
          "Default engine is configured only in Configuration Center");
    if (isPrimary) {
      java.util.Map<String, String> proposed =
          java.util.Map.of(
              "center.clamd.name",
              name,
              "clamav.service.host",
              host,
              "clamav.service.port",
              Integer.toString(port),
              "clamav.service.platform",
              platform.name(),
              "center.clamd.enabled",
              Boolean.toString(enabled));
      proposed.forEach(
          (key, value) -> {
            var field = info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get(key);
            String controlled =
                info.trizub.clamav.webclient.configuration.ConfigurationSource.override(
                        environment, field)
                    .map(
                        info.trizub.clamav.webclient.configuration.ConfigurationSource.Override
                            ::value)
                    .orElseGet(
                        () ->
                            info.trizub.clamav.webclient.configuration.ConfigurationSource.store(
                                    environment)
                                .flatMap(s -> s.active(key))
                                .orElse(null));
            if (controlled != null && !controlled.equals(value))
              throw new IllegalArgumentException(
                  field.label()
                      + " is managed by deployment configuration or Configuration Center");
          });
    }
    ep.setName(name);
    ep.setHost(host);
    ep.setPort(port);
    ep.setPlatform(platform);
    ep.setEnabled(enabled);
    ClamdEndpoint saved = repo.save(ep);
    invalidateClient(id);
    if (isPrimary) clearSynchronizationMarker();
    return saved;
  }

  @Transactional
  public void delete(Long id) {
    repo.deleteById(id);
    invalidateClient(id);
    clearSynchronizationMarker();
    if (java.util.Objects.equals(primaryId, id)) primaryId = null;
  }

  /** No lock: endpoint edits may run while the scheduled poll awaits a DB lock. */
  private void clearSynchronizationMarker() {
    syncRevision.incrementAndGet();
    lastSyncedCandidate = null;
    nextReconciliationNanos = 0L;
  }

  private void invalidateClient(Long id) {
    if (clients != null) clients.invalidate(id);
  }

  public ClamdEndpoint defaultEndpoint() {
    // Keep the selected record stable across renames and unordered SQL reads.
    Long selectedId = primaryId;
    if (selectedId != null) {
      var selected = repo.findById(selectedId);
      if (selected.isPresent()) return selected.get();
      primaryId = null;
    }
    var field =
        info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get("center.clamd.name");
    String name =
        info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment, field)
            .map(info.trizub.clamav.webclient.configuration.ConfigurationSource.Override::value)
            .or(
                () ->
                    info.trizub.clamav.webclient.configuration.ConfigurationSource.store(
                            environment)
                        .flatMap(store -> store.active(field.key())))
            .orElse("default");
    ClamdEndpoint selected =
        repo.findByName(name)
            .or(() -> repo.findByName("default"))
            .orElseGet(
                () ->
                    repo.findAll().stream()
                        .min(
                            java.util.Comparator.comparing(
                                ClamdEndpoint::getId,
                                java.util.Comparator.nullsLast(
                                    java.util.Comparator.naturalOrder())))
                        .orElse(null));
    if (selected != null) primaryId = selected.getId();
    return selected;
  }

  /**
   * Like defaultEndpoint(), but guarantees a non-null return by creating the default endpoint when
   * needed.
   */
  @Transactional
  public ClamdEndpoint defaultEndpointOrEnsure() {
    ClamdEndpoint ep = defaultEndpoint();
    if (ep == null) {
      ensureDefaultEndpoint();
      ep = defaultEndpoint();
    }
    return ep;
  }
}
