package info.trizub.clamav.webclient.configuration;

import org.springframework.core.env.ConfigurableEnvironment;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Review and resolution shared with the database-independent setup/recovery server. */
public final class ConfigurationEngine {
    public record Change(String key,String label,String before,String after,String apply) { }
    public record Review(String id,String version,Map<String,String> changes,Set<String> clearSecrets,
                         List<Change> summary,boolean sensitive,boolean databaseChange,Instant expires) { }
    private final ConfigurableEnvironment environment;
    private final ConfigurationStore store;
    private final Supplier<Map<String,String>> legacy;
    public ConfigurationEngine(ConfigurableEnvironment environment,ConfigurationStore store,Supplier<Map<String,String>> legacy) {
        this.environment=environment;this.store=store;this.legacy=legacy;
    }
    public ConfigurationStore store() { return store; }
    public Map<String,String> values(boolean staged) {
        Map<String,String> older=legacy.get(),result=new LinkedHashMap<>();
        for (var field:ConfigurationCatalog.all()) {
            String value=ConfigurationSource.override(environment,field).map(ConfigurationSource.Override::value).orElse(null);
            if(value==null && staged) value=store.saved(field.key()).orElse(null);
            if(value==null) value=older.get(field.key());
            if(value==null) value=store.active(field.key()).orElse(null);
            if(value==null) value=environment.getProperty(field.key());
            if(value==null) value=field.defaultValue();
            result.put(field.key(),value);
        }
        resolveSecretFiles(result);
        if(result.get("CLAMAV_REST_URL").isBlank()) result.put("clamav.rest.enabled","false");
        if(!store.saved("clamav.metrics.enabled").isPresent()
                && ConfigurationSource.override(environment,ConfigurationCatalog.get("clamav.metrics.enabled")).isEmpty()
                && !result.get("clamav.metrics.scrape-token").isBlank()) result.put("clamav.metrics.enabled","true");
        return result;
    }
    public static void resolveSecretFiles(Map<String,String> values) {
        for(String key:List.of("clamav.identity.ldap.manager-password","clamav.identity.oidc.client-secret")) {
            String path=values.getOrDefault(key+"-file","");
            if(values.getOrDefault(key,"").isBlank() && !path.isBlank())
                values.put(key,info.trizub.clamav.webclient.identity.SecretResolver.resolve("",path));
        }
    }
    public Map<String,String> draftValues(Review review) {
        store.refresh();
        if(!Objects.equals(review.version(),store.snapshot().revision()))
            throw new ConcurrentModificationException("Configuration changed; reload before testing or applying");
        Map<String,String> before=values(true),values=new LinkedHashMap<>(before);values.putAll(review.changes());
        review.clearSecrets().forEach(key->values.put(key,""));
        requireProviderSecrets(before,values,review.changes(),review.clearSecrets());
        resolveSecretFiles(values);return values;
    }
    private static void requireProviderSecrets(Map<String,String> before,Map<String,String> after,
                                               Map<String,String> supplied,Set<String> cleared) {
        for(String provider:List.of("ldap","oidc")) {
            String secret="clamav.identity."+provider+"."+(provider.equals("ldap")?"manager-password":"client-secret");
            boolean explicit=supplied.containsKey(secret) && !supplied.get(secret).isBlank();
            boolean removed=cleared.contains(secret) && after.getOrDefault(secret,"").isBlank()
                    && after.getOrDefault(secret+"-file","").isBlank();
            info.trizub.clamav.webclient.identity.ProviderSecretGuard.requireReplacement(provider,
                    ConfigurationCatalog.provider(provider,before),ConfigurationCatalog.provider(provider,after),
                    !before.getOrDefault(secret,"").isBlank(),explicit,removed);
        }
    }
    public Map<String,Object> view() {
        store.refresh();
        Map<String,String> selected=values(true),active=values(false),older=legacy.get();
        List<Map<String,Object>> fields=new ArrayList<>();List<String> pending=new ArrayList<>();
        for(var field:ConfigurationCatalog.all()) {
            var controlled=ConfigurationSource.override(environment,field);
            boolean restart=!field.live() && !Objects.equals(selected.get(field.key()),active.get(field.key()));
            if(restart) pending.add(field.key());
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("definition",field);item.put("value",display(field,selected.get(field.key())));
            item.put("activeValue",display(field,active.get(field.key())));
            item.put("configured",!selected.get(field.key()).isBlank() || field.key().equals("clamav.metrics.scrape-token") && "true".equals(older.get("center.metrics.token-present")));
            item.put("locked",controlled.isPresent());item.put("source",controlled.isPresent()?"Deployment configuration":store.saved(field.key()).isPresent()?"Saved in Configuration Center":older.containsKey(field.key())?"Existing application setting":"Application default");
            item.put("controlledBy",controlled.map(ConfigurationSource.Override::source).orElse(""));
            item.put("pendingRestart",restart);fields.add(item);
        }
        return Map.of("version",store.snapshot().revision(),"categories",ConfigurationCatalog.document().categories(),
                "sections",ConfigurationCatalog.document().sections(),"fields",fields,"pendingRestart",pending,
                "databaseChoiceRequired",store.requiresDatabaseChoice(selected.get("spring.datasource.url")));
    }
    private static String display(ConfigurationSetting field,String value) {
        if(field.secret()) return "";
        if(value==null) return "";
        if((field.type().equals("jdbc") || field.type().equals("url") || field.key().equals("clamav.identity.ldap.url"))
                && (value.matches("(?is).*://[^/]*@.*") || value.matches("(?is).*[?;&][^=]*(?:password|passwd|secret|token|api[-_]?key)[^=]*=.*"))) {
            String safe=value.replaceAll("(?i)(://)[^/@]*@","$1[private]@");
            int query=safe.indexOf('?');if(query>=0) safe=safe.substring(0,query)+"?[private options]";
            return safe.replaceAll("(?i)(;[^=]*(?:password|passwd|secret|token|api[-_]?key)[^=]*=)[^;]*","$1[private]");
        }
        return value;
    }
    public Review review(String version,Map<String,String> submitted,Set<String> clearSecrets) {
        store.refresh();
        if(!Objects.equals(version,store.snapshot().revision())) throw new ConcurrentModificationException("Configuration changed; reload before reviewing");
        if(submitted.size()>ConfigurationCatalog.all().size() || clearSecrets.size()>ConfigurationCatalog.all().size())
            throw new IllegalArgumentException("Too many configuration settings");
        Map<String,String> before=values(true),after=new LinkedHashMap<>(before),changes=new LinkedHashMap<>();
        Set<String> clear=new LinkedHashSet<>();List<Change> summary=new ArrayList<>();
        for(var entry:submitted.entrySet()) {
            var field=ConfigurationCatalog.get(entry.getKey());
            if(field.secret() && (entry.getValue()==null || entry.getValue().isBlank())) continue;
            String value=ConfigurationCatalog.validate(field,entry.getValue());
            if(!field.secret() && Objects.equals(before.get(field.key()),value) && !(field.key().equals("spring.datasource.url") && store.requiresDatabaseChoice(value))) continue;
            requireEditable(field);changes.put(field.key(),value);after.put(field.key(),value);
            summary.add(new Change(field.key(),field.label(),field.secret()?"[private]":before.get(field.key()),field.secret()?"[replaced]":value,field.apply()));
        }
        for(String key:clearSecrets) {
            var field=ConfigurationCatalog.get(key);requireEditable(field);
            if(!field.secret()) throw new IllegalArgumentException("Only a secret can be explicitly cleared");
            clear.add(key);changes.remove(key);after.put(key,"");
            summary.add(new Change(key,field.label(),"[private]","[cleared]",field.apply()));
        }
        if(clear.contains("app.webhook.url")) {
            var enabled=ConfigurationCatalog.get("app.webhook.enabled");requireEditable(enabled);
            changes.put(enabled.key(),"false");after.put(enabled.key(),"false");
        }
        for(String key:List.of("clamav.identity.ldap.manager-password","clamav.identity.oidc.client-secret")) {
            String fileKey=key+"-file";
            if(changes.containsKey(fileKey) && !changes.get(fileKey).isBlank()) {
                if(changes.containsKey(key)) throw new IllegalArgumentException("Configure either the credential or its secret file");
                requireEditable(ConfigurationCatalog.get(key));clear.add(key);after.put(key,"");
            } else if(changes.containsKey(key) || clear.contains(key)) {
                if(!after.get(fileKey).isBlank()) {
                    requireEditable(ConfigurationCatalog.get(fileKey));changes.put(fileKey,"");after.put(fileKey,"");
                    summary.add(new Change(fileKey,ConfigurationCatalog.get(fileKey).label(),before.get(fileKey),"", "restart"));
                }
            }
        }
        requireProviderSecrets(before,after,changes,clear);
        resolveSecretFiles(after);
        // Legacy deployments infer some features from a URL or token alone.
        // A GUI save must retain the switch the administrator actually saw,
        // including when they filled a draft and switched it back off.
        for(var section:ConfigurationCatalog.document().sections()) {
            String enabled=section.enableKey();
            if(enabled.isBlank() || changes.containsKey(enabled) || store.saved(enabled).isPresent() || locked(enabled)) continue;
            boolean edited=changes.keySet().stream().anyMatch(k->ConfigurationCatalog.get(k).section().equals(section.id()))
                    || clear.stream().anyMatch(k->ConfigurationCatalog.get(k).section().equals(section.id()));
            if(edited) {
                var field=ConfigurationCatalog.get(enabled);changes.put(enabled,after.get(enabled));
                summary.add(new Change(enabled,field.label()+" (retain selected state)","",after.get(enabled),field.apply()));
            }
        }
        Set<String> keys=new LinkedHashSet<>(changes.keySet());keys.addAll(clear);
        ConfigurationCatalog.validateCombination(after,keys);
        if("true".equals(after.get("clamav.metrics.enabled")) && after.get("clamav.metrics.scrape-token").isBlank()
                && (clear.contains("clamav.metrics.scrape-token") || !"true".equals(legacy.get().get("center.metrics.token-present"))))
            throw new IllegalArgumentException("A scrape token is required before enabling Prometheus");
        if(changes.containsKey("clamav.metrics.scrape-token") && !changes.get("clamav.metrics.scrape-token").matches("[\\x21-\\x7e]{32,4096}"))
            throw new IllegalArgumentException("Scrape token: use 32–4096 printable characters without spaces");
        boolean sensitive=keys.stream().map(ConfigurationCatalog::get).anyMatch(f->f.sensitive() || f.secret());
        boolean database=keys.stream().anyMatch(k->k.startsWith("spring.datasource.") || k.startsWith("spring.flyway.baseline"));
        return new Review(UUID.randomUUID().toString(),version,Map.copyOf(changes),Set.copyOf(clear),List.copyOf(summary),sensitive,database,Instant.now().plusSeconds(300));
    }
    public ConfigurationStore.Snapshot apply(Review review,boolean acknowledged,String actor) {
        if(review.expires().isBefore(Instant.now())) throw new IllegalArgumentException("Review expired; review the changes again");
        if(review.databaseChange() && !acknowledged) throw new IllegalArgumentException("Confirm the database backup and connection change before applying");
        for(String key:review.changes().keySet()) requireEditable(ConfigurationCatalog.get(key));
        for(String key:review.clearSecrets()) requireEditable(ConfigurationCatalog.get(key));
        Map<String,String> approved=draftValues(review);
        // Record the approved database before the atomic configuration commit. A
        // failed commit still retains the old selection and cannot cause fallback.
        var saved=store.save(review.version(),review.changes(),review.clearSecrets(),true,actor,
                review.databaseChange()?approved.get("spring.datasource.url"):null);
        return saved;
    }
    public boolean locked(String key) { return ConfigurationSource.override(environment,ConfigurationCatalog.get(key)).isPresent(); }
    private void requireEditable(ConfigurationSetting field) {
        if(locked(field.key())) throw new IllegalArgumentException(field.label()+" is controlled by deployment configuration; remove the override and recreate the container to edit it here");
    }
}
