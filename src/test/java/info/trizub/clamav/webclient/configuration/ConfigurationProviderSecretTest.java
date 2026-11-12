package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ConfigurationProviderSecretTest {
    @TempDir Path folder;
    static Stream<Object[]> destinations() {
        return Stream.of(new Object[]{"ldap","url","ldaps://changed.example.test"},
                new Object[]{"ldap","base-dn","dc=changed"}, new Object[]{"ldap","manager-dn","cn=changed,dc=example"},
                new Object[]{"ldap","start-tls","true"}, new Object[]{"ldap","referrals","follow"},
                new Object[]{"oidc","issuer","https://changed.example.test"}, new Object[]{"oidc","client-id","changed-client"});
    }
    static String secret(String provider) { return "clamav.identity."+provider+"."+(provider.equals("ldap")?"manager-password":"client-secret"); }
    static String target(String provider) { return "clamav.identity."+provider+"."+(provider.equals("ldap")?"url":"issuer"); }
    static Map<String,String> baseline() {
        return Map.of("clamav.identity.ldap.url","ldaps://directory.example.test", "clamav.identity.ldap.base-dn","dc=example",
            "clamav.identity.ldap.manager-dn","cn=reader,dc=example", secret("ldap"),"synthetic-ldap-secret",
            "clamav.identity.oidc.issuer","https://identity.example.test", "clamav.identity.oidc.client-id","original-client",
            secret("oidc"),"synthetic-oidc-secret");
    }
    ConfigurationEngine engine() { return engine(baseline()); }
    ConfigurationEngine engine(Map<String,String> saved) {
        var store=new ConfigurationStore(folder.resolve("center.properties"),folder.resolve("center.key"));
        store.save("0",saved,Set.of(),true,"fixture");
        var env=new MockEnvironment();env.getPropertySources().addLast(new ConfigurationSource(store));
        return new ConfigurationEngine(env,store,Map::of);
    }
    @ParameterizedTest @MethodSource("destinations")
    void destinationChangeCannotReuseSavedSecretEvenWhenProviderIsDisabled(String provider,String field,String value) {
        var engine=engine();String original=engine.store().saved(secret(provider)).orElseThrow();
        assertThatThrownBy(()->engine.review("1",Map.of("clamav.identity."+provider+"."+field,value,secret(provider)," "),Set.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Re-enter").hasMessageNotContaining(original);
        assertThat(engine.store().snapshot().revision()).isEqualTo("1");
        assertThat(engine.store().saved(secret(provider))).contains(original);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void unchangedDestinationRetainsBlankSecretForTestAndSave(String provider) {
        var engine=engine();String original=engine.store().saved(secret(provider)).orElseThrow();
        var review=engine.review("1",Map.of(secret(provider),""),Set.of());
        assertThat(engine.draftValues(review)).containsEntry(secret(provider),original);
        engine.apply(review,true,"operator");
        assertThat(engine.store().saved(secret(provider))).contains(original);
        assertThat(engine.view().toString()).doesNotContain(original);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void explicitlyReenteringTheSameSecretAuthorizesTheChangedDestination(String provider) {
        var engine=engine();String original=engine.store().saved(secret(provider)).orElseThrow();
        String changed=provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test";
        var review=engine.review("1",Map.of(target(provider),changed,secret(provider),original),Set.of());
        assertThat(review.changes()).containsEntry(secret(provider),original);
        assertThat(engine.draftValues(review)).containsEntry(target(provider),changed);
        engine.apply(review,true,"operator");
        assertThat(engine.store().saved(target(provider))).contains(changed);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void neitherForgedReviewNorMountedSecretCanBypassTheGuard(String provider) throws Exception {
        Path mounted=folder.resolve("mounted-secret");Files.writeString(mounted,"synthetic-file-secret");
        var saved=new LinkedHashMap<>(baseline());saved.remove(secret(provider));saved.put(secret(provider)+"-file",mounted.toString());
        var engine=engine(saved);String changed=provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test";
        var changes=Map.of(target(provider),changed);
        var forged=new ConfigurationEngine.Review("forged","1",changes,Set.of(),List.of(),true,false,Instant.now().plusSeconds(60));
        assertThatThrownBy(()->engine.draftValues(forged)).hasMessageContaining("Re-enter");
        assertThatThrownBy(()->engine.apply(forged,true,"operator")).hasMessageContaining("Re-enter");
        assertThatThrownBy(()->engine.review("1",changes,Set.of())).hasMessageContaining("Re-enter");
        assertThat(engine.store().snapshot().revision()).isEqualTo("1");
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void explicitCredentialRemovalAllowsSafeDisabledReconfiguration(String provider) {
        var engine=engine();String changed=provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test";
        var review=engine.review("1",Map.of(target(provider),changed),Set.of(secret(provider)));
        assertThat(engine.draftValues(review).get(secret(provider))).isEmpty();
        engine.apply(review,true,"operator");
        assertThat(engine.values(true).get(secret(provider))).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void recoveryTestRejectsRetargetingBeforeAnyNetworkConnection(String provider) throws Exception {
        var engine=engine();
        try(var socket=new ServerSocket(0);var protocol=new ConfigurationSetupProtocol(engine,true)) {
            socket.setSoTimeout(250);
            String changed=(provider.equals("ldap")?"ldaps":"https")+"://127.0.0.1:"+socket.getLocalPort();
            var headers=Map.of("content-type","application/json","authorization","Bearer "+engine.store().recoveryToken());
            byte[] body=new ObjectMapper().writeValueAsBytes(Map.of("version","1","section",provider,"changes",Map.of(target(provider),changed,secret(provider),"")));
            var reply=protocol.handle(new ConfigurationSetupProtocol.Request("POST","/api/test",headers,body,"127.0.0.1",true));
            assertThat(reply.status()).isEqualTo(400);
            assertThat(new String(reply.body(),java.nio.charset.StandardCharsets.UTF_8)).contains("Re-enter").doesNotContain("synthetic-ldap-secret","synthetic-oidc-secret");
            assertThatThrownBy(socket::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }
}
