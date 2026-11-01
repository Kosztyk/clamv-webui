package info.trizub.clamav.webclient.configuration;

import info.trizub.clamav.webclient.util.AtomicPropertiesFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;

/** Database-independent configuration. Credentials never enter a plaintext settings file. */
public final class ConfigurationStore {
    public record Snapshot(String revision, Map<String,String> values, boolean setupComplete) {
        public Snapshot { values=Map.copyOf(values); }
    }
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private final Path file;
    private final Path keyFile;
    private final Object lock;
    private volatile Snapshot snapshot;
    private final Map<String,String> startup;
    private volatile Map<String,String> deferredWebhooks=Map.of();
    public ConfigurationStore(Path file,Path keyFile) {
        this.file=file.toAbsolutePath().normalize();this.keyFile=keyFile.toAbsolutePath().normalize();
        this.lock=LOCKS.computeIfAbsent(this.file,k->new Object());
        this.snapshot=read();this.startup=snapshot.values();
    }
    public static ConfigurationStore open(Environment environment) {
        Path file=Path.of(environment.getProperty("clamav.configuration.file",
                environment.getProperty("CLAMAV_CONFIGURATION_FILE","conf/configuration-center.properties")));
        Path key=Path.of(environment.getProperty("clamav.configuration.key-file",
                environment.getProperty("CLAMAV_CONFIGURATION_KEY_FILE",file.toAbsolutePath().getParent().resolve("configuration-center.key").toString())));
        return new ConfigurationStore(file,key);
    }
    public Path file() { return file; }
    public Path directory() { return file.getParent(); }
    public Snapshot snapshot() { return snapshot; }
    public Snapshot refresh() { synchronized(lock) { publish(read());return snapshot; } }
    private void publish(Snapshot next) {
        String policy="clamav.webhook.allow-private-addresses",running=startup.getOrDefault(policy,"false");
        if(Objects.equals(snapshot.values().getOrDefault(policy,"false"),running)
                && !Objects.equals(next.values().getOrDefault(policy,"false"),running)) {
            Map<String,String> held=new LinkedHashMap<>();
            snapshot.values().forEach((key,value)->{if(key.startsWith("app.webhook.")) held.put(key,value);});
            deferredWebhooks=Map.copyOf(held);
        }
        snapshot=next;
    }
    public Optional<String> saved(String key) { return Optional.ofNullable(snapshot.values().get(key)); }
    public Optional<String> active(String key) {
        var definition=ConfigurationCatalog.find(key);
        if(key.startsWith("app.webhook.") && !Objects.equals(
                startup.getOrDefault("clamav.webhook.allow-private-addresses","false"),
                snapshot.values().getOrDefault("clamav.webhook.allow-private-addresses","false")))
            return Optional.ofNullable(deferredWebhooks.get(key));
        return Optional.ofNullable((definition.isPresent() && definition.get().live() ? snapshot.values() : startup).get(key));
    }
    private Snapshot read() {
        if (Files.notExists(file)) return new Snapshot("0",Map.of(),false);
        try {
            Properties properties=AtomicPropertiesFile.loadOrCreate(file);
            if (!"1".equals(properties.getProperty("_format","1"))) throw new IllegalStateException("Unsupported configuration file format");
            Map<String,String> values=new LinkedHashMap<>();
            for (var definition:ConfigurationCatalog.all()) if (properties.containsKey(definition.key())) {
                String value=properties.getProperty(definition.key());
                values.put(definition.key(),definition.secret()?decrypt(definition.key(),value):value);
            }
            return new Snapshot(properties.getProperty("_revision","0"),values,
                    Boolean.parseBoolean(properties.getProperty("_setupComplete","false")));
        } catch (Exception e) {
            throw new IllegalStateException("Configuration could not be read. Restore the configuration file and its encryption key, and check conf ownership.",e);
        }
    }
    public Snapshot save(String expected,Map<String,String> changes,Set<String> clearSecrets,boolean complete,String actor) {
        return save(expected,changes,clearSecrets,complete,actor,null);
    }
    public Snapshot save(String expected,Map<String,String> changes,Set<String> clearSecrets,boolean complete,String actor,String approvedDatabase) {
        synchronized(lock) {
            Snapshot disk=read();
            if (!disk.revision().equals(expected)) throw new ConcurrentModificationException("Configuration changed; reload and review the new values");
            Map<String,String> values=new LinkedHashMap<>(disk.values());
            for (var entry:changes.entrySet()) {
                var definition=ConfigurationCatalog.get(entry.getKey());
                if (definition.secret() && entry.getValue().isBlank()) continue;
                values.put(entry.getKey(),ConfigurationCatalog.validate(definition,entry.getValue()));
            }
            for (String key:clearSecrets) {
                if (!ConfigurationCatalog.get(key).secret()) throw new IllegalArgumentException("Only secret fields can be explicitly cleared");
                values.put(key,"");
            }
            String nextRevision=Long.toString(Math.addExact(Long.parseLong(disk.revision()),1));
            Properties output=new Properties();
            output.setProperty("_format","1");output.setProperty("_revision",nextRevision);
            output.setProperty("_setupComplete",Boolean.toString(complete || disk.setupComplete()));
            for (var entry:values.entrySet()) output.setProperty(entry.getKey(),
                    ConfigurationCatalog.get(entry.getKey()).secret()?encrypt(entry.getKey(),entry.getValue()):entry.getValue());
            Set<String> changedKeys=new LinkedHashSet<>(changes.keySet());changedKeys.addAll(clearSecrets);
            audit(actor,"CONFIGURATION_SAVE_REQUESTED",changedKeys,"REQUESTED");
            if(approvedDatabase!=null) approveDatabase(approvedDatabase);
            try { AtomicPropertiesFile.storeAtomic(file,output); }
            catch(Exception e) { throw new IllegalStateException("Configuration could not be saved; check the persistent conf volume",e); }
            publish(new Snapshot(nextRevision,values,complete || disk.setupComplete()));
            try { audit(actor,"CONFIGURATION_SAVED",changedKeys,"SUCCESS"); }
            catch(IllegalStateException e) { org.slf4j.LoggerFactory.getLogger(ConfigurationStore.class).error("Configuration was saved, but its completion audit could not be appended. Check conf permissions."); }
            return snapshot;
        }
    }
    public void audit(String actor,String action,Collection<String> keys,String result) {
        try {
            Path path=directory().resolve("configuration-audit.jsonl");privateFile(path);
            String safeActor=actor==null?"setup":actor.replaceAll("[\\r\\n\\p{Cntrl}]","");
            if (safeActor.length()>128) safeActor=safeActor.substring(0,128);
            var record=Map.of("at",Instant.now().toString(),"actor",safeActor,"action",action,
                    "keys",keys.stream().sorted().toList(),"outcome",result);
            synchronized(lock) { Files.writeString(path,new ObjectMapper().writeValueAsString(record)+"\n",StandardOpenOption.APPEND); }
        } catch (Exception e) { throw new IllegalStateException("Configuration audit could not be recorded; check conf permissions",e); }
    }
    public String recoveryToken() {
        synchronized(lock) {
            Path tokenFile=directory().resolve("configuration-recovery-token");
            try {
                if (Files.notExists(tokenFile)) {
                    byte[] random=new byte[32];new SecureRandom().nextBytes(random);
                    privateFile(tokenFile);
                    Files.writeString(tokenFile,Base64.getUrlEncoder().withoutPadding().encodeToString(random),StandardOpenOption.TRUNCATE_EXISTING);
                }
                String token=Files.readString(tokenFile).trim();
                if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Invalid recovery token");
                return token;
            } catch(Exception e) { throw new IllegalStateException("Recovery token unavailable; check conf ownership",e); }
        }
    }
    private Properties databaseState(String filename) {
        try {
            Path path=directory().resolve(filename);
            if(Files.notExists(path)) return new Properties();
            return AtomicPropertiesFile.loadOrCreate(path);
        } catch(Exception e) { throw new IllegalStateException("Database selection record is unavailable; check conf ownership",e); }
    }
    private static String fingerprint(String url) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public boolean requiresDatabaseChoice(String url) {
        if(!url.startsWith("jdbc:h2:")) return false;
        Properties previous=databaseState("configuration-database-state.properties");
        if(!"external".equals(previous.getProperty("kind"))) return false;
        Properties approved=databaseState("configuration-database-choice.properties");
        return !fingerprint(url).equals(approved.getProperty("fingerprint"))
                || (previous.containsKey("generation")
                    ? !previous.getProperty("generation").equals(approved.getProperty("sourceGeneration"))
                    : Long.parseLong(approved.getProperty("at","0")) < Long.parseLong(previous.getProperty("at","0")));
    }
    public void noteExternalDatabase(String url) { if(!url.isBlank() && !url.startsWith("jdbc:h2:")) recordDatabase(url); }
    public void recordDatabase(String url) {
        Properties state=new Properties();state.setProperty("kind",url.startsWith("jdbc:h2:")?"h2":"external");
        state.setProperty("generation",UUID.randomUUID().toString());
        state.setProperty("fingerprint",fingerprint(url));state.setProperty("at",Long.toString(System.currentTimeMillis()));
        try { AtomicPropertiesFile.storeAtomic(directory().resolve("configuration-database-state.properties"),state); }
        catch(Exception e) { throw new IllegalStateException("Database selection could not be recorded",e); }
    }
    public void approveDatabase(String url) {
        Properties state=new Properties();state.setProperty("fingerprint",fingerprint(url));state.setProperty("at",Long.toString(System.currentTimeMillis()));
        state.setProperty("sourceGeneration",databaseState("configuration-database-state.properties").getProperty("generation",""));
        try { AtomicPropertiesFile.storeAtomic(directory().resolve("configuration-database-choice.properties"),state); }
        catch(Exception e) { throw new IllegalStateException("Database choice could not be recorded; review it again",e); }
    }
    private static void privateFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try { Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch(FileAlreadyExistsException ignored) { }
        catch(UnsupportedOperationException e) { if (Files.notExists(path)) Files.createFile(path); }
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); }
        catch(UnsupportedOperationException ignored) { }
    }
    private byte[] key(boolean create) throws Exception {
        if (Files.notExists(keyFile)) {
            if (!create) throw new IllegalStateException("Configuration encryption key is missing; restore it from backup");
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);privateFile(keyFile);
            Files.writeString(keyFile,Base64.getEncoder().encodeToString(bytes),StandardOpenOption.TRUNCATE_EXISTING);
        }
        byte[] bytes=Base64.getDecoder().decode(Files.readString(keyFile).trim());
        if (bytes.length!=32) throw new IllegalStateException("Invalid configuration encryption key");
        return bytes;
    }
    private String encrypt(String name,String value) {
        try {
            byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(true),"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,combined,0,iv.length);System.arraycopy(encrypted,0,combined,iv.length,encrypted.length);
            return "cc1:"+Base64.getEncoder().encodeToString(combined);
        } catch(Exception e) { throw new IllegalStateException("Configuration encryption failed; existing settings were retained",e); }
    }
    private String decrypt(String name,String value) throws Exception {
        if (!value.startsWith("cc1:")) throw new IllegalStateException("Secret configuration is not encrypted");
        byte[] bytes=Base64.getDecoder().decode(value.substring(4));
        if (bytes.length<28) throw new IllegalStateException("Invalid encrypted configuration");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(false),"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);
    }
}
