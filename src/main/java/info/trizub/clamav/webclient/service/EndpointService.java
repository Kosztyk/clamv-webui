package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.env.Environment;
import xyz.capybara.clamav.Platform;

import java.util.List;

@Service
public class EndpointService {

    private final ClamdEndpointRepository repo;
    private final SettingsService settings;
    private final info.trizub.clamav.webclient.config.ClamdConfigurationObserver configObserver;
    private final Environment environment;
    private volatile Long primaryId;

    public EndpointService(ClamdEndpointRepository repo, SettingsService settings,
                           info.trizub.clamav.webclient.config.ClamdConfigurationObserver configObserver,
                           Environment environment) {
        this.repo = repo;
        this.settings = settings;
        this.configObserver = configObserver;
        this.environment = environment;
    }

    @Transactional
    public void ensureDefaultEndpoint() {
        synchronizeDefaultEndpoint();
        if (repo.count() == 0) {
            Platform p;
            try { p = Platform.valueOf(settings.legacyPlatform()); } catch (Exception e) { p = Platform.UNIX; }
            String host = settings.legacyHost();
            int port = settings.legacyPort();
            ClamdEndpoint ep = new ClamdEndpoint("default", host, port, p);
            repo.save(ep);
        }
    }

    private volatile long nextConfigPoll;
    @Scheduled(fixedDelay = 1000, initialDelayString = "${clamav.config.initial-delay-ms:1000}")
    @Transactional
    public void scheduledConfigurationPoll() {
        if (System.nanoTime()<nextConfigPoll) return;
        pollConfiguration();
    }
    @Transactional
    public void pollConfiguration() {
        configObserver.refresh();
        synchronizeDefaultEndpoint();
        nextConfigPoll=System.nanoTime()+configObserver.pollMillis()*1_000_000L;
    }

    private void synchronizeDefaultEndpoint() {
        if (hasConnectionOverride()) return;
        configObserver.endpointCandidate().ifPresent(candidate -> {
            ClamdEndpoint endpoint = repo.findByName("default").orElse(null);
            if (endpoint == null) {
                repo.save(new ClamdEndpoint("default",candidate.host(),candidate.port(),Platform.UNIX));
            } else if (!candidate.host().equals(endpoint.getHost()) || candidate.port()!=endpoint.getPort()
                    || endpoint.getPlatform()!=Platform.UNIX) {
                endpoint.setHost(candidate.host());
                endpoint.setPort(candidate.port());
                endpoint.setPlatform(Platform.UNIX);
                repo.save(endpoint);
            }
        });
    }

    public boolean hasConnectionOverride() {
        return info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all().stream()
            .filter(f->f.section().equals("clamd"))
            .anyMatch(f->info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,f).isPresent()
                || info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).flatMap(s->s.saved(f.key())).isPresent());
    }

    public List<ClamdEndpoint> all() {
        return repo.findAll();
    }

    public ClamdEndpoint get(Long id) {
        return repo.findById(id).orElseThrow();
    }

    @Transactional
    public ClamdEndpoint create(String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = new ClamdEndpoint(name, host, port, platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    @Transactional
    public ClamdEndpoint update(Long id, String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ClamdEndpoint primary=defaultEndpoint();
        if(primary!=null && primary.getId().equals(id)) {
            java.util.Map<String,String> proposed=java.util.Map.of("center.clamd.name",name,"clamav.service.host",host,
                    "clamav.service.port",Integer.toString(port),"clamav.service.platform",platform.name(),"center.clamd.enabled",Boolean.toString(enabled));
            proposed.forEach((key,value)->{
                var field=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get(key);
                String controlled=info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field)
                    .map(info.trizub.clamav.webclient.configuration.ConfigurationSource.Override::value)
                    .orElseGet(()->info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).flatMap(s->s.active(key)).orElse(null));
                if(controlled!=null && !controlled.equals(value)) throw new IllegalArgumentException(field.label()+" is managed by deployment configuration or Configuration Center");
            });
        }
        ep.setName(name);
        ep.setHost(host);
        ep.setPort(port);
        ep.setPlatform(platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
        if (java.util.Objects.equals(primaryId,id)) primaryId=null;
    }

    public ClamdEndpoint defaultEndpoint() {
        // Keep the selected record stable across renames and unordered SQL reads.
        Long selectedId=primaryId;
        if(selectedId!=null) {
            var selected=repo.findById(selectedId);
            if(selected.isPresent()) return selected.get();
            primaryId=null;
        }
        var field=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get("center.clamd.name");
        String name=info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field)
            .map(info.trizub.clamav.webclient.configuration.ConfigurationSource.Override::value)
            .or(()->info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).flatMap(store->store.active(field.key())))
            .orElse("default");
        ClamdEndpoint selected=repo.findByName(name).or(()->repo.findByName("default"))
            .orElseGet(()->repo.findAll().stream().min(java.util.Comparator.comparing(ClamdEndpoint::getId,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).orElse(null));
        if(selected!=null) primaryId=selected.getId();
        return selected;
    }

    /**
     * Like defaultEndpoint(), but guarantees a non-null return by creating the default endpoint when needed.
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
