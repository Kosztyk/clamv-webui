package info.trizub.clamav.webclient.configuration;

import info.trizub.clamav.webclient.identity.AuthenticationSettingsService;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.service.*;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import xyz.capybara.clamav.Platform;
import java.util.*;

@Service
public final class ConfigurationCenterService {
    private final ConfigurableEnvironment environment;
    private final SettingsService settings;
    private final EndpointService endpoints;
    private final AuthenticationSettingsService identities;
    private final IntegrationSettingsService integrations;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final ConfigurationEngine engine;
    private volatile Map<String,String> lastKnownValues=Map.of();
    private final Map<String,long[]> verificationAttempts=new LinkedHashMap<>();
    public ConfigurationCenterService(ConfigurableEnvironment environment,SettingsService settings,
            EndpointService endpoints,AuthenticationSettingsService identities,IntegrationSettingsService integrations,
            AppUserRepository users,PasswordEncoder passwords) {
        this.environment=environment;this.settings=settings;this.endpoints=endpoints;this.identities=identities;
        this.integrations=integrations;this.users=users;this.passwords=passwords;
        this.engine=new ConfigurationEngine(environment,ConfigurationSource.install(environment),this::legacy);
    }
    public ConfigurationEngine engine() { return engine; }
    public void validateRuntimeChanges(ConfigurationEngine.Review review) {
        String name=review.changes().get("center.clamd.name");
        if(name==null) return;
        var primary=endpoints.defaultEndpoint();
        if(endpoints.all().stream().anyMatch(ep->name.equals(ep.getName())
                && (primary==null || !Objects.equals(primary.getId(),ep.getId()))))
            throw new IllegalArgumentException("Endpoint name is already used by another engine; choose a unique name");
    }
    private Map<String,String> legacy() {
        try { Map<String,String> values=loadLegacy();lastKnownValues=Map.copyOf(values);return values; }
        catch(org.springframework.dao.DataAccessException e) {
            if(lastKnownValues.isEmpty()) throw e;
            Map<String,String> cached=new LinkedHashMap<>(lastKnownValues);
            settings.snapshot().forEach((k,v)->cached.put(k.toString(),v.toString()));return cached;
        }
    }
    private Map<String,String> loadLegacy() {
        Map<String,String> values=new LinkedHashMap<>();
        settings.snapshot().forEach((k,v)->values.put(k.toString(),v.toString()));
        var endpoint=endpoints.defaultEndpoint();
        if(endpoint!=null) {
            values.put("center.clamd.name",endpoint.getName());values.put("center.clamd.enabled",Boolean.toString(endpoint.isEnabled()));
            values.put("clamav.service.host",endpoint.getHost());values.put("clamav.service.port",Integer.toString(endpoint.getPort()));
            values.put("clamav.service.platform",endpoint.getPlatform().name());
        }
        for(String provider:List.of("ldap","oidc")) {
            var options=identities.values(provider);
            for(var field:ConfigurationCatalog.all()) if(field.section().equals(provider) && !field.legacy().isBlank())
                values.put(field.key(),options.getOrDefault(field.legacy(),field.defaultValue()));
            values.put(provider.equals("ldap")?"clamav.identity.ldap.manager-password":"clamav.identity.oidc.client-secret",identities.effectiveSecret(provider));
        }
        values.put("clamav.metrics.enabled",Boolean.toString(integrations.scrapeGate().enabled()));
        values.put("center.metrics.token-present",Boolean.toString(!integrations.scrapeGate().hash().isBlank()));
        values.put("center.observer.enabled",Boolean.toString(integrations.observationEnabled()));
        values.put("clamav.config.discovery-enabled",Boolean.toString(integrations.synchronizationEnabled(environment.getProperty("clamav.config.discovery-enabled",Boolean.class,true))));
        values.put("clamav.config.poll-ms",Long.toString(integrations.observerPollMillis(environment.getProperty("clamav.config.poll-ms",Long.class,30000L))));
        return values;
    }
    public synchronized void verifyAdministrator(String username,String password) {
        if(username==null || password==null || username.length()>128 || password.length()>128)
            throw new IllegalArgumentException("Administrator verification failed");
        long now=System.currentTimeMillis();verificationAttempts.entrySet().removeIf(e->now-e.getValue()[0]>60_000);
        String key=username.toLowerCase(Locale.ROOT);long[] attempt=verificationAttempts.get(key);
        if(attempt!=null && attempt[1]>=8) throw new IllegalArgumentException("Administrator verification temporarily limited; wait one minute and retry");
        if(verificationAttempts.size()>=256) verificationAttempts.remove(verificationAttempts.keySet().iterator().next());
        if(attempt==null) { attempt=new long[]{now,0};verificationAttempts.put(key,attempt); }
        attempt[1]++;
        var user=users.findByUsername(username);
        if(user.isEmpty() || !user.get().isEnabled() || !user.get().getRoles().contains("ROLE_ADMIN")
                || !passwords.matches(password,user.get().getPasswordHash()))
            throw new IllegalArgumentException("Administrator verification failed; use an enabled local recovery administrator");
        verificationAttempts.remove(key);
    }
    public void applyLive(ConfigurationEngine.Review review) {
        if(review.changes().keySet().stream().anyMatch(this::endpointKey)) applyEndpoint();
        if(review.changes().keySet().stream().anyMatch(key->Set.of("center.observer.enabled",
                "clamav.config.discovery-enabled","clamav.config.poll-ms").contains(key))) endpoints.pollConfiguration();
        // Scan policy and monitoring controls read the shared live property source.
        // Startup-bound clients, pools, identity and transport policy intentionally
        // retain their running values until a controlled container restart.
    }
    private boolean endpointKey(String key) { return key.startsWith("clamav.service.") || key.startsWith("center.clamd."); }
    private void applyEndpoint() {
        var current=endpoints.defaultEndpointOrEnsure();var values=engine.values(true);
        endpoints.update(current.getId(),values.get("center.clamd.name"),values.get("clamav.service.host"),
                Integer.parseInt(values.get("clamav.service.port")),Platform.valueOf(values.get("clamav.service.platform")),
                Boolean.parseBoolean(values.get("center.clamd.enabled")));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() {
        engine.store().recordDatabase(environment.getProperty("spring.datasource.url",ConfigurationCatalog.get("spring.datasource.url").defaultValue()));
        boolean configured=ConfigurationCatalog.all().stream().filter(f->endpointKey(f.key())).anyMatch(f->
                engine.store().saved(f.key()).isPresent() || ConfigurationSource.override(environment,f).isPresent());
        if(configured) applyEndpoint();
        engine.store().recoveryToken();
    }
}
