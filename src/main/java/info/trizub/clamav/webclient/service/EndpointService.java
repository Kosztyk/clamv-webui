package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.capybara.clamav.Platform;

import java.util.List;

@Service
public class EndpointService {

    private final ClamdEndpointRepository repo;
    private final SettingsService settings;

    public EndpointService(ClamdEndpointRepository repo, SettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    @Transactional
    public void ensureDefaultEndpoint() {
        if (repo.count() == 0) {
            Platform p;
            try { p = Platform.valueOf(settings.legacyPlatform()); } catch (Exception e) { p = Platform.UNIX; }
            ClamdEndpoint ep = new ClamdEndpoint("default", settings.legacyHost(), settings.legacyPort(), p);
            repo.save(ep);
        }
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
    }

    public ClamdEndpoint defaultEndpoint() {
        return repo.findByName("default").orElseGet(() -> repo.findAll().stream().findFirst().orElse(null));
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
