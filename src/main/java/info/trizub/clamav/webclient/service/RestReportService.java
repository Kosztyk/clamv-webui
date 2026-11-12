package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class RestReportService {
    private final RestApiClient client;
    public RestReportService(RestApiClient client) { this.client = client; }

    public Map<String, Object> report(Map<String, String> filters) {
        Map<String, String> query = new LinkedHashMap<>(filters);
        query.remove("offset"); query.remove("limit");
        String captured = Instant.now().toString();
        if (!query.containsKey("to") || Instant.parse(query.get("to")).isAfter(Instant.parse(captured))) query.put("to", captured);
        Map<String, JsonNode> jobs = new LinkedHashMap<>();
        int offset = 0, total = 0, max = client.config().reportMaxJobs;
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (offset < max) {
            query.put("offset", String.valueOf(offset));
            query.put("limit", String.valueOf(Math.min(100, max - offset)));
            JsonNode page = client.get("history", Map.copyOf(query));
            if (!page.path("jobs").isArray()) throw new RestApiClient.ApiException(502, "Invalid API history response");
            total = page.path("total").asInt();
            for (JsonNode j : page.path("jobs")) jobs.put(j.path("id").asText(), j);
            int count = page.path("jobs").size();
            offset += count;
            if (count == 0 || offset >= total || System.nanoTime() > deadline) break;
        }
        Map<String, Object> report = summarize(new ArrayList<>(jobs.values()));
        report.put("captured_at", captured);
        report.put("matching_retained_jobs", total);
        report.put("included_jobs", jobs.size());
        report.put("truncated", jobs.size() < total);
        report.put("max_jobs", max);
        query.remove("offset"); query.remove("limit");
        report.put("filters", query);
        report.put("scope", "Retained terminal REST API jobs, filtered by creation time. UTC daily buckets. Live history can change during collection; this is not an archival snapshot.");
        report.put("jobs", new ArrayList<>(jobs.values()));
        return report;
    }
    static Map<String, Object> summarize(List<JsonNode> jobs) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, long[]> days = new TreeMap<>();
        Map<String, Long> threats = new TreeMap<>();
        long clean = 0, infected = 0, failed = 0, bytes = 0, files = 0, infectedFiles = 0, failedFiles = 0, duration = 0, timed = 0;
        for (JsonNode j : jobs) {
            String verdict = j.path("verdict").asText("");
            boolean isFailed = "failed".equals(j.path("status").asText());
            String day = j.path("created_at").asText("");
            day = day.length() >= 10 ? day.substring(0, 10) : "unknown";
            long[] counts = days.computeIfAbsent(day, k -> new long[4]);
            counts[0]++;
            if (isFailed) { failed++; counts[3]++; }
            else if ("infected".equals(verdict)) { infected++; counts[2]++; }
            else if ("clean".equals(verdict)) { clean++; counts[1]++; }
            for (JsonNode f : j.path("files")) {
                String status = f.path("status").asText();
                if ("infected".equals(status) || "clean".equals(status)) { files++; bytes += f.path("size").asLong(); }
                if ("infected".equals(status)) infectedFiles++;
                if ("error".equals(status)) failedFiles++;
                Set<String> names = new HashSet<>();
                for (JsonNode v : f.path("result").path("viruses")) names.add(v.asText());
                for (String v : names) threats.merge(v, 1L, Long::sum);
            }
            try {
                long ms = Duration.between(Instant.parse(j.path("started_at").asText()), Instant.parse(j.path("finished_at").asText())).toMillis();
                if (ms >= 0) { duration += ms; timed++; }
            } catch (DateTimeException ignored) { }
        }
        out.put("clean_jobs", clean); out.put("infected_jobs", infected); out.put("failed_jobs", failed);
        out.put("files_scanned", files); out.put("infected_files", infectedFiles); out.put("failed_files", failedFiles);
        out.put("bytes_scanned", bytes); out.put("average_duration_ms", timed == 0 ? null : duration / timed);
        out.put("daily", days.entrySet().stream().map(e -> Map.of("date", e.getKey(), "total", e.getValue()[0], "clean", e.getValue()[1], "infected", e.getValue()[2], "failed", e.getValue()[3])).toList());
        out.put("threats", threats.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Map.of("signature", e.getKey(), "files", e.getValue())).toList());
        return out;
    }
    public static String csv(Map<String, Object> report) {
        StringBuilder csv = new StringBuilder("source,job_id,request_id,created_at,finished_at,status,verdict,filename,bytes,file_status,signatures,error,webhook_status\r\n");
        @SuppressWarnings("unchecked") List<JsonNode> jobs = (List<JsonNode>) report.get("jobs");
        for (JsonNode j : jobs) for (JsonNode f : j.path("files")) {
            List<String> signatures = new ArrayList<>();
            for (JsonNode v : f.path("result").path("viruses")) signatures.add(v.asText());
            List<String> row = List.of("REST API", j.path("id").asText(""), j.path("request_id").asText(""), j.path("created_at").asText(""),
                    j.path("finished_at").asText(""), j.path("status").asText(""), j.path("verdict").asText(""), f.path("name").asText(""),
                    f.path("size").asText(""), f.path("status").asText(""), String.join("; ", signatures), f.path("error").asText(""), j.path("webhook").path("status").asText(""));
            csv.append(row.stream().map(RestReportService::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }
    static String cell(String value) {
        // Prevent spreadsheet formula execution from filenames, signatures and request IDs.
        if (value.stripLeading().matches("(?s)^[=+@-].*") || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
