package info.trizub.clamav.webclient.metrics;

import info.trizub.clamav.webclient.service.ClamdTopService;
import java.util.Map;

final class ClamdTelemetryFixture {
    static final String STATS = "POOLS: 1\nSTATE: VALID PRIMARY\n" +
        "THREADS: live 5  idle 3 max 10 idle-timeout 30\nQUEUE: 0 items\nSTATS 0.000048\n" +
        "MEMSTATS: heap N/A mmap N/A used N/A free N/A releasable N/A pools 1 pools_used 1.500M pools_total 2.000M\nEND\n";

    static Map<String,Object> snapshot() {
        return Map.of("ok",true,"parsed",new ClamdTopService(null,null).parseStats(STATS),
            "versionParsed",Map.of("clamav","ClamAV 1.5.4","dbVersion","27920","dbTime","Thu Oct 29 10:00:00 2026"));
    }
}
