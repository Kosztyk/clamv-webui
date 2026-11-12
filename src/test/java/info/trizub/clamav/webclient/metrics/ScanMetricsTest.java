package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanMetricsTest {
    @Test void boundedClientDeadlinesAreClassifiedAsTimeoutsWithoutPublishingPrivateDetails() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ScanMetrics(registry, mock(ScanJobRepository.class), new ObjectMapper());
            var job = new ScanJob(); job.setType(ScanJobType.UPLOAD); job.setVerdict(ScanVerdict.ERROR);
            job.setErrorMessage("Path-based upload scan failed for /private/upload. ClamD operation timed out.");
            metrics.recordCompletion(job);
            assertEquals(1, registry.get("clamav.scan.failures").tag("reason", "TIMEOUT").counter().count());
            assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .noneMatch(tag -> tag.getValue().contains("/private")));
        } finally { registry.close(); }
    }
    @Test void recordsBoundedCompletedCounterAndTimer() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics=new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            var job=new ScanJob(); job.setType(ScanJobType.UPLOAD); job.setVerdict(ScanVerdict.OK);
            job.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
            job.setFinishedAt(Instant.parse("2026-01-01T00:00:01Z"));
            metrics.recordCompletion(job);
            assertEquals(1,registry.get("clamav.scan.completed").tags("type","UPLOAD","verdict","OK").counter().count());
            assertEquals(1,registry.get("clamav.scans").tags("type","upload","verdict","ok").counter().count());
            assertEquals(1,registry.get("clamav.scan.duration").tag("type","upload").timer().count());
            assertTrue(registry.getMeters().stream().noneMatch(m -> m.getId().getTags().stream().anyMatch(t -> t.getKey().equals("user")||t.getKey().equals("jobId")||t.getKey().equals("path"))));
        } finally {
            registry.close();
        }
    }
    @Test void limitsFailureReasons() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics=new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            var job=new ScanJob(); job.setType(ScanJobType.PATH); job.setVerdict(ScanVerdict.ERROR);
            job.setErrorMessage("failed to connect to host with sensitive path /secret");
            metrics.recordCompletion(job);
            assertEquals(1, registry.get("clamav.scan.failures").tag("reason","CONNECTION").counter().count());
            assertEquals(1, registry.get("clamav.scans").tags("type","path","verdict","error").counter().count());
        } finally {
            registry.close();
        }
    }

    @Test void initializesAllRequestedLowercaseJobSeriesAtZero() {
        var registry = new SimpleMeterRegistry();
        try {
            new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            for (String type : List.of("upload","path","watch")) {
                for (String verdict : List.of("ok","virus_found","error"))
                    assertEquals(0,registry.get("clamav.scans").tags("type",type,"verdict",verdict).counter().count());
                assertEquals(0,registry.get("clamav.scan.duration").tag("type",type).timer().count());
            }
        } finally { registry.close(); }
    }

    @Test void countsEachFileSignatureOnceWithoutExportingFileNames() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var json = new ObjectMapper();
            var metrics = new ScanMetrics(registry,mock(ScanJobRepository.class),json);
            var job = new ScanJob();job.setType(ScanJobType.WATCH);job.setVerdict(ScanVerdict.VIRUS_FOUND);
            job.setFoundVirusesJson(json.writeValueAsString(Map.of(
                "/private/first.txt",List.of("Win.Test.EICAR_HDB-1","Win.Test.EICAR_HDB-1","Second.Signature"),
                "/private/second.txt",List.of("Win.Test.EICAR_HDB-1"))));
            metrics.recordCompletion(job);
            assertEquals(2,registry.get("clamav.viruses.detected").tag("signature","Win.Test.EICAR_HDB-1").counter().count());
            assertEquals(1,registry.get("clamav.viruses.detected").tag("signature","Second.Signature").counter().count());
            assertEquals(1,registry.get("clamav.scans").tags("type","watch","verdict","virus_found").counter().count());
            assertEquals(1,registry.get("clamav.scan.detections").counter().count());
            assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("/private/")));
        } finally { registry.close(); }
    }

    @Test void missingOrLegacySignatureResultsUseUnknownWithoutBreakingCompletion() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            var job = new ScanJob();job.setType(ScanJobType.PATH);job.setVerdict(ScanVerdict.VIRUS_FOUND);
            for (String result : new String[]{null,"not-json","{}","{\"file\":[null,42,\"\"]}"}) {
                job.setFoundVirusesJson(result);metrics.recordCompletion(job);
            }
            assertEquals(4,registry.get("clamav.viruses.detected").tag("signature","unknown").counter().count());
            assertEquals(4,registry.get("clamav.scans").tags("type","path","verdict","virus_found").counter().count());
        } finally { registry.close(); }
    }

    @Test void cleanAndFailedScansDoNotRecordVirusDetections() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            var job = new ScanJob();job.setType(ScanJobType.UPLOAD);
            for (ScanVerdict verdict : List.of(ScanVerdict.OK,ScanVerdict.ERROR)) {
                job.setVerdict(verdict);metrics.recordCompletion(job);
            }
            assertEquals(0,registry.get("clamav.viruses.detected").tag("signature","unknown").counter().count());
            assertEquals(1,registry.get("clamav.scans").tags("type","upload","verdict","ok").counter().count());
            assertEquals(1,registry.get("clamav.scans").tags("type","upload","verdict","error").counter().count());
        } finally { registry.close(); }
    }

    @Test void durationUsesLowercaseTypeOnlyAndIgnoresMissingOrNegativeIntervals() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ScanMetrics(registry,mock(ScanJobRepository.class),new ObjectMapper());
            var job = new ScanJob();job.setType(ScanJobType.PATH);job.setVerdict(ScanVerdict.OK);
            job.setStartedAt(Instant.parse("2026-10-29T10:00:00Z"));
            job.setFinishedAt(Instant.parse("2026-10-29T10:00:02Z"));metrics.recordCompletion(job);
            job.setVerdict(ScanVerdict.ERROR);metrics.recordCompletion(job);
            job.setFinishedAt(null);metrics.recordCompletion(job);
            job.setFinishedAt(Instant.parse("2026-10-29T09:59:59Z"));metrics.recordCompletion(job);
            var timer = registry.get("clamav.scan.duration").tag("type","path").timer();
            assertEquals(2,timer.count());
            assertEquals(4,timer.totalTime(java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("type"),timer.getId().getTags().stream().map(tag -> tag.getKey()).toList());
        } finally { registry.close(); }
    }
}
