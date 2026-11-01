package info.trizub.clamav.webclient.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Every editable field crosses the real review, atomic save and startup snapshot boundaries. */
class ConfigurationAllFieldsTest {
    @TempDir Path folder;
    static Stream<ConfigurationSetting> fields() { return ConfigurationCatalog.all().stream(); }
    ConfigurationStore store() { return new ConfigurationStore(folder.resolve("center.properties"),folder.resolve("center.key")); }
    Map<String,String> prerequisites() throws Exception {
        var values=new LinkedHashMap<>(ConfigurationCatalog.defaults());
        values.putAll(Map.of("CLAMAV_REST_URL","https://rest.example.test", "clamav.rest.enabled","false",
            "app.webhook.url","https://hooks.example.test/events", "clamav.metrics.scrape-token","synthetic-scrape-token-0123456789-abcdefgh",
            "clamav.identity.ldap.url","ldaps://directory.example.test:636", "clamav.identity.ldap.base-dn","dc=example,dc=test",
            "clamav.identity.ldap.manager-dn","cn=reader,dc=example,dc=test", "clamav.identity.ldap.manager-password","synthetic-bind-secret",
            "clamav.identity.oidc.issuer","https://identity.example.test", "clamav.identity.oidc.client-id","test-client"));
        values.put("clamav.identity.oidc.client-secret","synthetic-client-secret");
        values.put("clamav.identity.ldap.group-search-base","ou=groups");
        values.put("clamav.identity.ldap.groups.admin","scanner-admins");
        values.put("clamav.identity.oidc.groups.admin","scanner-admins");
        return values;
    }
    String alternate(ConfigurationSetting field,Map<String,String> baseline) throws Exception {
        String key=field.key(),before=baseline.get(key);
        if(key.endsWith("-password-file") || key.endsWith("-secret-file")) {
            Path file=folder.resolve("mounted-secret");Files.writeString(file,"synthetic-mounted-secret");return file.toString();
        }
        if(key.equals("javax.net.ssl.trustStore")) {
            Path file=folder.resolve("truststore");Files.write(file,new byte[]{1});return file.toString();
        }
        if(field.secret()) return key.equals("app.webhook.url")?"https://hooks.example.test/changed":"synthetic-replacement-secret-0123456789";
        if(field.type().equals("boolean")) return Boolean.toString(!Boolean.parseBoolean(before));
        if(field.type().equals("number")) {
            long n=Long.parseLong(before);return Long.toString(n==field.maximum()?n-1:n+1);
        }
        if(field.type().equals("select")) return field.choices().stream().filter(s->!s.equals(before)).findFirst().orElseThrow();
        if(field.type().equals("duration")) return "7s";
        if(field.type().equals("timezone")) return "Europe/Bucharest";
        if(field.type().equals("jdbc")) return "jdbc:h2:mem:field_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        if(field.type().equals("path")) return folder.resolve("changed-"+field.section()).toString();
        if(field.type().equals("url")) return "https://changed.example.test/base";
        if(key.endsWith("_PATH")) return "/changed/"+key.toLowerCase(Locale.ROOT);
        if(key.equals("CLAMAV_REST_FORM_KEY")) return "DOCUMENTS";
        if(key.equals("CLAMAV_REST_WEBHOOK_FIELD")) return "callbackId";
        if(key.equals("spring.flyway.baseline-version")) return "2";
        if(key.startsWith("spring.servlet.multipart.")) return "8MB";
        if(key.equals("management.server.port")) return "9091";
        if(key.equals("server.servlet.context-path")) return "/scanner";
        if(key.equals("app.allowedScanRoots")) return folder.toString();
        if(key.equals("clamav.service.host")) return "daemon.example.test";
        if(key.equals("center.clamd.name")) return "Primary scanner";
        if(key.equals("clamav.identity.ldap.url")) return "ldaps://changed-directory.example.test:636";
        if(key.endsWith("scopes")) return "openid,email,profile,custom:read";
        if(key.endsWith("user-search-filter")) return "(mail={0})";
        if(key.endsWith("group-search-filter")) return "(uniqueMember={0})";
        if(key.endsWith("base-dn") || key.endsWith("manager-dn")) return "dc=changed,dc=test";
        if(key.endsWith("search-base")) return "ou=changed";
        return "changed-value";
    }
    @ParameterizedTest(name="{0}") @MethodSource("fields")
    void eachFieldReviewsPersistsAndActivatesAtItsDeclaredBoundary(ConfigurationSetting field) throws Exception {
        var baseline=prerequisites();
        // A valid explicit driver is meaningful only with a matching JDBC family.
        if(field.key().equals("spring.datasource.driver-class-name")) baseline.put("spring.datasource.url","jdbc:h2:mem:field_driver");
        var store=store();store.save("0",baseline,Set.of(),true,"fixture");store=store();
        var env=new MockEnvironment();env.getPropertySources().addLast(new ConfigurationSource(store));
        var engine=new ConfigurationEngine(env,store,Map::of);
        String before=engine.values(false).get(field.key()),changed=alternate(field,baseline);
        var review=engine.review("1",Map.of(field.key(),changed),Set.of());
        assertThat(review.summary()).as(field.key()).anyMatch(c->c.key().equals(field.key()));
        engine.apply(review,true,"field-audit");
        String normalized=ConfigurationCatalog.validate(field,changed);
        assertThat(store.saved(field.key())).as(field.key()).contains(normalized);
        assertThat(engine.values(false).get(field.key())).isEqualTo(field.live()?normalized:before);
        if(field.secret()) {
            assertThat(Files.readString(store.file())).doesNotContain(changed);
            assertThat(engine.view().toString()).doesNotContain(changed);
        }
        var reopened=store();var restarted=new MockEnvironment();restarted.getPropertySources().addLast(new ConfigurationSource(reopened));
        assertThat(new ConfigurationEngine(restarted,reopened,Map::of).values(false).get(field.key())).isEqualTo(normalized);
    }
    @ParameterizedTest(name="deployment override: {0}") @MethodSource("fields")
    void deploymentAliasesLockEveryFieldWithoutErasingItsSavedValue(ConfigurationSetting field) throws Exception {
        var saved=prerequisites();var store=store();store.save("0",saved,Set.of(),true,"fixture");
        String alias=field.environment().isEmpty()?field.key():field.environment().get(0);
        String controlled=alternate(field,saved);
        var env=new MockEnvironment();env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",Map.of(alias,controlled)));
        env.getPropertySources().addLast(new ConfigurationSource(store));
        var engine=new ConfigurationEngine(env,store,Map::of);
        assertThat(engine.locked(field.key())).isTrue();
        assertThat(ConfigurationSource.override(env,field).orElseThrow().value()).isEqualTo(controlled);
        String attempted=field.secret() && saved.get(field.key()).isBlank()?"attempted-secret-replacement-0123456789":saved.get(field.key());
        assertThatThrownBy(()->engine.review("1",Map.of(field.key(),attempted),Set.of())).hasMessageContaining("deployment");
        assertThat(store.saved(field.key()).orElse("")).isEqualTo(saved.get(field.key()));
    }
    @Test void invalidValuesAreRejectedBeforeTheyCanBeSavedOrSilentlyReplacedByClientDefaults() {
        for(var entry:Map.of("CLAMAV_REST_FORM_KEY","bad field", "CLAMAV_REST_WEBHOOK_FIELD","1invalid",
            "app.storage.uploadDir","", "app.storage.quarantineDir","", "spring.flyway.baseline-version","invalid-version",
            "api.v1.max-page-size","501", "clamav.metrics.clamd-max-endpoints","13", "CLAMAV_REST_STATS_PATH","/bad path",
            "CLAMAV_REST_JOBS_PATH","/bad%xx").entrySet())
            assertThatThrownBy(()->ConfigurationCatalog.validate(ConfigurationCatalog.get(entry.getKey()),entry.getValue())).as(entry.getKey()).isInstanceOf(IllegalArgumentException.class);
    }
}
