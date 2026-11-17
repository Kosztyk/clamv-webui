package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Issue39MetricsProjectionTest {
  @Test
  void viewerProjectionCannotIncludeQueuedPathsEndpointHostOrRawErrors() {
    var parsed =
        Map.of(
            "threads",
            Map.of("live", 4, "busy", 3, "idle", 1),
            "queue",
            Map.of("items", 2),
            "commands",
            List.of(Map.of("file", "/mnt/private/bank-statement.pdf")),
            "memory",
            Map.of("heap", "1.3M", "server", "db.internal", "invalid", "/mnt/sensitive"));
    Map<String, Object> snapshot =
        Map.of(
            "ok",
            false,
            "timestamp",
            "2026-11-13T10:00:00Z",
            "stats",
            "SCAN /mnt/private/bank-statement.pdf",
            "parsed",
            parsed,
            "endpoint",
            Map.of("host", "scanner.internal", "port", 3310),
            "error",
            "connect to scanner.internal:3310 failed");
    var sanitized = ClamdMetricsView.from(snapshot, 8L);
    assertThat(sanitized.toString())
        .doesNotContain(
            "bank-statement",
            "scanner.internal",
            "db.internal",
            "3310",
            "SCAN",
            "connect to",
            "server",
            "invalid");
    assertThat(sanitized.get("endpointId")).isEqualTo(8L);
    assertThat(((Map<?, ?>) sanitized.get("queue")).get("items")).isEqualTo(2L);
    assertThat(((Map<?, ?>) sanitized.get("memory")).get("heap")).isEqualTo("1.3M");
    assertThat(ClamdMetricsView.summary(sanitized)).contains("unavailable");
  }

  @Test
  void fabricatedStatsCannotSmuggleTextThroughNumericFields() {
    var parsed =
        Map.of(
            "threads",
            Map.of("live", "/etc/shadow", "busy", -1, "max", Double.NaN),
            "memory",
            Map.of("heap", "1M \u003cscript\u003e", "used", "2M"));
    var sanitized = ClamdMetricsView.from(Map.of("ok", true, "parsed", parsed), 12L);
    assertThat(sanitized.toString()).doesNotContain("/etc/shadow", "script", "NaN");
    assertThat(((Map<?, ?>) sanitized.get("threads"))).isEmpty();
  }
}
