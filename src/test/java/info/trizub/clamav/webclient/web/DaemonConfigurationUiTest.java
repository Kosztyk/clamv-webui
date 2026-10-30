package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.metrics.ClamdMetricsCollector;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.EndpointService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:daemon-configuration-ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=DaemonConfigLocalRecoverySecret456",
    "clamav.config.initial-delay-ms=3600000", "clamav.metrics.clamd-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class DaemonConfigurationUiTest {
    static final Path directory;
    static {
        try {
            directory=Files.createTempDirectory("daemon-config-ui");
            Files.writeString(directory.resolve("clamd.conf"),"TCPAddr 127.0.0.1\nTCPSocket 3310\nStreamMaxLength 4\nExcludePath \\.skip$\n");
            Files.writeString(directory.resolve("freshclam.conf"),"Checks 12\nHTTPProxyPassword NEVER_RENDER_THIS_SECRET\n");
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        // Exercise the exact public aliases and default-enabled synchronization.
        registry.add("CLAMAV_CONFIG_PATH",() -> directory.resolve("clamd.conf").toString());
        registry.add("FRESHCLAM_CONFIG_PATH",() -> directory.resolve("freshclam.conf").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired EndpointService endpoints;
    @Autowired ClamdEndpointRepository repository;
    @Autowired ClamdMetricsCollector metrics;
    @MockitoBean ClamdTopService top;

    @AfterAll static void cleanup() throws Exception {
        try (var paths=Files.walk(directory)) {
            for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test void mountedConfigurationSynchronizesPersistenceAndRendersStatsWhileProtectingAdminAccess() throws Exception {
        mvc.perform(get("/admin/configuration").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/configuration").with(user("operator").roles("OPERATOR"))).andExpect(status().isForbidden());
        String stats="POOLS: 1\nTHREADS: live 5 idle 3 max 10\nQUEUE: 0 items\nEND\n";
        when(top.snapshot(any())).thenReturn(Map.of("ok",true,"timestamp","2026-10-30T00:00:00Z",
            "version","ClamAV 1.5.4/27920/date","stats",stats,"parsed",new ClamdTopService(null,null).parseStats(stats)));
        endpoints.pollConfiguration();metrics.poll();
        mvc.perform(get("/admin/configuration").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Effective daemon scan policy")))
            .andExpect(content().string(containsString("THREADS: live 5 idle 3 max 10")))
            .andExpect(content().string(containsString("QUEUE: 0 items")))
            .andExpect(content().string(containsString("[REDACTED]")))
            .andExpect(content().string(not(containsString("NEVER_RENDER_THIS_SECRET"))))
            .andExpect(content().string(containsString("configuration-observer.js")));
        var endpoint=repository.findByName("default").orElseThrow();
        mvc.perform(multipart("/api/v1/scans/uploads").file(new MockMultipartFile("files","large.txt","text/plain",new byte[5]))
            .param("endpointId",endpoint.getId().toString()).with(csrf()).with(user("operator").roles("OPERATOR")))
            .andExpect(status().isBadRequest());
        Files.writeString(directory.resolve("clamd.conf"),"TCPAddr 192.0.2.20\nTCPSocket 3312\nStreamMaxLength 2K\n");
        Files.writeString(directory.resolve("freshclam.conf"),"Checks 24\nHTTPProxyPassword NEVER_RENDER_THIS_SECRET\n");
        endpoints.pollConfiguration();
        var changed=repository.findByName("default").orElseThrow();
        assertThat(changed.getId()).isEqualTo(endpoint.getId());assertThat(changed.getHost()).isEqualTo("192.0.2.20");
        assertThat(changed.getPort()).isEqualTo(3312);
        mvc.perform(get("/admin/configuration").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("192.0.2.20:3312")))
            .andExpect(content().string(containsString("2048"))).andExpect(content().string(containsString("24")));
        Files.writeString(directory.resolve("clamd.conf"),"StreamMaxLength invalid\n");endpoints.pollConfiguration();
        mvc.perform(get("/admin/configuration").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Using the last valid configuration.")))
            .andExpect(content().string(containsString("2048")));
        when(top.snapshot(any())).thenReturn(Map.of("ok",false,"error","Synthetic daemon unavailable"));metrics.poll();
        mvc.perform(get("/admin/configuration").with(user("administrator").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Synthetic daemon unavailable")))
            .andExpect(content().string(not(containsString("THREADS: live 5 idle 3 max 10"))));
    }
}
