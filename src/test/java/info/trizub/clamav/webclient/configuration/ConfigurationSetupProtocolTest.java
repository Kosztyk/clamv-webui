package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConfigurationSetupProtocolTest {
    @TempDir Path folder;
    ConfigurationStore store;ConfigurationSetupProtocol setup;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void create() {
        store=new ConfigurationStore(folder.resolve("center.properties"),folder.resolve("key"));
        var env=new MockEnvironment();env.getPropertySources().addLast(new ConfigurationSource(store));
        setup=new ConfigurationSetupProtocol(new ConfigurationEngine(env,store,Map::of),true);
    }
    @AfterEach void close() {setup.close();}
    ConfigurationSetupProtocol.Response request(String path,Object body,boolean authenticated,boolean secure,String origin) throws Exception {
        Map<String,String> headers=new HashMap<>(Map.of("host","app.example:8443","content-type","application/json"));
        if(authenticated) headers.put("authorization","Bearer "+store.recoveryToken());
        if(origin!=null) headers.put("origin",origin);
        return setup.handle(new ConfigurationSetupProtocol.Request("POST",path,headers,json.writeValueAsBytes(body),"127.0.0.1",secure));
    }
    JsonNode body(ConfigurationSetupProtocol.Response response) throws Exception { return json.readTree(response.body()); }
    @Test void noDatabaseOrAccountIsNeededButEveryApiRequiresOwnerCredential() throws Exception {
        assertThat(request("/api/view",Map.of(),false,true,null).status()).isEqualTo(401);
        var login=request("/api/login",Map.of("token",store.recoveryToken()),false,true,null);
        assertThat(login.status()).isEqualTo(200);assertThat(body(login).get("setup").asBoolean()).isTrue();
        assertThat(new String(login.body(),StandardCharsets.UTF_8)).doesNotContain(store.recoveryToken());
        var html=setup.handle(new ConfigurationSetupProtocol.Request("GET","/",Map.of(),new byte[0],"127.0.0.1",false));
        assertThat(html.status()).isEqualTo(200);assertThat(new String(html.body(),StandardCharsets.UTF_8)).contains("Unlock setup &amp; recovery");
    }
    @Test void directHttpTestReportsAnUninitializedH2WithoutCreatingSavingOrStartingIt() throws Exception {
        String filename="./data/setup-probe-"+UUID.randomUUID();String secret="synthetic-setup-probe-password";
        var changes=Map.of("spring.datasource.url","jdbc:h2:file:"+filename,"spring.datasource.password",secret);
        var blocked=request("/api/test",Map.of("version","0","section","database","changes",changes),true,false,"http://app.example:8443");
        assertThat(blocked.status()).isEqualTo(400);
        var result=request("/api/test",Map.of("version","0","section","database","changes",changes,"acknowledgeHttp",true),true,false,"http://app.example:8443");
        assertThat(result.status()).isEqualTo(200);assertThat(result.start()).isFalse();
        assertThat(body(result).get("ok").asBoolean()).isFalse();assertThat(body(result).get("status").asText()).isEqualTo("not_initialized");
        assertThat(body(result).get("message").asText()).contains("not verified","Start application").doesNotContain(secret,filename);
        assertThat(store.snapshot().revision()).isEqualTo("0");assertThat(store.saved("spring.datasource.password")).isEmpty();
        assertThat(java.nio.file.Files.exists(Path.of(filename+".mv.db"))).isFalse();
        assertThat(request("/api/start",Map.of(),true,false,null).status()).isEqualTo(400);
    }
    @Test void crossOriginRequestsAndOversizedBodiesAreRejectedEvenWithValidCredential() throws Exception {
        assertThat(request("/api/view",Map.of(),true,true,"https://attacker.example").status()).isEqualTo(403);
        assertThat(request("/api/view",Map.of(),true,true,"https://app.example:8443").status()).isEqualTo(200);
        var huge=setup.handle(new ConfigurationSetupProtocol.Request("POST","/api/view",Map.of("content-type","application/json"),new byte[65537],"127.0.0.1",true));
        assertThat(huge.status()).isEqualTo(400);
        assertThat(request("/api/view",Map.of(),true,false,"https://app.example:8443").status()).isEqualTo(403);
    }
    @Test void secretSubmissionNeedsHttpsOrExplicitHttpConfirmationAndReviewMasksPassword() throws Exception {
        var draft=Map.of("version","0","changes",Map.of("spring.datasource.password","private-test-password"));
        assertThat(request("/api/review",draft,true,false,null).status()).isEqualTo(400);
        var reviewed=request("/api/review",draft,true,true,null);
        assertThat(reviewed.status()).isEqualTo(200);
        assertThat(new String(reviewed.body(),StandardCharsets.UTF_8)).doesNotContain("private-test-password").contains("[replaced]");
        String id=body(reviewed).get("reviewId").asText();
        assertThat(request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true),true,true,null).status()).isEqualTo(200);
        assertThat(store.saved("spring.datasource.password")).contains("private-test-password");
        assertThat(request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true),true,true,null).status()).isEqualTo(400);
    }
    @Test void databaseAcknowledgementIsRequiredAndStartIsExplicit() throws Exception {
        assertThat(request("/api/start",Map.of(),true,true,null).status()).isEqualTo(400);
        var draft=Map.of("version","0","changes",Map.of("spring.datasource.url","jdbc:postgresql://database/app"));
        String id=body(request("/api/review",draft,true,true,null)).get("reviewId").asText();
        assertThat(request("/api/apply",Map.of("reviewId",id),true,true,null).status()).isEqualTo(400);
        assertThat(store.snapshot().revision()).isEqualTo("0");
        id=body(request("/api/review",draft,true,true,null)).get("reviewId").asText();
        assertThat(request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true),true,true,null).status()).isEqualTo(200);
        var started=request("/api/start",Map.of(),true,true,null);
        assertThat(started.status()).isEqualTo(200);assertThat(started.start()).isTrue();
    }
    @Test void externalDatabaseRemovalCannotBeBypassedWithTheStartButton() throws Exception {
        store.save("0",Map.of(),Set.of(),true,"test");store.noteExternalDatabase("jdbc:postgresql://previous-database/app");
        assertThat(request("/api/start",Map.of(),true,true,null).status()).isEqualTo(400);
        String url=ConfigurationCatalog.get("spring.datasource.url").defaultValue();
        var reviewed=request("/api/review",Map.of("version","1","changes",Map.of("spring.datasource.url",url)),true,true,null);
        String id=body(reviewed).get("reviewId").asText();
        assertThat(request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true),true,true,null).status()).isEqualTo(200);
        assertThat(request("/api/start",Map.of(),true,true,null).start()).isTrue();
    }
    @Test void badCredentialsAreRateLimitedAndAssetsAreAllowlisted() throws Exception {
        for(int i=0;i<8;i++) assertThat(request("/api/login",Map.of("token","wrong"),false,true,null).status()).isEqualTo(401);
        assertThat(request("/api/view",Map.of(),true,true,null).status()).isEqualTo(429);
        var response=setup.handle(new ConfigurationSetupProtocol.Request("GET","/assets/../../conf/configuration-center.key",Map.of(),new byte[0],"remote",true));
        assertThat(response.status()).isEqualTo(404);
    }
    @Test void directHttpSetupCanSaveCredentialsWithOwnerAuthenticationAndExplicitConfirmation() throws Exception {
        var draft=Map.of("version","0","acknowledgeHttp",true,"changes",Map.of("spring.datasource.password","direct-http-private-password"));
        var reviewed=request("/api/review",draft,true,false,"http://app.example:8443");
        assertThat(reviewed.status()).isEqualTo(200);
        assertThat(new String(reviewed.body(),StandardCharsets.UTF_8)).doesNotContain("direct-http-private-password");
        String id=body(reviewed).get("reviewId").asText();
        var saved=request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true,"acknowledgeHttp",true),true,false,"http://app.example:8443");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(store.saved("spring.datasource.password")).contains("direct-http-private-password");
        assertThat(java.nio.file.Files.readString(store.file())).doesNotContain("direct-http-private-password");
        assertThat(request("/api/start",Map.of(),true,false,"http://app.example:8443").start()).isTrue();
    }
    @Test void httpConfirmationDoesNotBypassOwnerAuthenticationOrSameOriginChecks() throws Exception {
        var draft=Map.of("version","0","acknowledgeHttp",true,"changes",Map.of("spring.datasource.password","private-password"));
        assertThat(request("/api/review",draft,false,false,"http://app.example:8443").status()).isEqualTo(401);
        assertThat(request("/api/review",draft,true,false,"http://attacker.example").status()).isEqualTo(403);
        var reviewed=request("/api/review",draft,true,false,"http://app.example:8443");
        String id=body(reviewed).get("reviewId").asText();
        assertThat(request("/api/apply",Map.of("reviewId",id,"acknowledgeDatabase",true),true,false,null).status()).isEqualTo(400);
        assertThat(store.saved("spring.datasource.password")).isEmpty();
    }
    @Test void wrongTokenAndTemporaryLimitHaveDifferentStatusesWithoutEchoingTokens() throws Exception {
        var wrong=request("/api/login",Map.of("token","private-wrong-token"),false,false,null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(body(wrong).get("message").asText()).contains("this application instance").doesNotContain("private-wrong-token");
        for(int i=0;i<7;i++) request("/api/login",Map.of("token","wrong"),false,false,null);
        var limited=request("/api/login",Map.of("token",store.recoveryToken()),false,false,null);
        assertThat(limited.status()).isEqualTo(429);assertThat(body(limited).get("message").asText()).contains("one minute");
    }
}
