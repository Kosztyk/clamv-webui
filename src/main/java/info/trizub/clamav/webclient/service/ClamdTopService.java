package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.ClamavClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ClamdTopService {

    private static final Pattern THREADS_PATTERN = Pattern.compile(
            "THREADS:\\s*live\\s+(\\d+)\\s+idle\\s+(\\d+)\\s+max\\s+(\\d+)(?:\\s+idle-timeout\\s+(\\d+))?.*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QUEUE_PATTERN = Pattern.compile(
            "QUEUE:\\s+(\\d+)\\s+items\\s+(\\d+)\\s+max.*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern POOLS_PATTERN = Pattern.compile("POOLS:\\s+(\\d+).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATS_PATTERN = Pattern.compile("STATS\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)\\s+([^\\s]+)");

    private final EndpointService endpoints;
    private final ClamavClientProvider clientProvider;

    public ClamdTopService(EndpointService endpoints, ClamavClientProvider clientProvider) {
        this.endpoints = endpoints;
        this.clientProvider = clientProvider;
    }

    public Map<String, Object> snapshot(Long endpointId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("timestamp", Instant.now().toString());

        ClamdEndpoint ep = endpointId != null ? endpoints.get(endpointId) : endpoints.defaultEndpointOrEnsure();
        if (ep == null) {
            result.put("error", "No clamd endpoints are configured.");
            return result;
        }

        result.put("endpoint", endpointInfo(ep));

        try {
            ClamavClient client = clientProvider.clientFor(ep);
            client.ping();

            String version = safe(client.version());
            String stats = safe(client.stats());

            result.put("ok", true);
            result.put("version", version);
            result.put("versionParsed", parseVersion(version));
            result.put("stats", stats);
            result.put("parsed", parseStats(stats));
            return result;
        } catch (Exception e) {
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return result;
        }
    }

    public Map<String, Object> parseStats(String stats) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        Map<String, Object> threads = new LinkedHashMap<>();
        Map<String, Object> queue = new LinkedHashMap<>();
        Map<String, Object> mem = new LinkedHashMap<>();
        List<Map<String, String>> commands = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();

        if (stats == null || stats.isBlank()) {
            parsed.put("threads", threads);
            parsed.put("queue", queue);
            parsed.put("memory", mem);
            parsed.put("commands", commands);
            parsed.put("unparsed", unparsed);
            return parsed;
        }

        for (String rawLine : stats.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.equalsIgnoreCase("END")) {
                continue;
            }

            Matcher matcher = THREADS_PATTERN.matcher(line);
            if (matcher.matches()) {
                int live = intValue(matcher.group(1));
                int idle = intValue(matcher.group(2));
                int max = intValue(matcher.group(3));
                threads.put("live", live);
                threads.put("idle", idle);
                threads.put("busy", Math.max(0, live - idle));
                threads.put("max", max);
                if (matcher.group(4) != null) {
                    threads.put("idleTimeout", intValue(matcher.group(4)));
                }
                continue;
            }

            matcher = QUEUE_PATTERN.matcher(line);
            if (matcher.matches()) {
                queue.put("items", intValue(matcher.group(1)));
                queue.put("max", intValue(matcher.group(2)));
                continue;
            }

            matcher = POOLS_PATTERN.matcher(line);
            if (matcher.matches()) {
                parsed.put("pools", intValue(matcher.group(1)));
                continue;
            }

            matcher = STATS_PATTERN.matcher(line);
            if (matcher.matches()) {
                parsed.put("statsWindow", matcher.group(1).trim());
                continue;
            }

            if (line.regionMatches(true, 0, "STATE:", 0, 6)) {
                parsed.put("state", line.substring(6).trim());
                continue;
            }

            if (line.regionMatches(true, 0, "MEMSTATS:", 0, 9)) {
                parseKeyValueLine(line.substring(9).trim(), mem);
                continue;
            }

            if (looksLikeCommandLine(line)) {
                commands.add(parseCommandLine(line));
            } else {
                unparsed.add(line);
            }
        }

        parsed.put("threads", threads);
        parsed.put("queue", queue);
        parsed.put("memory", mem);
        parsed.put("commands", commands);
        parsed.put("unparsed", unparsed);
        return parsed;
    }

    private Map<String, String> parseVersion(String version) {
        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.put("raw", safe(version));
        if (version == null || version.isBlank()) {
            return parsed;
        }

        String[] parts = version.split("/", 3);
        if (parts.length > 0) parsed.put("clamav", parts[0].trim());
        if (parts.length > 1) parsed.put("dbVersion", parts[1].trim());
        if (parts.length > 2) parsed.put("dbTime", parts[2].trim());
        return parsed;
    }

    private Map<String, Object> endpointInfo(ClamdEndpoint ep) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("id", ep.getId());
        endpoint.put("name", ep.getName());
        endpoint.put("host", ep.getHost());
        endpoint.put("port", ep.getPort());
        endpoint.put("platform", ep.getPlatform() != null ? ep.getPlatform().name() : "");
        endpoint.put("enabled", ep.isEnabled());
        return endpoint;
    }

    private void parseKeyValueLine(String line, Map<String, Object> target) {
        Matcher matcher = KEY_VALUE_PATTERN.matcher(line);
        while (matcher.find()) {
            target.put(matcher.group(1), matcher.group(2));
        }
    }

    private boolean looksLikeCommandLine(String line) {
        String upper = line.toUpperCase();
        return upper.startsWith("SCAN")
                || upper.startsWith("MULTISCAN")
                || upper.startsWith("MULTISCANFILE")
                || upper.startsWith("INSTREAM")
                || upper.startsWith("CONTSCAN")
                || upper.startsWith("FILDES")
                || upper.startsWith("COMMAND");
    }

    private Map<String, String> parseCommandLine(String line) {
        Map<String, String> command = new LinkedHashMap<>();
        String[] parts = line.trim().split("\\s+", 3);
        command.put("command", parts.length > 0 ? parts[0] : "");
        command.put("queuedSince", parts.length > 1 ? parts[1] : "");
        command.put("file", parts.length > 2 ? parts[2] : "");
        command.put("raw", line);
        return command;
    }

    private int intValue(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
