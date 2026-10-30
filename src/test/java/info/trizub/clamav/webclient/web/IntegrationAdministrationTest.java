package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.service.IntegrationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integration-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=IntegrationAdminLocal456789", "clamav.metrics.clamd-initial-delay-ms=3600000",
    "clamav.config.initial-delay-ms=3600000","clamav.metrics.scrape-token=bootstrap-metrics-secret-0123456789"})
@AutoConfigureMockMvc @AutoConfigureMetrics
class IntegrationAdministrationTest {
    static final Path folder;
    static { try { folder=Files.createTempDirectory("integration-admin-"); } catch(Exception ex){throw new ExceptionInInitializerError(ex);} }
    @DynamicPropertySource static void paths(DynamicPropertyRegistry r) {r.add("clamav.integrations.settings-file",()->folder.resolve("integrations.properties").toString());}
    @Autowired MockMvc mvc;
    @Autowired IntegrationSettingsService settings;

    @Test void pagesAndWritesAreAdminOnlyAndRequireCsrf() throws Exception {
        for (String path:List.of("/admin/integrations","/admin/configuration","/admin/integrations/files/testing-guide","/admin/integrations/files/grafana-dashboard")) {
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/integrations/prometheus").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/configuration/settings").with(csrf()).with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/integrations").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void liveScrapeGateCanEnableDisableRotateAndPersistWithoutLeakingSecrets() throws Exception {
        String first="first-private-test-token-0123456789012345",second="second-private-test-token-01234567890123";
        try {
            settings.saveMetrics(true,first);
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+first)).andExpect(status().isOk());
            String html=mvc.perform(get("/admin/integrations").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain(first,settings.scrapeGate().hash());
            mvc.perform(post("/admin/integrations/prometheus").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("enabled","true").param("token",second)).andExpect(status().is3xxRedirection());
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+first)).andExpect(status().isForbidden());
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+second)).andExpect(status().isOk());
            var restored=new IntegrationSettingsService(new MockEnvironment(),folder.resolve("integrations.properties").toString());
            assertThat(restored.scrapeGate()).isEqualTo(settings.scrapeGate());
            assertThat(Files.readString(folder.resolve("integrations.properties"))).doesNotContain(first,second);
            settings.saveMetrics(false,"");
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+second)).andExpect(status().isForbidden());
            mvc.perform(get("/actuator/prometheus/").with(user("admin").roles("ADMIN")).header("Authorization","Bearer "+second)).andExpect(status().isForbidden());
            settings.saveMetrics(true,"");
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+second)).andExpect(status().isOk());
        } finally { settings.resetMetrics(); }
    }
    @Test void generatedTokenIsReturnedOnceAndValidationKeepsPreviousSettings() throws Exception {
        try {
            var response=mvc.perform(post("/admin/integrations/prometheus").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("action","generate")).andExpect(status().is3xxRedirection()).andReturn();
            String secret=(String)response.getFlashMap().get("newScrapeToken");assertThat(secret).hasSize(43);
            mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer "+secret)).andExpect(status().isOk());
            var before=settings.scrapeGate();
            mvc.perform(post("/admin/integrations/prometheus").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("enabled","true").param("token","short")).andExpect(flash().attributeExists("errorMsg"));
            assertThat(settings.scrapeGate()).isEqualTo(before);
            String html=mvc.perform(get("/admin/integrations").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain(secret);
        } finally { settings.resetMetrics(); }
    }
    @Test void observerSettingsCanBePausedAndRestoredWithoutChangingMountPaths() throws Exception {
        try {
            mvc.perform(post("/admin/configuration/settings").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("pollSeconds","5")).andExpect(status().is3xxRedirection());
            assertThat(settings.observationEnabled()).isFalse();
            String html=mvc.perform(get("/admin/configuration").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("What should I watch?","PAUSED","File observation controls").doesNotContain("value=\"secret\"");
            assertThatThrownBy(()->settings.saveObserver(true,true,0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(settings.observationEnabled()).isFalse();
            mvc.perform(post("/admin/configuration/settings").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("action","reset")).andExpect(status().is3xxRedirection());
            assertThat(settings.observationEnabled()).isTrue();
        } finally { settings.resetObserver(); }
    }
    @Test void downloadsArePackagedAndNavigationHasAnIconAndActiveStateForEveryAdminTab() throws Exception {
        for (String name:List.of("grafana-dashboard","alert-rules","testing-guide")) {
            mvc.perform(get("/admin/integrations/files/"+name).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(header().exists("Content-Disposition"));
        }
        String html=mvc.perform(get("/admin/integrations").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        String sidebar=html.substring(html.indexOf("<aside"),html.indexOf("</aside>"));
        var links=java.util.regex.Pattern.compile("<a[^>]*href=\"/admin/[^\"]+\"[^>]*>(.*?)</a>",java.util.regex.Pattern.DOTALL).matcher(sidebar);
        int count=0;while(links.find()) {assertThat(links.group(1)).contains("<i class=\"fas fa-");count++;}
        assertThat(count).isEqualTo(9);
        var selected=java.util.regex.Pattern.compile("<a[^>]*href=\"/admin/integrations\"[^>]*>").matcher(sidebar);
        assertThat(selected.find()).isTrue();assertThat(selected.group()).contains("aria-current=\"page\"");
    }
    @Test void savedObserverModesControlEffectivePolicyAndRecoverFromPause(@TempDir Path dir) throws Exception {
        Path config=dir.resolve("clamd.conf");Files.writeString(config,"TCPSocket 3310\nStreamMaxLength 4\n");
        var live=new IntegrationSettingsService(new MockEnvironment(),dir.resolve("settings.properties").toString());
        var observer=new info.trizub.clamav.webclient.config.ClamdConfigurationObserver(config.toString(),"",true);
        observer.liveSettings(live,30000);assertThat(observer.effective().streamMaxLength()).isEqualTo(4);
        live.saveObserver(true,false,5);observer.refresh();assertThat(observer.effective()).isNull();
        assertThat(observer.clamd().status()).isEqualTo("LOADED");assertThat(observer.pollMillis()).isEqualTo(5000);
        live.saveObserver(true,true,5);observer.refresh();assertThat(observer.effective().streamMaxLength()).isEqualTo(4);
        live.saveObserver(false,true,5);observer.refresh();assertThat(observer.effective()).isNull();assertThat(observer.clamd().status()).isEqualTo("PAUSED");
        Files.writeString(config,"StreamMaxLength invalid\n");live.saveObserver(true,true,5);observer.refresh();
        assertThat(observer.effective()).isNull();assertThat(observer.policyProblem()).isNotBlank();
        Files.writeString(config,"TCPSocket 3310\nStreamMaxLength 8\n");observer.refresh();assertThat(observer.effective().streamMaxLength()).isEqualTo(8);
        var restored=new IntegrationSettingsService(new MockEnvironment(),dir.resolve("settings.properties").toString());
        assertThat(restored.observationEnabled()).isTrue();assertThat(restored.synchronizationEnabled(false)).isTrue();
    }
}
