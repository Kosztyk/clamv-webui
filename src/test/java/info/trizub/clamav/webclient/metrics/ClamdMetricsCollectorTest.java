package info.trizub.clamav.webclient.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.EndpointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClamdMetricsCollectorTest {
    private ClamdEndpoint endpoint() {
        var endpoint = mock(ClamdEndpoint.class);
        when(endpoint.getId()).thenReturn(1L);when(endpoint.getName()).thenReturn("default");when(endpoint.isEnabled()).thenReturn(true);
        return endpoint;
    }

    @Test void exportsCanonicalAndLegacyGaugesFromNativeStats() {
        var registry = new SimpleMeterRegistry();
        try {
            var endpoints = mock(EndpointService.class);var top = mock(ClamdTopService.class);
            var endpoint = endpoint();
            when(endpoints.all()).thenReturn(List.of(endpoint));when(top.snapshot(1L)).thenReturn(ClamdTelemetryFixture.snapshot());
            new ClamdMetricsCollector(registry,endpoints,top,12,30000,"UTC").poll();
            for (String name : List.of("clamav.queue.items","clamav.clamd.queue.items"))
                assertThat(registry.get(name).tag("slot","0").gauge().value()).isZero();
            assertThat(registry.get("clamav.threads.live").tag("slot","0").gauge().value()).isEqualTo(5);
            assertThat(registry.get("clamav.threads.idle").tag("slot","0").gauge().value()).isEqualTo(3);
            assertThat(registry.get("clamav.threads.busy").tag("slot","0").gauge().value()).isEqualTo(2);
            assertThat(registry.get("clamav.threads.max").tag("slot","0").gauge().value()).isEqualTo(10);
            assertThat(registry.get("clamav.pools.used.bytes").tag("slot","0").gauge().value()).isEqualTo(1572864);
            assertThat(registry.get("clamav.pools.total.bytes").tag("slot","0").gauge().value()).isEqualTo(2097152);
            assertThat(registry.get("clamav.database.version").tag("slot","0").gauge().value()).isEqualTo(27920);
            assertThat(registry.get("clamav.database.timestamp").tag("slot","0").gauge().value())
                .isEqualTo(Instant.parse("2026-10-29T10:00:00Z").getEpochSecond());
            assertThat(registry.get("clamav.clamd.queue.max").tag("slot","0").gauge().value()).isNaN();
            assertThat(registry.get("clamav.queue.items").tag("slot","1").gauge().value()).isNaN();
        } finally { registry.close(); }
    }

    @Test void unreachableEndpointClearsEveryNumericMetricInsteadOfReportingOldValues() {
        var registry = new SimpleMeterRegistry();
        try {
            var endpoints = mock(EndpointService.class);var top = mock(ClamdTopService.class);
            var endpoint = endpoint();
            when(endpoints.all()).thenReturn(List.of(endpoint));
            when(top.snapshot(1L)).thenReturn(ClamdTelemetryFixture.snapshot(),Map.of("ok",false));
            var collector = new ClamdMetricsCollector(registry,endpoints,top,12,30000,"UTC");
            collector.poll();collector.poll();
            for (String name : List.of("clamav.queue.items","clamav.threads.live","clamav.threads.idle","clamav.threads.busy",
                "clamav.pools.used.bytes","clamav.pools.total.bytes","clamav.database.version","clamav.database.timestamp","clamav.database.age"))
                assertThat(registry.get(name).tag("slot","0").gauge().value()).as(name).isNaN();
            assertThat(registry.get("clamav.clamd.endpoint.available").tag("slot","0").gauge().value()).isZero();
        } finally { registry.close(); }
    }

    @Test void unavailableMemoryAndInvalidVersionTimeRemainUnknown() {
        var state = new ClamdMetricsCollector.State();
        state.capture(Map.of("live",1),Map.of("items",0),
            Map.of("memory",Map.of("pools_used","N/A","pools_total","invalid")),
            Map.of("dbVersion","27920","dbTime","invalid"),java.time.ZoneOffset.UTC);
        assertThat(state.value("poolsUsedBytes")).isNaN();assertThat(state.value("poolsTotalBytes")).isNaN();
        assertThat(state.value("dbTimestamp")).isNaN();assertThat(state.value("dbAge")).isNaN();
        assertThat(state.value("dbVersion")).isEqualTo(27920);
    }

    @Test void signatureTimestampRespectsTheConfiguredDaemonTimezone() {
        var state = new ClamdMetricsCollector.State();
        state.capture(Map.of(),Map.of(),Map.of(),Map.of("dbTime","Thu Oct 29 10:00:00 2026"),
            java.time.ZoneId.of("Europe/Bucharest"));
        assertThat(state.value("dbTimestamp")).isEqualTo(Instant.parse("2026-10-29T08:00:00Z").getEpochSecond());
    }

    @ParameterizedTest
    @CsvSource({"0,0","512,512","1.500M,1572864","2.000M,2097152","1KiB,1024","0.5G,536870912"})
    void convertsMemoryUnitsUsingClamdBinaryMultipliers(String input,double expected) {
        assertThat(ClamdMetricsCollector.memoryBytes(input)).isEqualTo(expected);
    }

    @Test void rejectsMissingNegativeAndUnparseableMemoryInsteadOfFabricatingZero() {
        for (Object input : new Object[]{null,"N/A","-1M","Infinity","unknown","1.2.3M"})
            assertThat(ClamdMetricsCollector.memoryBytes(input)).isNaN();
    }
}
