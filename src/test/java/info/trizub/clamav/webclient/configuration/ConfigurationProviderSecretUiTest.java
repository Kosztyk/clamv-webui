package info.trizub.clamav.webclient.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:provider-secret-ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=ProviderSecretGuardAdminOnlyPassword123",
    "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.configuration.file=target/test-config/provider-secret-ui-${random.uuid}/center.properties"})
@AutoConfigureMockMvc
class ConfigurationProviderSecretUiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ConfigurationCenterService center;
    @MockitoBean ConfigurationConnectionTester tester;
    @BeforeEach void resetState() {
        reset(tester);
        var fields=new LinkedHashMap<String,String>();
        ConfigurationCatalog.defaults().forEach((key,value)->{if(key.startsWith("clamav.identity.")) fields.put(key,value);});
        fields.putAll(ConfigurationProviderSecretTest.baseline());
        center.engine().store().save(center.engine().store().snapshot().revision(),fields,Set.of(),true,"fixture");
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String action,Object body,MockHttpSession session) throws Exception {
        return post("/admin/configuration-center/api/"+action).secure(true).with(user("admin").roles("ADMIN")).with(csrf())
            .session(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }
    Map<String,Object> draft(String provider,Map<String,String> changes) {
        return Map.of("version",center.engine().store().snapshot().revision(),"section",provider,"changes",changes);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void changedDestinationWithBlankSecretCannotTestOrSaveAndMakesNoOutboundCall(String provider) throws Exception {
        String version=center.engine().store().snapshot().revision();
        String target=ConfigurationProviderSecretTest.target(provider),secret=ConfigurationProviderSecretTest.secret(provider);
        var draft=draft(provider,Map.of(target,provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test",secret,""));
        var session=new MockHttpSession();
        for(String action:List.of("test","review")) mvc.perform(request(action,draft,session))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Re-enter")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-"))));
        verifyNoInteractions(tester);
        assertThat(center.engine().store().snapshot().revision()).isEqualTo(version);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void unchangedDestinationCanTestAndSaveWithABlankSecret(String provider) throws Exception {
        String key=ConfigurationProviderSecretTest.secret(provider),original=center.engine().values(true).get(key);
        when(tester.test(eq(provider),anyMap())).thenReturn(Map.of("ok",true,"message","Synthetic test succeeded"));
        var draft=draft(provider,Map.of(key,""));var session=new MockHttpSession();
        mvc.perform(request("test",draft,session)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(tester).test(eq(provider),argThat(values->original.equals(values.get(key))));
        var result=mvc.perform(request("review",draft,session)).andExpect(status().isOk()).andReturn();
        String reviewId=json.readTree(result.getResponse().getContentAsByteArray()).get("reviewId").asText();
        mvc.perform(request("apply",Map.of("reviewId",reviewId,"verificationUsername","admin",
            "verificationPassword","ProviderSecretGuardAdminOnlyPassword123","acknowledgeDatabase",true),session)).andExpect(status().isOk());
        assertThat(center.engine().store().saved(key)).contains(original);
    }
    @ParameterizedTest @ValueSource(strings={"ldap","oidc"})
    void changedDestinationWithExplicitReplacementCanTestAndSaveWithoutEchoingCredentials(String provider) throws Exception {
        String key=ConfigurationProviderSecretTest.secret(provider),target=ConfigurationProviderSecretTest.target(provider);
        String destination=provider.equals("ldap")?"ldaps://changed.example.test":"https://changed.example.test";
        when(tester.test(eq(provider),anyMap())).thenReturn(Map.of("ok",true,"message","Synthetic test succeeded"));
        var draft=draft(provider,Map.of(target,destination,key,"synthetic-new-secret"));var session=new MockHttpSession();
        mvc.perform(request("test",draft,session)).andExpect(status().isOk());
        verify(tester).test(eq(provider),argThat(values->"synthetic-new-secret".equals(values.get(key))));
        var result=mvc.perform(request("review",draft,session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-new-secret")))).andReturn();
        String reviewId=json.readTree(result.getResponse().getContentAsByteArray()).get("reviewId").asText();
        mvc.perform(request("apply",Map.of("reviewId",reviewId,"verificationUsername","admin",
            "verificationPassword","ProviderSecretGuardAdminOnlyPassword123","acknowledgeDatabase",true),session)).andExpect(status().isOk());
        assertThat(center.engine().store().saved(target)).contains(destination);
        assertThat(center.engine().store().saved(key)).contains("synthetic-new-secret");
    }
}
