package info.trizub.clamav.webclient.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ClamdTopServiceTest {
    @Test void parsesNativeQueueWithoutInventingCapacity() {
        var stats = new ClamdTopService(null,null).parseStats("QUEUE: 0 items\nEND\0");
        assertThat(((Map<?,?>)stats.get("queue")).get("items")).isEqualTo(0);
        assertThat(((Map<?,?>)stats.get("queue")).containsKey("max")).isFalse();
        assertThat((List<?>)stats.get("unparsed")).isEmpty();
    }

    @Test void parsesNativeMemstatsAndQueueTimings() {
        var stats = new ClamdTopService(null,null).parseStats(
            "POOLS: 1\nTHREADS: live 5  idle 3 max 10 idle-timeout 30\n" +
            "QUEUE: 2 items min 0.1 max 0.2 avg 0.15\n" +
            "MEMSTATS: heap N/A mmap N/A pools 1 pools_used 1.500M pools_total 2.000M\nEND");
        assertThat(((Map<?,?>)stats.get("queue")).get("items")).isEqualTo(2);
        assertThat(((Map<?,?>)stats.get("queue")).containsKey("max")).isFalse();
        assertThat(((Map<?,?>)stats.get("memory")).get("pools_used")).isEqualTo("1.500M");
        assertThat(((Map<?,?>)stats.get("memory")).get("pools_total")).isEqualTo("2.000M");
    }

    @Test void aggregatesNativeMultipleThreadPoolsWithoutFabricatingQueueMaximum() {
        var stats = new ClamdTopService(null,null).parseStats(
            "POOLS: 2\nTHREADS: live 5 idle 3 max 10\nQUEUE: 2 items 100 max\n" +
            "THREADS: live 4 idle 1 max 8\nQUEUE: 3 items\nEND");
        var threads = (Map<?,?>)stats.get("threads");
        assertThat(threads.get("live")).isEqualTo(9);
        assertThat(threads.get("idle")).isEqualTo(4);
        assertThat(threads.get("busy")).isEqualTo(5);
        assertThat(threads.get("max")).isEqualTo(18);
        assertThat(((Map<?,?>)stats.get("queue")).get("items")).isEqualTo(5);
        assertThat(((Map<?,?>)stats.get("queue")).containsKey("max")).isFalse();
    }

    @Test void malformedQueueDoesNotBecomeZero() {
        var stats = new ClamdTopService(null,null).parseStats("QUEUE: unavailable\nEND");
        assertThat((Map<?,?>)stats.get("queue")).isEmpty();
        assertThat((List<?>)stats.get("unparsed")).hasSize(1);
    }

    @Test void parsesThreadAndNativeScanFilePath() {
        ClamdTopService service = new ClamdTopService(null, null);
        Map<String, Object> stats = service.parseStats("THREADS: live 5 idle 3 max 10\n" +
            "QUEUE: 2 items 100 max\n" +
            "MULTISCANFILE 2026-10-28T12:00:00 /app/data/uploads/eicar.com\nEND");
        assertThat(((Map<?, ?>)stats.get("threads")).get("busy")).isEqualTo(2);
        assertThat(((Map<?, ?>)stats.get("queue")).get("items")).isEqualTo(2);
        assertThat((List<?>)stats.get("commands")).hasSize(1);
        assertThat(((Map<?, ?>)((List<?>)stats.get("commands")).get(0)).get("file"))
            .isEqualTo("/app/data/uploads/eicar.com");
    }
}
