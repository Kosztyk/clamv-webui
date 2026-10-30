package info.trizub.clamav.webclient.metrics;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.service.ClamdTopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:prometheus-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=MetricsContractLocalAdmin456",
    "clamav.metrics.scrape-token=synthetic-metrics-token-0123456789abcdef",
    "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureMetrics
class PrometheusEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired ScanMetrics scans;
    @Autowired ClamdMetricsCollector clamd;
    @MockitoBean ClamdTopService top;

    @BeforeEach void stats() { when(top.snapshot(any())).thenReturn(ClamdTelemetryFixture.snapshot()); }

    @Test void tokenProtectedActuatorRouteReturnsTheRequestedPrometheusContract() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization","Bearer wrong-token"))
            .andExpect(status().isForbidden());
        var job = new ScanJob();job.setType(ScanJobType.PATH);job.setVerdict(ScanVerdict.VIRUS_FOUND);
        job.setStartedAt(Instant.now().minusSeconds(2));job.setFinishedAt(Instant.now());
        job.setFoundVirusesJson("{\"/private/file.txt\":[\"Win.Test.EICAR_HDB-1\"]}");
        scans.recordCompletion(job);clamd.poll();
        String body = mvc.perform(get("/actuator/prometheus")
            .header("Authorization","Bearer synthetic-metrics-token-0123456789abcdef"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("clamav_scans_total{type=\"path\",verdict=\"virus_found\"}",
            "clamav_scan_duration_seconds_bucket", "clamav_viruses_detected_total{signature=\"Win.Test.EICAR_HDB-1\"}",
            "clamav_queue_items", "clamav_threads_live", "clamav_threads_idle", "clamav_threads_busy",
            "clamav_pools_used_bytes", "clamav_pools_total_bytes", "clamav_database_version", "clamav_database_timestamp")
            .doesNotContain("/private/file.txt");
    }
}
