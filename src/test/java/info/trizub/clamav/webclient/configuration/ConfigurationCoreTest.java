package info.trizub.clamav.webclient.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.*;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConfigurationCoreTest {
    @TempDir Path folder;
    ConfigurationStore store() { return new ConfigurationStore(folder.resolve("center.properties"),folder.resolve("center.key")); }
    ConfigurationEngine engine(MockEnvironment env,ConfigurationStore store) {
        env.getPropertySources().addLast(new ConfigurationSource(store));
        return new ConfigurationEngine(env,store,Map::of);
    }
    @Test void catalogueIsUniqueAndEveryPackagedDefaultHasAValidScalar() {
        assertThat(ConfigurationCatalog.document().categories()).hasSize(7);
        assertThat(ConfigurationCatalog.defaults()).containsEntry("CLAMAV_REST_URL", "").containsEntry("clamav.public-url", "");
        assertThat(ConfigurationCatalog.all()).hasSizeGreaterThan(100);
        for(var field:ConfigurationCatalog.all()) assertThat(ConfigurationCatalog.validate(field,field.defaultValue())).isNotNull();
        assertThatThrownBy(()->ConfigurationCatalog.get("spring.datasource.jndi-name")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void savesAreEncryptedAuthenticatedAndBlankSecretsAreRetainedUntilExplicitlyCleared() throws Exception {
        var store=store();String secret="synthetic-private-password-0123";
        store.save("0",Map.of("spring.datasource.password",secret),Set.of(),true,"administrator");
        assertThat(Files.readString(store.file())).doesNotContain(secret).contains("cc1\\:");
        assertThat(Files.readString(folder.resolve("configuration-audit.jsonl"))).doesNotContain(secret).contains("spring.datasource.password");
        store.save("1",Map.of("spring.datasource.password",""),Set.of(),false,"administrator");
        assertThat(store().saved("spring.datasource.password")).contains(secret);
        store.save("2",Map.of(),Set.of("spring.datasource.password"),false,"administrator");
        assertThat(store().saved("spring.datasource.password")).contains("");
        assertThat(store().snapshot().setupComplete()).isTrue();
        assertThat(Files.readString(folder.resolve("configuration-audit.jsonl"))).contains("CONFIGURATION_SAVED");
    }
    @Test void ciphertextCannotBeMovedToAnotherCredentialAndMissingKeyIsNeverRegenerated() throws Exception {
        var store=store();store.save("0",Map.of("spring.datasource.password","private-value"),Set.of(),false,"test");
        Properties props=new Properties();try(var in=Files.newInputStream(store.file())) { props.load(in); }
        props.setProperty("CLAMAV_REST_API_KEY",props.getProperty("spring.datasource.password"));
        try(var out=Files.newOutputStream(store.file())) { props.store(out,null); }
        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
        props.remove("CLAMAV_REST_API_KEY");try(var out=Files.newOutputStream(store.file())) { props.store(out,null); }
        Files.delete(folder.resolve("center.key"));
        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
        assertThat(folder.resolve("center.key")).doesNotExist();
    }
    @Test void staleRevisionDoesNotOverwriteAnotherSave() {
        var first=store();var second=store();
        first.save("0",Map.of("app.watch.enabled","true"),Set.of(),true,"first");
        assertThatThrownBy(()->second.save("0",Map.of("app.watch.enabled","false"),Set.of(),true,"second"))
            .isInstanceOf(ConcurrentModificationException.class);
        assertThat(store().saved("app.watch.enabled")).contains("true");
    }
    @Test void liveSettingsChangeImmediatelyButStartupSettingsRemainActiveUntilReopen() {
        var store=store();var engine=engine(new MockEnvironment(),store);
        var review=engine.review("0",Map.of("app.watch.enabled","true","app.concurrentScans","4"),Set.of());
        engine.apply(review,false,"admin");
        assertThat(engine.values(true)).containsEntry("app.concurrentScans","4");
        assertThat(engine.values(false)).containsEntry("app.concurrentScans","2").containsEntry("app.watch.enabled","true");
        assertThat(engine.view().get("pendingRestart")).asList().contains("app.concurrentScans");
        var restarted=engine(new MockEnvironment(),store());
        assertThat(restarted.values(false)).containsEntry("app.concurrentScans","4");
        assertThat(restarted.view().get("pendingRestart")).asList().isEmpty();
    }
    @Test void explicitFalseAndCommandLineOverridesWinWithoutErasingSavedGuiValues() {
        var store=store();store.save("0",Map.of("app.watch.enabled","true","clamav.service.host","saved-host"),Set.of(),true,"test");
        var env=new MockEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",Map.of("CLAMAV_WATCH_ENABLED","false","CLAMAV_HOST","env-host")));
        env.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--clamav.service.host=cli-host"));
        var engine=engine(env,store);
        assertThat(engine.values(true)).containsEntry("app.watch.enabled","false").containsEntry("clamav.service.host","cli-host");
        assertThat(engine.locked("app.watch.enabled")).isTrue();
        assertThatThrownBy(()->engine.review("1",Map.of("app.watch.enabled","true"),Set.of())).hasMessageContaining("deployment");
        assertThat(store.saved("app.watch.enabled")).contains("true");
        env.getPropertySources().remove("systemEnvironment");env.getPropertySources().remove("commandLineArgs");
        assertThat(engine.values(true)).containsEntry("app.watch.enabled","true").containsEntry("clamav.service.host","saved-host");
    }
    @Test void mountedDeploymentSecretLocksInlineAndFileInputsAndWinsOverSavedSecret() throws Exception {
        Path mounted=folder.resolve("mounted-secret");Files.writeString(mounted,"mounted-credential\n");
        var store=store();store.save("0",Map.of("clamav.identity.ldap.manager-password","saved-credential"),Set.of(),true,"test");
        var env=new MockEnvironment();env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE",mounted.toString())));
        var engine=engine(env,store);
        assertThat(engine.values(true)).containsEntry("clamav.identity.ldap.manager-password","mounted-credential");
        assertThat(engine.locked("clamav.identity.ldap.manager-password")).isTrue();
        assertThat(engine.locked("clamav.identity.ldap.manager-password-file")).isTrue();
        assertThatThrownBy(()->engine.review("1",Map.of("clamav.identity.ldap.manager-password","bypass"),Set.of())).hasMessageContaining("deployment");
        assertThat(engine.view().toString()).doesNotContain("mounted-credential","saved-credential");
    }
    @Test void choosingGuiSecretFileClearsSavedInlineCredentialAndTestsUseTheFile() throws Exception {
        var store=store();store.save("0",Map.of("clamav.identity.ldap.manager-password","old-secret"),Set.of(),true,"test");
        Path mounted=folder.resolve("mounted-secret");Files.writeString(mounted,"new-mounted-secret");
        var engine=engine(new MockEnvironment(),store);
        var review=engine.review("1",Map.of("clamav.identity.ldap.manager-password-file",mounted.toString()),Set.of());
        assertThat(engine.draftValues(review)).containsEntry("clamav.identity.ldap.manager-password","new-mounted-secret");
        engine.apply(review,false,"test");
        assertThat(store.saved("clamav.identity.ldap.manager-password")).contains("");
        assertThat(engine.values(true)).containsEntry("clamav.identity.ldap.manager-password","new-mounted-secret");
    }
    @Test void secretsAreMaskedInViewsAndReviewAndReviewCannotBeReplayed() {
        var engine=engine(new MockEnvironment(),store());
        var review=engine.review("0",Map.of("spring.datasource.password","hidden-password"),Set.of());
        assertThat(review.summary().toString()).doesNotContain("hidden-password").contains("[replaced]");
        assertThat(review.sensitive()).isTrue();assertThat(review.databaseChange()).isTrue();
        assertThatThrownBy(()->engine.apply(review,false,"test")).hasMessageContaining("Confirm");
        engine.apply(review,true,"test");
        assertThat(engine.view().toString()).doesNotContain("hidden-password");
        assertThatThrownBy(()->engine.apply(review,true,"test")).isInstanceOf(ConcurrentModificationException.class);
        var expired=new ConfigurationEngine.Review("expired","1",Map.of(),Set.of(),List.of(),false,false,Instant.now().minusSeconds(1));
        assertThatThrownBy(()->engine.apply(expired,true,"test")).hasMessageContaining("expired");
    }
    @Test void removingExternalDatabaseRequiresExplicitChoiceAndNeverSilentlySelectsH2() {
        var store=store();store.noteExternalDatabase("jdbc:postgresql://database/app");
        String h2=ConfigurationCatalog.get("spring.datasource.url").defaultValue();
        assertThat(store.requiresDatabaseChoice(h2)).isTrue();
        var engine=engine(new MockEnvironment(),store);
        assertThat(engine.view()).containsEntry("databaseChoiceRequired",true);
        var review=engine.review("0",Map.of("spring.datasource.url",h2),Set.of());
        assertThat(review.databaseChange()).isTrue();
        engine.apply(review,true,"owner");
        assertThat(store.requiresDatabaseChoice(h2)).isFalse();
        store.noteExternalDatabase("jdbc:postgresql://database/app");
        assertThat(store.requiresDatabaseChoice(h2)).isTrue();
        store.recordDatabase(h2);assertThat(store.requiresDatabaseChoice(h2)).isFalse();
    }
    @Test void dangerousJdbcOptionsMismatchedDriversAndSameSiteDowngradeAreRejected() {
        for(String url:List.of("jdbc:h2:file:./data/test;INIT=RUNSCRIPT FROM 'http://bad'","jdbc:h2:file:../outside","jdbc:h2:mem:test;AUTO_SERVER=TRUE","jdbc:postgresql://user:secret@database/app","jdbc:postgresql://database/app?password=secret","jdbc:unknown://database/app"))
            assertThatThrownBy(()->ConfigurationCatalog.checkJdbc(url)).isInstanceOf(IllegalArgumentException.class);
        var engine=engine(new MockEnvironment(),store());
        assertThatThrownBy(()->engine.review("0",Map.of("spring.datasource.driver-class-name","org.postgresql.Driver"),Set.of())).hasMessageContaining("driver");
        assertThatThrownBy(()->engine.review("0",Map.of("server.servlet.session.cookie.same-site","none"),Set.of())).hasMessageContaining("secure");
        assertThatThrownBy(()->engine.review("0",Map.of("app.webhook.enabled","true","app.webhook.url","http://169.254.169.254/metadata"),Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void startupLoaderResolvesGuiBeforeDataSourceAndExternalConfigBeforeGui() throws Exception {
        var store=store();store.save("0",Map.of("spring.datasource.url","jdbc:postgresql://gui-database/app","spring.datasource.password","encrypted-password"),Set.of(),true,"test");
        var env=ConfigurationBootstrap.environment(new String[]{"--clamav.configuration.file="+store.file(),"--clamav.configuration.key-file="+folder.resolve("center.key")});
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://gui-database/app");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("encrypted-password");
        Path external=folder.resolve("application.properties");Files.writeString(external,"spring.datasource.url=jdbc:postgresql://deployment-database/app\n");
        var overridden=ConfigurationBootstrap.environment(new String[]{"--clamav.configuration.file="+store.file(),"--clamav.configuration.key-file="+folder.resolve("center.key"),"--spring.config.additional-location="+external});
        assertThat(overridden.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://deployment-database/app");
        assertThat(ConfigurationBootstrap.needsSetup(overridden)).isFalse();
    }
    @Test void mirroringGuiTruststoreIntoJsseDoesNotTurnItIntoADeploymentLock() throws Exception {
        String key="javax.net.ssl.trustStore";String original=System.getProperty(key);
        try {
            System.clearProperty(key);
            var store=store();Path trust=folder.resolve("truststore");Files.writeString(trust,"test fixture");
            store.save("0",Map.of(key,trust.toString()),Set.of(),true,"test");
            var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("deployment",Map.of(
                    "clamav.configuration.file",store.file().toString(),"clamav.configuration.key-file",folder.resolve("center.key").toString())));
            ConfigurationSource.install(environment);
            assertThat(System.getProperty(key)).isEqualTo(trust.toString());
            assertThat(ConfigurationSource.override(environment,ConfigurationCatalog.get(key))).isEmpty();
        } finally {if(original==null) System.clearProperty(key);else System.setProperty(key,original);}
    }
    @Test void savingOptionalCredentialsDoesNotEnableFeaturesWhoseSwitchWasLeftOff() {
        var store=store();var engine=engine(new MockEnvironment(),store);
        var reviewed=engine.review("0",Map.of("CLAMAV_REST_URL","https://scanner.example",
                "clamav.metrics.scrape-token","disabled-metrics-token-01234567890123456789",
                "clamav.identity.oidc.issuer","https://issuer.example","clamav.identity.oidc.client-id","client",
                "clamav.identity.oidc.client-secret","private-client-secret"),Set.of());
        engine.apply(reviewed,false,"test");
        for(String flag:List.of("clamav.rest.enabled","clamav.metrics.enabled","clamav.identity.oidc.enabled"))
            assertThat(store.saved(flag)).contains("false");
    }
    @Test void credentialsEmbeddedInDeploymentUrlsAreAlsoMasked() {
        var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://private-user:private-password@db/app?sslpassword=private-tls-password");
        var engine=engine(env,store());
        assertThat(engine.view().toString()).doesNotContain("private-password","private-tls-password","private-user").contains("[private]");
    }
    @Test void stagedWebhookPolicyRetainsTheMostRecentLiveDestinationUntilRestart() {
        var store=store();store.save("0",Map.of("app.webhook.enabled","true","app.webhook.url","https://example.com/first"),Set.of(),true,"test");
        store=store();store.save("1",Map.of("app.webhook.url","https://example.com/latest"),Set.of(),true,"test");
        var engine=engine(new MockEnvironment(),store);
        var reviewed=engine.review("2",Map.of("clamav.webhook.allow-private-addresses","true","app.webhook.url","https://example.com/pending"),Set.of());
        engine.apply(reviewed,false,"test");
        assertThat(store.active("app.webhook.url")).contains("https://example.com/latest");
        assertThat(store.saved("app.webhook.url")).contains("https://example.com/pending");
        assertThat(store().active("app.webhook.url")).contains("https://example.com/pending");
    }
    @Test void disabledOidcDraftsBindCanonicalSettingsWithoutCreatingAnIncompleteSpringRegistration() {
        var initial=store();initial.save("0",Map.of("clamav.identity.oidc.enabled","false",
                "clamav.identity.oidc.scopes","openid,email"),Set.of(),true,"test");
        var source=new ConfigurationSource(store());var env=new MockEnvironment();env.getPropertySources().addFirst(source);
        assertThat(source.getProperty("clamav.identity.oidc.scopes")).isEqualTo("openid,email");
        assertThat(source.getProperty("spring.security.oauth2.client.registration.oidc.scope")).isNull();
        assertThat(source.getPropertyNames()).noneMatch(name->name.startsWith("spring.security.oauth2.") || name.startsWith("SPRING_SECURITY_OAUTH2_"));
        var properties=org.springframework.boot.context.properties.bind.Binder.get(env)
            .bind("clamav.identity",org.springframework.boot.context.properties.bind.Bindable.of(info.trizub.clamav.webclient.identity.IdentityProperties.class)).get();
        assertThat(properties.getOidc().isEnabled()).isFalse();assertThat(properties.getOidc().getScopes()).containsExactly("openid","email");
        var oauth=org.springframework.boot.context.properties.bind.Binder.get(env)
            .bind("spring.security.oauth2.client",org.springframework.boot.context.properties.bind.Bindable.of(
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties.class))
            .orElseGet(org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties::new);
        oauth.afterPropertiesSet();assertThat(oauth.getRegistration()).isEmpty();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE","openid,profile,email")));
        assertThat(ConfigurationSource.override(env,ConfigurationCatalog.get("clamav.identity.oidc.scopes")).orElseThrow().value())
            .isEqualTo("openid,profile,email");
    }

}
