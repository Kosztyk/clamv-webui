package info.trizub.clamav.webclient.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deployment settings override Configuration Center, then legacy UI settings.
 * Secrets are AES-256-GCM encrypted at rest; the independent key lives on a mounted
 * Docker secret / persistent conf volume, not in the database. Never echo secrets.
 * This service is per instance; it is NOT a distributed HA config bus.
 */
@Service
@DependsOn("flywayInitializer")
public class AuthenticationSettingsService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.core.env.Environment environment;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdentityProperties active;
    // Keep bootstrap secret sources independent from the mutable UI-managed properties.
    private final Map<String, BootstrapSecret> bootstrapSecrets;
    private record BootstrapSecret(String value, String file) {
        String resolve() { return SecretResolver.resolve(value, file); }
    }
    private final AtomicLong revision = new AtomicLong();
    private final Map<String, Map<String,String>> persisted = new HashMap<>();
    private final Map<String, Boolean> secretSaved = new HashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private volatile String lastChangeBy = "Environment";

    public AuthenticationSettingsService(JdbcTemplate jdbc, ObjectMapper json, IdentityProperties active) {
        this.jdbc=jdbc; this.json=json; this.active=active;
        bootstrapSecrets = Map.of(
            "ldap", new BootstrapSecret(active.getLdap().getManagerPassword(), active.getLdap().getManagerPasswordFile()),
            "oidc", new BootstrapSecret(active.getOidc().getClientSecret(), active.getOidc().getClientSecretFile()));
    }
    @PostConstruct public synchronized void load() {
        jdbc.query("SELECT provider, settings_json, encrypted_secret, updated_by FROM auth_provider_settings", rs -> {
            String provider=rs.getString(1);
            Map<String,String> options=parse(rs.getString(2));
            String encrypted=rs.getString(3);
            String secret=encrypted == null ? "" : decrypt(encrypted);
            persisted.put(provider, Map.copyOf(options));
            secretSaved.put(provider, encrypted!=null);
            apply(provider, options, secret, encrypted!=null);
            lastChangeBy=rs.getString(4);
        });
        for(String provider:List.of("ldap","oidc")) if(!persisted.containsKey(provider))
            apply(provider,snapshot(provider),environmentalSecret(provider),true);
        revision.incrementAndGet();
    }
    public long revision() { return revision.get(); }
    public IdentityProperties properties() { return active; }
    public synchronized boolean uiManaged(String name) { return persisted.containsKey(name); }
    public synchronized boolean hasSecret(String name) {
        return !effectiveSecret(name).isBlank();
    }
    public synchronized Map<String,String> values(String provider) {
        Map<String,String> values = new LinkedHashMap<>(snapshot(provider));
        values.putAll(persisted.getOrDefault(provider, Map.of()));
        return Map.copyOf(configurationOverrides(provider,values));
    }
    public synchronized void save(String provider, Map<String,String> submitted, String replacementSecret, String operator) {
        checkProvider(provider);
        Map<String,String> normalized=draft(provider,submitted);
        requireEditableSecret(provider,replacementSecret);
        String oldCipher=jdbc.query("SELECT encrypted_secret FROM auth_provider_settings WHERE provider=?", rs -> rs.next() ? rs.getString(1) : null, provider);
        String cipher=replacementSecret!=null && !replacementSecret.isBlank() ? encrypt(replacementSecret) : oldCipher;
        String effectiveSecret=cipher!=null ? decrypt(cipher) : environmentalSecret(provider);
        validate(provider,normalized,effectiveSecret);
        String stored=serialize(normalized);
        // Atomic transaction per statement; only update live authentication after DB write succeeds.
        int changed=jdbc.update("UPDATE auth_provider_settings SET settings_json=?, encrypted_secret=?, updated_at=?, updated_by=? WHERE provider=?",
                stored,cipher,java.sql.Timestamp.from(Instant.now()),operator,provider);
        if (changed==0) jdbc.update("INSERT INTO auth_provider_settings (provider, settings_json, encrypted_secret, updated_at, updated_by) VALUES (?,?,?,?,?)",
                provider,stored,cipher,java.sql.Timestamp.from(Instant.now()),operator);
        persisted.put(provider,Map.copyOf(normalized));
        secretSaved.put(provider,cipher!=null);
        apply(provider,normalized,effectiveSecret,cipher!=null);
        lastChangeBy=operator;
        revision.incrementAndGet();
    }
    public synchronized String effectiveSecret(String provider) {
        checkProvider(provider);
        String cipher=jdbc.query("SELECT encrypted_secret FROM auth_provider_settings WHERE provider=?", rs -> rs.next()?rs.getString(1):null, provider);
        return configurationSecret(provider,cipher!=null?decrypt(cipher):environmentalSecret(provider));
    }
    public synchronized Map<String,String> draft(String provider, Map<String,String> submitted) {
        if (centerManaged(provider)) throw new IllegalArgumentException(provider.toUpperCase(Locale.ROOT)+" is managed by Configuration Center; edit it there and apply the required restart");
        Map<String,String> normalized=normalize(provider, submitted);
        if(!configurationOverrides(provider,normalized).equals(normalized))
            throw new IllegalArgumentException(provider.toUpperCase(Locale.ROOT)+" contains deployment-controlled fields; inspect their source in Configuration Center");
        return normalized;
    }
    public synchronized void validateDraft(String provider, Map<String,String> fields, String replacement) {
        requireEditableSecret(provider,replacement);
        validate(provider, normalize(provider,fields), replacement!=null&&!replacement.isBlank()?replacement:effectiveSecret(provider));
    }
    private String environmentalSecret(String provider) {
        checkProvider(provider);
        String configured=configurationSecret(provider,null);
        return configured!=null?configured:bootstrapSecrets.get(provider).resolve();
    }
    private static void checkProvider(String provider) {
        if (!"ldap".equals(provider) && !"oidc".equals(provider)) throw new IllegalArgumentException("Unsupported provider");
    }
    private static String val(Map<String,String> props,String key,String fallback) { return props.getOrDefault(key, fallback); }
    private static boolean flag(Map<String,String> m,String k) { return "true".equalsIgnoreCase(m.getOrDefault(k,"false")); }
    private static int number(Map<String,String> m, String key, int defaultValue) { try { return Integer.parseInt(val(m,key,""+defaultValue)); } catch(NumberFormatException e){throw new IllegalArgumentException("Invalid numeric setting: "+key);} }
    private static void groups(IdentityProperties.RoleGroups target, Map<String,String> settings) {
        target.setViewer(val(settings,"viewer",""));target.setOperator(val(settings,"operator",""));target.setAdmin(val(settings,"admin",""));
    }
    private synchronized void apply(String provider,Map<String,String> m,String secret,boolean encrypted) {
        m=configurationOverrides(provider,m);
        String configured=configurationSecret(provider,secret);
        if(!Objects.equals(configured,secret)) { secret=configured;encrypted=true; }
        if (provider.equals("ldap")) {
            IdentityProperties.Ldap l=active.getLdap();
            l.setEnabled(flag(m,"enabled"));l.setUrl(val(m,"url",""));l.setBaseDn(val(m,"baseDn",""));
            l.setManagerDn(val(m,"managerDn",""));
            l.setManagerPassword(encrypted ? secret : bootstrapSecrets.get("ldap").value());
            l.setManagerPasswordFile(encrypted ? "" : bootstrapSecrets.get("ldap").file());
            l.setUserSearchBase(val(m,"userSearchBase",""));l.setUserSearchFilter(val(m,"userSearchFilter","(uid={0})"));
            l.setGroupSearchBase(val(m,"groupSearchBase",""));l.setGroupMemberAttribute(val(m,"groupMemberAttribute","member"));
            l.setGroupSearchFilter(val(m,"groupSearchFilter",""));
            l.setGroupNameAttribute(val(m,"groupNameAttribute","cn"));l.setNestedGroups(flag(m,"nestedGroups"));
            l.setMaxNestedDepth(number(m,"maxNestedDepth",3));l.setStartTls(flag(m,"startTls"));l.setPooled(flag(m,"pooled"));
            l.setReferrals(val(m,"referrals","ignore"));l.setConnectTimeoutMillis(number(m,"connectTimeoutMillis",5000));
            l.setReadTimeoutMillis(number(m,"readTimeoutMillis",5000));groups(l.getGroups(),m);
        } else {
            IdentityProperties.Oidc o=active.getOidc();
            o.setEnabled(flag(m,"enabled"));o.setRegistrationId("clamav");o.setIssuer(val(m,"issuer",""));
            o.setClientId(val(m,"clientId",""));
            o.setClientSecret(encrypted ? secret : bootstrapSecrets.get("oidc").value());
            o.setClientSecretFile(encrypted ? "" : bootstrapSecrets.get("oidc").file());
            // Older settings JSON has no scopes field: retain bootstrap scopes on upgrade.
            o.setScopes(OidcScopes.parse(val(m,"scopes",String.join(",",o.getScopes()))));
            o.setGroupsClaim(val(m,"groupsClaim","groups"));o.setButtonLabel(val(m,"buttonLabel","Sign in with SSO"));
            o.setLogoutMode(val(m,"logoutMode","local"));groups(o.getGroups(),m);
        }
    }
    private Map<String,String> snapshot(String provider) {
        Map<String,String> m=new LinkedHashMap<>();
        if (provider.equals("ldap")) {
            IdentityProperties.Ldap l=active.getLdap();
            m.put("enabled",Boolean.toString(l.isEnabled()));m.put("url",l.getUrl());m.put("baseDn",l.getBaseDn());
            m.put("managerDn",l.getManagerDn());m.put("userSearchBase",l.getUserSearchBase());
            m.put("userSearchFilter",l.getUserSearchFilter());m.put("groupSearchBase",l.getGroupSearchBase());
            m.put("groupSearchFilter",l.getGroupSearchFilter());
            m.put("groupMemberAttribute",l.getGroupMemberAttribute());m.put("groupNameAttribute",l.getGroupNameAttribute());
            m.put("nestedGroups",Boolean.toString(l.isNestedGroups()));m.put("maxNestedDepth",""+l.getMaxNestedDepth());
            m.put("startTls",Boolean.toString(l.isStartTls()));m.put("pooled",Boolean.toString(l.isPooled()));
            m.put("referrals",l.getReferrals());m.put("connectTimeoutMillis",""+l.getConnectTimeoutMillis());
            m.put("readTimeoutMillis",""+l.getReadTimeoutMillis());putGroups(m,l.getGroups());
        } else {
            IdentityProperties.Oidc o=active.getOidc();
            m.put("enabled",Boolean.toString(o.isEnabled()));m.put("issuer",o.getIssuer());m.put("clientId",o.getClientId());
            m.put("scopes",String.join(",",o.getScopes()));
            m.put("groupsClaim",o.getGroupsClaim());m.put("buttonLabel",o.getButtonLabel());
            m.put("logoutMode",o.getLogoutMode());putGroups(m,o.getGroups());
        }
        return m;
    }
    private static void putGroups(Map<String,String> m, IdentityProperties.RoleGroups g) {
        m.put("viewer",g.getViewer());m.put("operator",g.getOperator());m.put("admin",g.getAdmin());
    }
    private static final Set<String> LDAP_KEYS=Set.of("enabled","url","baseDn","managerDn","userSearchBase","userSearchFilter",
        "groupSearchBase","groupSearchFilter","groupMemberAttribute","groupNameAttribute","nestedGroups","maxNestedDepth","startTls","pooled",
        "referrals","connectTimeoutMillis","readTimeoutMillis","viewer","operator","admin");
    private static final Set<String> OIDC_KEYS=Set.of("enabled","issuer","clientId","scopes","groupsClaim","buttonLabel","logoutMode","viewer","operator","admin");
    private Map<String,String> normalize(String provider, Map<String,String> input) {
        checkProvider(provider);
        Map<String,String> defaults=provider.equals("ldap")
          ? Map.of("userSearchFilter","(uid={0})", "groupMemberAttribute","member", "groupNameAttribute","cn",
              "maxNestedDepth","3", "connectTimeoutMillis","5000", "readTimeoutMillis","5000", "referrals","ignore",
              "groupSearchFilter",active.getLdap().getGroupSearchFilter())
          : Map.of("groupsClaim","groups", "buttonLabel","Sign in with SSO", "logoutMode","local",
              "scopes",String.join(",",active.getOidc().getScopes()));
        Map<String,String> normalized=new LinkedHashMap<>();
        for(String key : provider.equals("ldap")?LDAP_KEYS:OIDC_KEYS) {
            // Missing optional parameters get safe defaults. An explicitly cleared input
            // stays blank and is validated; it must never silently replace the user's edit.
            String raw=input.getOrDefault(key,defaults.getOrDefault(key,"")).trim();
            if (raw.length()>1024 || raw.contains("\u0000") || raw.contains("\n") || raw.contains("\r"))
                throw new IllegalArgumentException("Invalid value for "+key);
            normalized.put(key,raw);
        }
        normalized.put("enabled",Boolean.toString(flag(normalized,"enabled")));
        if (provider.equals("oidc")) normalized.put("scopes",String.join(",",OidcScopes.parse(normalized.get("scopes"))));
        for(String key:List.of("startTls","nestedGroups","pooled")) if(provider.equals("ldap")) normalized.put(key,Boolean.toString(flag(normalized,key)));
        return normalized;
    }
    public static Set<String> optionKeys(String provider) {
        checkProvider(provider);
        return provider.equals("ldap")?LDAP_KEYS:OIDC_KEYS;
    }
    public static void validate(String provider, Map<String,String> m,String secret) {
        checkProvider(provider);
        for (String value:m.values())
            if(value==null || value.length()>1024 || value.indexOf('\0')>=0 || value.indexOf('\n')>=0 || value.indexOf('\r')>=0)
                throw new IllegalArgumentException("Invalid identity configuration value");
        if (provider.equals("ldap")) {
            LdapGroupSearchFilter.validate(val(m,"groupSearchFilter",""), val(m,"groupMemberAttribute","member"), flag(m,"nestedGroups"));
            if (number(m,"connectTimeoutMillis",5000)<1000 || number(m,"connectTimeoutMillis",5000)>30000 ||
                number(m,"readTimeoutMillis",5000)<1000 || number(m,"readTimeoutMillis",5000)>30000 ||
                number(m,"maxNestedDepth",3)<1 || number(m,"maxNestedDepth",3)>8)
                throw new IllegalArgumentException("LDAP timeouts or nesting depth out of range");
            if (!List.of("ignore","throw","follow").contains(val(m,"referrals","ignore"))) throw new IllegalArgumentException("Invalid LDAP referrals policy");
            if(flag(m,"enabled")) {
                String url=val(m,"url","");
                if(!(url.startsWith("ldaps://") || (url.startsWith("ldap://") && flag(m,"startTls"))))
                    throw new IllegalArgumentException("LDAP must use LDAPS or StartTLS");
                // Explain each missing field rather than resetting an entire form after an opaque error.
                // Never include the supplied bind password in validation output.
                List<String> missing=new ArrayList<>();
                if (val(m,"baseDn","").isBlank()) missing.add("Base DN");
                if (val(m,"managerDn","").isBlank()) missing.add("Bind / manager DN");
                if (secret.isBlank()) missing.add("Bind password (enter a new password or retain a saved secret)");
                if (val(m,"userSearchFilter","").isBlank()) missing.add("User search filter");
                else if (!val(m,"userSearchFilter","").contains("{0}"))
                    throw new IllegalArgumentException("LDAP user search filter must contain {0}, for example (uid={0})");
                if (val(m,"groupSearchBase","").isBlank()) missing.add("Group search base");
                if (!missing.isEmpty()) throw new IllegalArgumentException("LDAP missing required fields: "+String.join(", ",missing));
            }
        } else {
            OidcScopes.parse(val(m,"scopes","openid,profile,email,groups"));
            if (!List.of("local","provider").contains(val(m,"logoutMode","local"))) throw new IllegalArgumentException("Invalid logout mode");
            if(flag(m,"enabled")) {
                if(!val(m,"issuer","").startsWith("https://") || val(m,"clientId","").isBlank() || secret.isBlank())
                    throw new IllegalArgumentException("OIDC HTTPS issuer, client ID and secret are required");
                if(!val(m,"groupsClaim","groups").matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))
                    throw new IllegalArgumentException("Invalid OIDC groups claim");
            }
        }
        if (flag(m,"enabled") && val(m,"viewer","").isBlank() && val(m,"operator","").isBlank() && val(m,"admin","").isBlank())
            throw new IllegalArgumentException("At least one explicit group must be mapped; unmapped users receive no access");
    }
    private Map<String,String> parse(String jsonText) {
        try {return json.readValue(jsonText,new TypeReference<LinkedHashMap<String,String>>(){});}
        catch(Exception e){throw new IllegalStateException("Invalid persisted authentication configuration",e);}
    }

    public boolean centerManaged(String provider) {
        if(environment==null) return false;
        return info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment)
            .map(store->store.snapshot().values().keySet().stream().anyMatch(k->k.startsWith("clamav.identity."+provider+"."))).orElse(false);
    }
    private Map<String,String> configurationOverrides(String provider,Map<String,String> input) {
        Map<String,String> values=new LinkedHashMap<>(input);
        if(environment==null) return values;
        for(var field:info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all()) {
            if(!field.section().equals(provider) || field.secret() || field.legacy().isBlank()) continue;
            String replacement=configurationValue(field);
            if(replacement!=null) values.put(field.legacy(),replacement);
        }
        return values;
    }
    private String configurationValue(info.trizub.clamav.webclient.configuration.ConfigurationSetting field) {
        if(environment==null) return null;
        return info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field)
            .map(info.trizub.clamav.webclient.configuration.ConfigurationSource.Override::value)
            .orElseGet(()->info.trizub.clamav.webclient.configuration.ConfigurationSource.store(environment)
                .flatMap(store->store.active(field.key())).orElse(null));
    }
    private String configurationSecret(String provider,String fallback) {
        if(environment==null) return fallback;
        String base="clamav.identity."+provider+".";
        String suffix=provider.equals("ldap")?"manager-password":"client-secret";
        String direct=configurationValue(info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get(base+suffix));
        if(direct!=null && !direct.isBlank()) return direct;
        String file=configurationValue(info.trizub.clamav.webclient.configuration.ConfigurationCatalog.get(base+suffix+"-file"));
        return file==null || file.isBlank()?(direct==null?fallback:direct):SecretResolver.resolve("",file);
    }
    public Set<String> lockedFields(String provider) {
        Set<String> fields=new LinkedHashSet<>();
        if(environment==null) return fields;
        boolean managed=centerManaged(provider);
        for(var field:info.trizub.clamav.webclient.configuration.ConfigurationCatalog.all())
            if(field.section().equals(provider) && (managed || info.trizub.clamav.webclient.configuration.ConfigurationSource.override(environment,field).isPresent())) {
                if(field.secret()) fields.add("secret");else if(!field.legacy().isBlank()) fields.add(field.legacy());
            }
        return fields;
    }
    public void requireEditableSecret(String provider,String replacement) {
        if(replacement!=null && !replacement.isBlank() && lockedFields(provider).contains("secret"))
            throw new IllegalArgumentException(provider.toUpperCase(Locale.ROOT)+" secret is controlled by deployment configuration or Configuration Center");
    }
    private String serialize(Map<String,String> data) {
        try{return json.writeValueAsString(data);}catch(Exception e){throw new IllegalStateException("Could not encode provider configuration",e);}
    }
    private byte[] key() {
        String env=System.getenv("CLAMAV_AUTH_SETTINGS_KEY");
        if(env!=null && !env.isBlank()) return keyDecode(env);
        Path path=Path.of(System.getenv().getOrDefault("CLAMAV_AUTH_SETTINGS_KEY_FILE","./conf/auth-settings.key"));
        try {
            if(Files.notExists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                byte[] generated=new byte[32];rng.nextBytes(generated);
                try {
                    try { Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                    catch(UnsupportedOperationException ex) { Files.createFile(path); }
                    Files.writeString(path,Base64.getEncoder().encodeToString(generated),StandardOpenOption.WRITE);
                    try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); }
                    catch(UnsupportedOperationException ignored) { }
                } catch(FileAlreadyExistsException ignored) { }
            }
            return keyDecode(Files.readString(path).trim());
        }catch(Exception e){throw new IllegalStateException("Authentication encryption key is missing or inaccessible; restore the configured key",e);}
    }
    private static byte[] keyDecode(String encoded) {
        byte[] bytes=Base64.getDecoder().decode(encoded);
        if(bytes.length!=32) throw new IllegalStateException("Authentication encryption key must be 32 bytes, base64 encoded");
        return bytes;
    }
    private String encrypt(String secret) {
        try {
            if(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024) throw new IllegalArgumentException("Secret exceeds maximum length");
            byte[] iv=new byte[12];rng.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=cipher.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] joined=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,joined,0,iv.length);
            System.arraycopy(encrypted,0,joined,iv.length,encrypted.length);
            return "gcm1:"+Base64.getEncoder().encodeToString(joined);
        } catch(Exception e){throw new IllegalStateException("Unable to encrypt authentication secret",e);}
    }
    private String decrypt(String encoded) {
        try {
            if(!encoded.startsWith("gcm1:")) throw new IllegalStateException("Unsupported encrypted secret format");
            byte[] bytes=Base64.getDecoder().decode(encoded.substring(5));
            if(bytes.length<29) throw new IllegalStateException("Invalid secret ciphertext");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("Cannot decrypt saved provider credentials; restore the original authentication key",e);}
    }
}
