package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.util.AtomicPropertiesFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Per-instance live controls on the persistent conf volume. Scrape credentials are stored only as hashes. */
@Service
public class IntegrationSettingsService {
    private final Environment environment;
    private final Path file;
    private volatile Properties saved;

    public IntegrationSettingsService(Environment environment,
            @Value("${clamav.integrations.settings-file:conf/integrations.properties}") String file) {
        this.environment=environment;this.file=Path.of(file);
        try { saved=Files.exists(this.file) ? AtomicPropertiesFile.loadOrCreate(this.file) : new Properties(); }
        catch (Exception ex) { throw new IllegalStateException("Cannot load saved integration settings",ex); }
    }
    public record ScrapeGate(boolean enabled, String hash) { }
    public ScrapeGate scrapeGate() {
        Properties p=saved;
        String bootstrap=environment.getProperty("clamav.metrics.scrape-token","").trim();
        var configuredToken=configured("clamav.metrics.scrape-token");
        String hash=configuredToken.isPresent()?(configuredToken.get().length()>=24?hash(configuredToken.get()):""):
            p.getProperty("metrics.tokenHash",bootstrap.length()>=24 ? hash(bootstrap) : "");
        boolean enabled=Boolean.parseBoolean(configured("clamav.metrics.enabled").orElseGet(()->p.getProperty("metrics.enabled",
            environment.getProperty("clamav.metrics.enabled",String.valueOf(!hash.isBlank())))));
        return new ScrapeGate(enabled,hash);
    }
    public boolean metricsManaged() { return saved.containsKey("metrics.enabled") || saved.containsKey("metrics.tokenHash"); }
    public boolean observationEnabled() { return Boolean.parseBoolean(configured("center.observer.enabled").orElseGet(()->saved.getProperty("observer.enabled","true"))); }
    public boolean synchronizationEnabled(boolean bootstrap) { return Boolean.parseBoolean(configured("clamav.config.discovery-enabled").orElseGet(()->saved.getProperty("observer.synchronize",String.valueOf(bootstrap)))); }
    public boolean observerManaged() { return saved.containsKey("observer.enabled"); }
    public long observerPollMillis(long bootstrap) { return Long.parseLong(configured("clamav.config.poll-ms").orElseGet(()->saved.getProperty("observer.pollMillis",String.valueOf(Math.max(1000,bootstrap))))); }

    private Optional<String> configured(String key) {
        var definition=info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get(key);
        return info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,definition)
            .map(info.trizub.clamav.webclient.configuration.ConfigurationSource.Override::value)
            .or(()->info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment).flatMap(store->store.active(key)));
    }

    public synchronized void saveMetrics(boolean enabled,String replacement) {
        if(configured("clamav.metrics.enabled").isPresent() || configured("clamav.metrics.scrape-token").isPresent())
            throw new IllegalArgumentException("Prometheus access is controlled by deployment settings or Configuration Center; change it at its source.");
        Properties next=copy();String token=replacement==null ? "" : replacement.trim();
        if (!token.isEmpty()) {
            if (token.length()<32 || token.length()>4096 || !token.matches("[\\x21-\\x7e]+"))
                throw new IllegalArgumentException("Use a scrape token of 32–4096 characters without spaces.");
            next.setProperty("metrics.tokenHash",hash(token));
        }
        if (enabled && token.isEmpty() && scrapeGate().hash().isBlank())
            throw new IllegalArgumentException("Generate or enter a scrape token before enabling Prometheus.");
        next.setProperty("metrics.enabled",String.valueOf(enabled));persist(next);
    }
    public synchronized String generateMetricsToken() {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        saveMetrics(true,token);return token;
    }
    public synchronized void resetMetrics() {
        Properties next=copy();next.remove("metrics.enabled");next.remove("metrics.tokenHash");persist(next);
    }
    public synchronized void saveObserver(boolean enabled,boolean synchronize,long seconds) {
        if(configured("center.observer.enabled").isPresent() || configured("clamav.config.discovery-enabled").isPresent() || configured("clamav.config.poll-ms").isPresent())
            throw new IllegalArgumentException("Configuration observation is controlled by deployment settings or Configuration Center; change it at its source.");
        if (seconds<5 || seconds>3600) throw new IllegalArgumentException("Choose a configuration polling interval from 5 to 3600 seconds.");
        Properties next=copy();next.setProperty("observer.enabled",String.valueOf(enabled));
        next.setProperty("observer.synchronize",String.valueOf(synchronize));next.setProperty("observer.pollMillis",String.valueOf(seconds*1000));
        persist(next);
    }
    public synchronized void resetObserver() {
        Properties next=copy();next.remove("observer.enabled");next.remove("observer.synchronize");next.remove("observer.pollMillis");persist(next);
    }
    private Properties copy() { Properties next=new Properties();next.putAll(saved);return next; }
    private void persist(Properties next) {
        try { AtomicPropertiesFile.storeAtomic(file,next);saved=next; }
        catch (Exception ex) { throw new IllegalStateException("Could not save integration settings; check the conf volume permissions."); }
    }
    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
