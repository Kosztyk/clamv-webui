package info.trizub.clamav.webclient.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.configuration.ConfigurationCatalog;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Regression against unbounded series even with concurrent and 10,000-unique ClamAV signatures. */
class Issue35MetricCardinalityTest {
  @Test
  void tenThousandDistinctSignaturesProduceAtMostTheConfiguredNumberPlusTwoReservedBuckets()
      throws Exception {
    var registry = new SimpleMeterRegistry();
    try {
      var json = new ObjectMapper();
      var metrics = new ScanMetrics(registry, mock(ScanJobRepository.class), json, 30);
      var job = new ScanJob();
      job.setType(ScanJobType.PATH);
      job.setVerdict(ScanVerdict.VIRUS_FOUND);
      var signatures = new ArrayList<String>();
      for (int i = 0; i < 10_000; i++) signatures.add("Test.Dynamic." + i);
      job.setFoundVirusesJson(json.writeValueAsString(Map.of("/private/file.txt", signatures)));
      metrics.recordCompletion(job);
      assertEquals(32, signatureMeters(registry).size(), "N exact + other + unknown");
      assertEquals(
          9_970,
          registry.get("clamav.viruses.detected").tag("signature", "other").counter().count());
      assertEquals(
          0, registry.get("clamav.viruses.detected").tag("signature", "unknown").counter().count());
      assertEquals(
          10_000,
          signatureMeters(registry).stream()
              .mapToDouble(
                  m ->
                      registry
                          .get("clamav.viruses.detected")
                          .tag("signature", m.getId().getTag("signature"))
                          .counter()
                          .count())
              .sum());
      assertEquals(
          1,
          registry
              .get("clamav.scans")
              .tags("type", "path", "verdict", "virus_found")
              .counter()
              .count());
      assertTrue(
          registry.find("clamav.scan.completed").meters().isEmpty(),
          "duplicate completion series removed");
    } finally {
      registry.close();
    }
  }

  @Test
  void parallelJobCompletionCannotExceedTheSignatureLimit() throws Exception {
    var registry = new SimpleMeterRegistry();
    try {
      var metrics =
          new ScanMetrics(registry, mock(ScanJobRepository.class), new ObjectMapper(), 12);
      var workers = Executors.newFixedThreadPool(8);
      try {
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
          final int id = i;
          tasks.add(
              workers.submit(
                  () -> {
                    var job = new ScanJob();
                    job.setType(ScanJobType.UPLOAD);
                    job.setVerdict(ScanVerdict.VIRUS_FOUND);
                    job.setFoundVirusesJson("{\"file\":[\"Concurrent.Signature." + id + "\"]}");
                    metrics.recordCompletion(job);
                  }));
        }
        for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        assertEquals(14, signatureMeters(registry).size());
        assertEquals(
            388,
            registry.get("clamav.viruses.detected").tag("signature", "other").counter().count());
        assertEquals(
            400,
            registry
                .get("clamav.scans")
                .tags("type", "upload", "verdict", "virus_found")
                .counter()
                .count());
      } finally {
        workers.shutdownNow();
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void overlongNamesRouteToOtherAndUnknownIsNotConsumedAsAnExactName() throws Exception {
    var registry = new SimpleMeterRegistry();
    try {
      var metrics = new ScanMetrics(registry, mock(ScanJobRepository.class), new ObjectMapper(), 1);
      var job = new ScanJob();
      job.setType(ScanJobType.WATCH);
      job.setVerdict(ScanVerdict.VIRUS_FOUND);
      job.setFoundVirusesJson("{\"file\":[\"" + "A".repeat(200) + "\",\"Detected.Signature\"]}");
      metrics.recordCompletion(job);
      job.setFoundVirusesJson("not-json");
      metrics.recordCompletion(job);
      assertEquals(3, signatureMeters(registry).size());
      assertEquals(
          1, registry.get("clamav.viruses.detected").tag("signature", "other").counter().count());
      assertEquals(
          1, registry.get("clamav.viruses.detected").tag("signature", "unknown").counter().count());
      assertEquals(
          1,
          registry
              .get("clamav.viruses.detected")
              .tag("signature", "Detected.Signature")
              .counter()
              .count());
    } finally {
      registry.close();
    }
  }

  @Test
  void twentyFourHourTimerRetainsLongRunningSamplesInFiniteBuckets() {
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      var metrics = new ScanMetrics(registry, mock(ScanJobRepository.class), new ObjectMapper(), 2);
      var job = new ScanJob();
      job.setType(ScanJobType.PATH);
      job.setVerdict(ScanVerdict.OK);
      job.setStartedAt(Instant.parse("2026-10-01T00:00:00Z"));
      job.setFinishedAt(job.getStartedAt().plusSeconds(12 * 3600L));
      metrics.recordCompletion(job);
      assertEquals(
          12, registry.get("clamav.scan.duration").tag("type", "path").timer().max(TimeUnit.HOURS));
      String wire = registry.scrape();
      assertTrue(
          wire.lines()
              .anyMatch(
                  line ->
                      line.startsWith("clamav_scan_duration_seconds_bucket{")
                          && line.contains("type=\"path\"")
                          && line.contains("le=\"86400.0\"")),
          "24-hour boundary must be exported as a finite histogram bucket");
      assertFalse(wire.contains("clamav_scan_completed_total"));
    } finally {
      registry.close();
    }
  }

  @Test
  void signatureLimitIsEditableInConfigurationCenterWithSafeBounds() {
    var field = ConfigurationCatalog.get("clamav.metrics.signatures.max-distinct");
    assertNotNull(field);
    assertEquals("100", field.defaultValue());
    assertEquals("restart", field.apply());
    assertEquals("100", ConfigurationCatalog.validate(field, "100"));
    assertThrows(IllegalArgumentException.class, () -> ConfigurationCatalog.validate(field, "0"));
    assertThrows(IllegalArgumentException.class, () -> ConfigurationCatalog.validate(field, "501"));
  }

  private static List<Meter> signatureMeters(SimpleMeterRegistry registry) {
    return registry.getMeters().stream()
        .filter(m -> m.getId().getName().equals("clamav.viruses.detected"))
        .toList();
  }
}
