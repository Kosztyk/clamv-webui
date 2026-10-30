package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringDashboardTest {
    @Test void dashboardContainsRequestedViewsAndKeepsDistinctThreadAndJobSeries() throws Exception {
        String source = Files.readString(Path.of("dashboards/grafana-clamav-webui.json"));
        JsonNode dashboard = new ObjectMapper().readTree(source);
        assertThat(dashboard.path("__inputs").get(0).path("pluginId").asText()).isEqualTo("prometheus");
        assertThat(panel(dashboard,"Verdict distribution").path("type").asText()).isEqualTo("piechart");
        assertThat(panel(dashboard,"Virus detection timeline").path("targets").get(0).path("expr").asText())
            .contains("clamav_scans_total","verdict=\"virus_found\"");
        assertThat(panel(dashboard,"Detected signatures").path("type").asText()).isEqualTo("table");
        assertThat(panel(dashboard,"Detected signatures").path("targets").get(0).path("expr").asText())
            .contains("clamav_viruses_detected_total","signature");
        assertThat(panel(dashboard,"ClamD threads").path("targets").size()).isEqualTo(4);
        assertThat(panel(dashboard,"Pending / running jobs").path("targets").size()).isEqualTo(2);
        assertThat(panel(dashboard,"ClamD queue depth").toString()).contains("clamav_queue_items").doesNotContain("queue_max");
        assertThat(panel(dashboard,"ClamD thread saturation").toString()).contains("clamav_threads_busy","clamav_threads_max");
        assertThat(panel(dashboard,"ClamD pool memory").toString()).contains("clamav_pools_used_bytes","clamav_pools_total_bytes");
        assertThat(panel(dashboard,"Signature database version").toString()).contains("clamav_database_version");
        assertThat(panel(dashboard,"Signature database freshness").toString()).contains("clamav_database_timestamp","thresholds");
        assertThat(source).doesNotContain("clamav_scan_completed_total","clamav_clamd_threads_live or","clamav_scan_jobs_pending or");
    }

    @Test void alertsUseCanonicalTelemetryAndMatchTheFullEndpointIdentity() throws Exception {
        String alerts = Files.readString(Path.of("dashboards/clamav-alerts.yml"));
        assertThat(alerts).contains("on(job,instance,slot)","clamav_database_timestamp","clamav_queue_items > 10",
            "clamav_scans_total{verdict=\"virus_found\"}","clamav_pools_used_bytes","clamav_threads_busy")
            .doesNotContain("on(slot)","clamav_clamd_queue_max","clamav_scan_detections_total");
    }

    private static JsonNode panel(JsonNode dashboard,String title) {
        for (JsonNode panel : dashboard.path("panels")) if (title.equals(panel.path("title").asText())) return panel;
        throw new AssertionError("Missing required Grafana view: " + title);
    }
}
