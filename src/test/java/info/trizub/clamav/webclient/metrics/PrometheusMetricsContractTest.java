package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.EndpointService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Checks actual Prometheus wire names/labels/values, not just Micrometer's internal IDs. */
class PrometheusMetricsContractTest {
    @Test void exportsEveryRequestedMetricUnderItsExactPublicName() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.throwExceptionOnRegistrationFailure();
        try {
            var json = new ObjectMapper();
            var scans = new ScanMetrics(registry,mock(ScanJobRepository.class),json);
            var job = new ScanJob();job.setId("private-job-id");job.setType(ScanJobType.UPLOAD);
            job.setVerdict(ScanVerdict.VIRUS_FOUND);job.setStartedAt(Instant.parse("2026-10-29T10:00:00Z"));
            job.setFinishedAt(Instant.parse("2026-10-29T10:00:02Z"));
            job.setFoundVirusesJson(json.writeValueAsString(Map.of("/private/scan/file.txt",List.of("Win.Test.EICAR_HDB-1"))));
            scans.recordCompletion(job);
            var endpoint = mock(ClamdEndpoint.class);
            when(endpoint.getId()).thenReturn(1L);when(endpoint.getName()).thenReturn("default");when(endpoint.isEnabled()).thenReturn(true);
            var endpoints = mock(EndpointService.class);var top = mock(ClamdTopService.class);
            when(endpoints.all()).thenReturn(List.of(endpoint));when(top.snapshot(1L)).thenReturn(ClamdTelemetryFixture.snapshot());
            new ClamdMetricsCollector(registry,endpoints,top,12,30000,"UTC").poll();
            String text = registry.scrape();
            assertThat(sample(text,"clamav_scans_total{type=\"upload\",verdict=\"virus_found\"}")).isEqualTo(1);
            assertThat(sample(text,"clamav_scans_total{type=\"watch\",verdict=\"error\"}")).isZero();
            assertThat(sample(text,"clamav_scan_duration_seconds_count{type=\"upload\"}")).isEqualTo(1);
            assertThat(sample(text,"clamav_scan_duration_seconds_sum{type=\"upload\"}")).isEqualTo(2);
            assertThat(text.lines().anyMatch(line -> line.startsWith("clamav_scan_duration_seconds_bucket{")
                && line.contains("type=\"upload\"") && !line.contains("verdict="))).isTrue();
            assertThat(sample(text,"clamav_viruses_detected_total{signature=\"Win.Test.EICAR_HDB-1\"}")).isEqualTo(1);
            assertThat(sample(text,"clamav_queue_items{slot=\"0\"}")).isZero();
            assertThat(sample(text,"clamav_threads_live{slot=\"0\"}")).isEqualTo(5);
            assertThat(sample(text,"clamav_threads_idle{slot=\"0\"}")).isEqualTo(3);
            assertThat(sample(text,"clamav_threads_busy{slot=\"0\"}")).isEqualTo(2);
            assertThat(sample(text,"clamav_pools_used_bytes{slot=\"0\"}")).isEqualTo(1572864);
            assertThat(sample(text,"clamav_pools_total_bytes{slot=\"0\"}")).isEqualTo(2097152);
            assertThat(sample(text,"clamav_database_version{slot=\"0\"}")).isEqualTo(27920);
            assertThat(sample(text,"clamav_database_timestamp{slot=\"0\"}"))
                .isEqualTo(Instant.parse("2026-10-29T10:00:00Z").getEpochSecond());
            assertThat(text).doesNotContain("/private/scan/file.txt","private-job-id");
        } finally { registry.close(); }
    }

    private static double sample(String text,String prefix) {
        String line = text.lines().filter(value -> value.startsWith(prefix + " ")).findFirst()
            .orElseThrow(() -> new AssertionError("Missing Prometheus series: " + prefix));
        return Double.parseDouble(line.substring(prefix.length()+1).split(" ")[0]);
    }
}
