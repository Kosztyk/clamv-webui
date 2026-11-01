package info.trizub.clamav.webclient.configuration;

import org.springframework.core.env.*;
import java.util.*;

/** GUI values sit above packaged defaults and below deployment configuration. */
public final class ConfigurationSource extends EnumerablePropertySource<ConfigurationStore> {
    public static final String NAME="configurationCenter";
    private static final Map<String,String> APPLIED_SSL=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,String> aliases;
    public ConfigurationSource(ConfigurationStore store) {
        super(NAME,store);
        Map<String,String> map=new LinkedHashMap<>();
        for (var definition:ConfigurationCatalog.all()) {
            map.put(definition.key(),definition.key());
            // Raw REST placeholders and documented legacy/environment aliases also work.
            for (String alias:definition.environment()) {
                // Standard Spring OAuth names are accepted deployment INPUT aliases.
                // Publishing partial GUI drafts into that namespace creates an
                // incomplete Boot client registration even when SSO is disabled.
                if(alias.startsWith("spring.security.oauth2.") || alias.startsWith("SPRING_SECURITY_OAUTH2_")) continue;
                map.putIfAbsent(alias,definition.key());
            }
        }
        aliases=Collections.unmodifiableMap(map);
    }
    public Object getProperty(String name) {
        String key=aliases.get(name);
        return key==null?null:getSource().active(key).orElse(null);
    }
    public String[] getPropertyNames() { return aliases.keySet().toArray(String[]::new); }
    public record Override(String value,String source) { }
    public static Optional<Override> override(Environment environment,ConfigurationSetting definition) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return Optional.empty();
        for (PropertySource<?> property:configurable.getPropertySources()) {
            String name=property.getName();
            if (property instanceof ConfigurationSource || name.equals("configurationProperties")
                    || name.equals("random") || name.equals("defaultProperties") || packaged(name)) continue;
            Object value=raw(property,definition);
            if(name.equals("systemProperties") && value!=null && Objects.equals(APPLIED_SSL.get(definition.key()),value.toString())) value=null;
            // A mounted deployment secret also controls the corresponding inline
            // field. A lower-priority GUI value must never bypass that secret.
            if(value==null && definition.section().matches("ldap|oidc")) {
                if(definition.secret()) {
                    Object path=raw(property,ConfigurationCatalog.get(definition.key()+"-file"));
                    if(path!=null && !path.toString().isBlank())
                        value=info.trizub.clamav.webclient.identity.SecretResolver.resolve("",environment.resolvePlaceholders(path.toString()));
                } else if(definition.key().endsWith("-file")) {
                    var inline=ConfigurationCatalog.get(definition.key().substring(0,definition.key().length()-5));
                    if(raw(property,inline)!=null) value="";
                }
            }
            if (value!=null) return Optional.of(new Override(environment.resolvePlaceholders(String.valueOf(value)),name));
        }
        return Optional.empty();
    }
    private static Object raw(PropertySource<?> property,ConfigurationSetting definition) {
        Object value=property.getProperty(definition.key());
        if(value==null) for(String alias:definition.environment()) { value=property.getProperty(alias);if(value!=null) break; }
        return value;
    }
    static boolean packaged(String name) {
        return name.contains("class path resource [application") || name.equals("configurationCenterPackagedDefaults");
    }
    public static Optional<ConfigurationStore> store(Environment environment) {
        if(environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().get(NAME) instanceof ConfigurationSource source)
            return Optional.of(source.getSource());
        return Optional.empty();
    }
    public static ConfigurationStore install(ConfigurableEnvironment environment) {
        var existing=store(environment);
        if(existing.isPresent()) return existing.get();
        var source=new ConfigurationSource(ConfigurationStore.open(environment));
        String before=null;
        for (var property:environment.getPropertySources()) if (packaged(property.getName()) || property.getName().equals("defaultProperties")) { before=property.getName();break; }
        if(before==null) environment.getPropertySources().addLast(source);
        else environment.getPropertySources().addBefore(before,source);
        for(String key:List.of("javax.net.ssl.trustStore","javax.net.ssl.trustStorePassword","javax.net.ssl.trustStoreType")) {
            String resolved=override(environment,ConfigurationCatalog.get(key)).map(Override::value)
                    .orElseGet(()->source.getSource().active(key).orElse(null));
            // Mirroring GUI TLS settings into JSSE must not turn them into apparent
            // deployment overrides on the Configuration Center page.
            boolean originalJvm=System.getProperty(key)!=null && !Objects.equals(APPLIED_SSL.get(key),System.getProperty(key));
            if(resolved!=null && !resolved.isBlank()) {
                System.setProperty(key,resolved);if(!originalJvm) APPLIED_SSL.put(key,resolved);
            } else if(!originalJvm && APPLIED_SSL.containsKey(key)) { System.clearProperty(key);APPLIED_SSL.remove(key); }
        }
        return source.getSource();
    }
}
