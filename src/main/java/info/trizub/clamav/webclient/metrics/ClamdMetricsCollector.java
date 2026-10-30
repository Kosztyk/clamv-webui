package info.trizub.clamav.webclient.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.ClamdTopService;
import info.trizub.clamav.webclient.service.EndpointService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Controlled sampling of ClamdTopService, not a scrape-triggered connection to clamd. */
@Component
public class ClamdMetricsCollector {
    public static final int MAX_SLOTS = 12;
    private final EndpointService endpoints;
    private final ClamdTopService top;
    private final State[] slots = new State[MAX_SLOTS];
    private final int maxEndpoints;
    private final long intervalSeconds;
    private final ZoneId databaseZone;

    public ClamdMetricsCollector(MeterRegistry registry, EndpointService endpoints, ClamdTopService top,
            @Value("${clamav.metrics.clamd-max-endpoints:12}") int maxEndpoints,
            @Value("${clamav.metrics.clamd-poll-ms:30000}") long pollMillis,
            @Value("${api.v1.clamd.database-timezone:UTC}") String zone) {
        this.endpoints = endpoints;
        this.top = top;
        this.maxEndpoints = Math.min(MAX_SLOTS, Math.max(1,maxEndpoints));
        this.intervalSeconds = Math.max(1, pollMillis / 1000);
        ZoneId parsed;
        try { parsed = ZoneId.of(zone); } catch (Exception ex) { parsed = ZoneOffset.UTC; }
        databaseZone = parsed;
        for (int i=0; i<MAX_SLOTS; i++) {
            State state = new State(); slots[i] = state;
            String slot = String.valueOf(i);
            gauge(registry, "clamav.clamd.endpoint.configured", state, slot, "configured");
            gauge(registry, "clamav.clamd.endpoint.available", state, slot, "available");
            gauge(registry, "clamav.clamd.endpoint.stale", state, slot, "stale");
            gauge(registry, "clamav.clamd.sample.age", state, slot, "sampleAge");
            gauge(registry, "clamav.clamd.threads.live", state, slot, "live");
            gauge(registry, "clamav.clamd.threads.idle", state, slot, "idle");
            gauge(registry, "clamav.clamd.threads.busy", state, slot, "busy");
            gauge(registry, "clamav.clamd.threads.max", state, slot, "max");
            gauge(registry, "clamav.clamd.queue.items", state, slot, "queue");
            gauge(registry, "clamav.clamd.queue.max", state, slot, "queueMax");
            gauge(registry, "clamav.clamd.pools", state, slot, "pools");
            gauge(registry, "clamav.clamd.database.version", state, slot, "dbVersion");
            gauge(registry, "clamav.clamd.database.age", state, slot, "dbAge");
            gauge(registry, "clamav.clamd.version.major", state, slot, "major");
            gauge(registry, "clamav.clamd.version.minor", state, slot, "minor");
            gauge(registry, "clamav.clamd.version.patch", state, slot, "patch");
            // Exact public contract; existing clamd-prefixed gauges remain compatibility aliases.
            gauge(registry, "clamav.queue.items", state, slot, "queue");
            gauge(registry, "clamav.threads.live", state, slot, "live");
            gauge(registry, "clamav.threads.idle", state, slot, "idle");
            gauge(registry, "clamav.threads.busy", state, slot, "busy");
            gauge(registry, "clamav.threads.max", state, slot, "max");
            gauge(registry, "clamav.pools.used.bytes", state, slot, "poolsUsedBytes");
            gauge(registry, "clamav.pools.total.bytes", state, slot, "poolsTotalBytes");
            gauge(registry, "clamav.database.version", state, slot, "dbVersion");
            gauge(registry, "clamav.database.timestamp", state, slot, "dbTimestamp");
            gauge(registry, "clamav.database.age", state, slot, "dbAge");
        }
    }

    private static void gauge(MeterRegistry registry, String name, State state, String slot, String field) {
        Gauge.builder(name, state, s -> s.value(field)).tag("slot",slot).register(registry);
    }

    @Scheduled(fixedDelayString = "${clamav.metrics.clamd-poll-ms:30000}", initialDelayString = "${clamav.metrics.clamd-initial-delay-ms:5000}")
    public void poll() {
        List<ClamdEndpoint> list;
        try {
            list = endpoints.all().stream().filter(ClamdEndpoint::isEnabled)
                    .sorted(Comparator.comparing(ClamdEndpoint::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(maxEndpoints).toList();
        } catch (Exception e) {
            for (State state : slots) state.unreachable();
            return;
        }
        for (int i=0;i<MAX_SLOTS;i++) {
            State state = slots[i];
            if (i >= list.size()) { state.unconfigured(); continue; }
            ClamdEndpoint endpoint = list.get(i);
            state.configure(endpoint.getName());
            try {
                Map<String,Object> snapshot = top.snapshot(endpoint.getId());
                if (!Boolean.TRUE.equals(snapshot.get("ok"))) { state.unreachable(); state.snapshot = Map.copyOf(snapshot); continue; }
                Map<?,?> parsed = snapshot.get("parsed") instanceof Map<?,?> m ? m : Map.of();
                Map<?,?> threads = parsed.get("threads") instanceof Map<?,?> m ? m : Map.of();
                Map<?,?> queue = parsed.get("queue") instanceof Map<?,?> m ? m : Map.of();
                Map<?,?> version = snapshot.get("versionParsed") instanceof Map<?,?> m ? m : Map.of();
                state.capture(threads, queue, parsed, version, databaseZone);
                state.snapshot = Map.copyOf(snapshot);
            } catch (Exception e) { state.unreachable(); }
        }
    }

    public List<Map<String,Object>> status() {
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i=0;i<maxEndpoints;i++) if (slots[i].configured == 1) {
            State s = slots[i];
            Map<String,Object> snapshot = s.snapshot;
            Map<String,Object> view=new LinkedHashMap<>();
            view.put("slot",i);view.put("endpoint",s.name);view.put("available",s.available==1);
            view.put("lastSuccess",s.lastSuccess==0 ? "Never" : Instant.ofEpochSecond(s.lastSuccess).toString());
            for (String key:List.of("stats","version","error")) view.put(key,snapshot.getOrDefault(key,""));
            view.put("observedAt",snapshot.getOrDefault("timestamp",""));
            view.put("live",display(s.live));view.put("idle",display(s.idle));view.put("busy",display(s.busy));view.put("max",display(s.max));
            double age=s.value("dbAge");
            view.put("queue",display(s.queue));view.put("databaseAgeHours",Double.isFinite(age) ? String.format(Locale.ROOT,"%.1f",age/3600) : "Unknown");
            result.add(view);
        }
        return result;
    }
    private static String display(double value) { return Double.isFinite(value) ? String.valueOf((long)value) : "Unknown"; }

    static final class State {
        volatile String name = "";
        volatile double configured, available;
        volatile double live=Double.NaN, idle=Double.NaN, busy=Double.NaN, max=Double.NaN;
        volatile double queue=Double.NaN, queueMax=Double.NaN, pools=Double.NaN;
        volatile double poolsUsedBytes=Double.NaN, poolsTotalBytes=Double.NaN;
        volatile double dbVersion=Double.NaN, dbTimestamp=Double.NaN;
        volatile double major=Double.NaN, minor=Double.NaN, patch=Double.NaN;
        volatile long lastSuccess;
        volatile Map<String,Object> snapshot = Map.of();
        void configure(String n) { configured=1; name=n == null ? "" : n; }
        void unconfigured() { configured=0; name=""; lastSuccess=0; unreachable(); }
        void unreachable() {
            available=0;
            snapshot=Map.of();
            live=idle=busy=max=queue=queueMax=pools=poolsUsedBytes=poolsTotalBytes=dbVersion=dbTimestamp=major=minor=patch=Double.NaN;
        }
        void capture(Map<?,?> threads, Map<?,?> q, Map<?,?> stats, Map<?,?> version, ZoneId zone) {
            live=number(threads.get("live")); idle=number(threads.get("idle")); busy=number(threads.get("busy"));
            max=number(threads.get("max")); queue=number(q.get("items")); queueMax=number(q.get("max"));
            pools=number(stats.get("pools")); dbVersion=number(version.get("dbVersion"));
            Map<?,?> memory = stats.get("memory") instanceof Map<?,?> m ? m : Map.of();
            poolsUsedBytes=memoryBytes(memory.get("pools_used"));
            poolsTotalBytes=memoryBytes(memory.get("pools_total"));
            String raw = String.valueOf(version.get("clamav"));
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(raw);
            if (matcher.find()) { major=number(matcher.group(1)); minor=number(matcher.group(2)); patch=number(matcher.group(3)); }
            else { major=minor=patch=Double.NaN; }
            dbTimestamp = Double.NaN;
            Object timestamp = version.get("dbTime");
            if (timestamp != null) try {
                DateTimeFormatter format = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);
                LocalDateTime local = LocalDateTime.parse(timestamp.toString().replaceAll("\\s+", " ").trim(), format);
                dbTimestamp = local.atZone(zone).toEpochSecond();
            } catch (DateTimeParseException ignored) { /* no fabricated age */ }
            available=1; lastSuccess=Instant.now().getEpochSecond();
        }
        static double number(Object o) { try { return o == null ? Double.NaN : Double.parseDouble(o.toString()); } catch (Exception e) { return Double.NaN; } }
        double value(String name) {
            return switch(name) {
                case "configured" -> configured;
                case "available" -> available;
                case "stale" -> configured == 0 ? Double.NaN : (available == 0 || lastSuccess == 0 ? 1 : (Instant.now().getEpochSecond() - lastSuccess > 120 ? 1 : 0));
                case "sampleAge" -> lastSuccess == 0 ? Double.NaN : Instant.now().getEpochSecond()-lastSuccess;
                case "live" -> live; case "idle" -> idle; case "busy" -> busy; case "max" -> max;
                case "queue" -> queue; case "queueMax" -> queueMax; case "pools" -> pools;
                case "poolsUsedBytes" -> poolsUsedBytes; case "poolsTotalBytes" -> poolsTotalBytes;
                case "dbVersion" -> dbVersion; case "dbTimestamp" -> dbTimestamp;
                case "dbAge" -> Double.isFinite(dbTimestamp) ? Math.max(0,Instant.now().getEpochSecond()-dbTimestamp) : Double.NaN;
                case "major" -> major; case "minor" -> minor; case "patch" -> patch;
                default -> Double.NaN;
            };
        }
    }

    /** ClamD prints M as bytes / (1024 * 1024), rounded to three decimal places. */
    static double memoryBytes(Object value) {
        if (value == null) return Double.NaN;
        var matcher = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)(?:I?B)?",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.toString().trim());
        if (!matcher.matches()) return Double.NaN;
        double amount = Double.parseDouble(matcher.group(1));
        int power = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "K" -> 1; case "M" -> 2; case "G" -> 3; case "T" -> 4; default -> 0;
        };
        double bytes = amount * Math.pow(1024, power);
        return Double.isFinite(bytes) && bytes <= Long.MAX_VALUE ? Math.round(bytes) : Double.NaN;
    }
}
