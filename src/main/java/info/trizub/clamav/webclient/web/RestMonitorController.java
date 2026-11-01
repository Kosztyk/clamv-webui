package info.trizub.clamav.webclient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import info.trizub.clamav.webclient.service.RestApiBridge;
import info.trizub.clamav.webclient.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** The browser never receives external REST API credentials. Spring Security protects each endpoint. */
@Controller
public class RestMonitorController {
    private final RestApiBridge api;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public RestMonitorController(RestApiBridge api, ObjectMapper mapper, AuditService audit) {
        this.api = api; this.mapper = mapper; this.audit = audit;
    }

    @ModelAttribute
    void model(Model model, Authentication auth) {
        if (auth != null) model.addAttribute("username", auth.getName());
    }

    @GetMapping("/api-scan")
    String apiScan(Model model) {
        model.addAttribute("restConfigured", api.configured());
        return "pages/api-scan";
    }

    @GetMapping("/api-reports")
    String apiReports(Model model) {
        model.addAttribute("restConfigured", api.configured());
        return "pages/api-reports";
    }

    @GetMapping(value = "/rest-monitor/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> summary() {
        if (!api.configured()) {
            ObjectNode value = mapper.createObjectNode();
            value.put("configured", false);
            value.put("message", "Configure the external REST scanner in Configuration Center or deployment configuration");
            return secured(value, HttpStatus.OK.value());
        }
        RestApiBridge.ApiResponse response = api.read("summary", Map.of());
        ObjectNode value = mapper.createObjectNode();
        value.put("configured", true); value.set("stats", unpackStats(response.json()));
        return secured(value, response.status());
    }

    @GetMapping(value = "/rest-monitor/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> jobs(@RequestParam Map<String,String> query) {
        return jobListing("jobs", query);
    }

    @GetMapping(value = "/rest-monitor/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> history(@RequestParam Map<String,String> query) {
        return jobListing("history", query);
    }

    @GetMapping(value = "/rest-monitor/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> job(@PathVariable String id) {
        return respond(unpackApiResponse(api.detail(id)));
    }

    @GetMapping(value = "/rest-monitor/scan-history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> scanHistory(@RequestParam Map<String,String> query) {
        return respond(api.read("scan-history", params(query)));
    }

    @GetMapping(value = "/rest-monitor/jobs/{id}/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> progress(@PathVariable String id) {
        return respond(unpackApiResponse(api.progress(id)));
    }

    @GetMapping(value = "/rest-monitor/jobs/{id}/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> webhookStatus(@PathVariable String id) {
        return respond(unpackApiResponse(api.webhookStatus(id)));
    }

    @PostMapping(value = "/rest-monitor/jobs/{id}/webhook/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> retryWebhook(@PathVariable String id, Authentication authentication,
                                                         HttpServletRequest request) {
        RestApiBridge.ApiResponse response = api.retryWebhook(id);
        audit.record(authentication, request, "REST_WEBHOOK_RETRY", "externalJobId=" + id,
                response.status() < 400 ? "SUCCESS" : "FAILED", null);
        return respond(response);
    }

    @GetMapping(value = "/rest-monitor/webhooks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> webhooks() { return respond(unpackApiResponse(api.read("webhooks", Map.of()))); }

    @PostMapping(value = "/rest-monitor/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> upload(@RequestParam("files") List<MultipartFile> files,
                                                    @RequestParam(value = "webhookId", required = false) String webhookId,
                                                    Authentication authentication, HttpServletRequest request) {
        try {
            RestApiBridge.ApiResponse response = api.upload(files, webhookId);
            audit.record(authentication, request, "SCAN_REST_UPLOAD", "files=" + files.size(),
                    response.status() < 400 ? "SUCCESS" : "FAILED", null);
            return respond(unpackApiResponse(response));
        } catch (RuntimeException e) {
            audit.record(authentication, request, "SCAN_REST_UPLOAD", "external API request failed", "FAILED", null);
            throw e;
        }
    }

    @GetMapping(value = "/rest-monitor/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ResponseEntity<JsonNode> analytics(@RequestParam Map<String,String> query) {
        ArrayNode entries = fetchedJobs("history", params(query));
        ObjectNode report = mapper.createObjectNode();
        report.put("inspectedJobs", entries.size());
        int clean = 0, infected = 0, failed = 0, pending = 0;
        TreeMap<String,Integer> byDay = new TreeMap<>();
        for (JsonNode job : entries) {
            String status = value(job, "status").toLowerCase(Locale.ROOT);
            if (status.contains("queue") || status.contains("pend") || status.contains("running")) pending++;
            else if (isFailed(job)) failed++;
            else if (isInfected(job)) infected++;
            else clean++;
            String timestamp = value(job, "created_at", "createdAt", "submittedAt");
            if (timestamp.length() >= 10) byDay.merge(timestamp.substring(0,10), 1, Integer::sum);
        }
        report.put("cleanJobs", clean);report.put("infectedJobs", infected);report.put("failedJobs", failed);report.put("pendingJobs", pending);
        report.put("scope", "Aggregated from the returned external history page (upstream pagination may apply)");
        ObjectNode daily = report.putObject("dailyJobs");
        byDay.forEach(daily::put);
        report.put("truncated", entries.size() >= api.reportMaxJobs());
        return secured(report, 200);
    }

    @GetMapping("/rest-monitor/export")
    @ResponseBody ResponseEntity<byte[]> export(@RequestParam Map<String,String> query,
                                @RequestParam(value="format", defaultValue="csv") String format) {
        ArrayNode jobs = fetchedJobs("history", params(query));
        byte[] bytes;
        String contentType;
        String filename;
        if ("json".equalsIgnoreCase(format)) {
            bytes = jobs.toString().getBytes(StandardCharsets.UTF_8);
            contentType = "application/json";
            filename = "clamav-api-jobs.json";
        } else if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder("id,status,mode,infected,created_at,request_id\r\n");
            for (JsonNode job:jobs) {
                csv.append(csv(value(job,"id","job_id","jobId"))).append(',')
                   .append(csv(value(job,"status"))).append(',')
                   .append(csv(value(job,"mode"))).append(',')
                   .append(isInfected(job)).append(',')
                   .append(csv(value(job,"created_at","createdAt","submittedAt"))).append(',')
                   .append(csv(value(job,"request_id","requestId"))).append("\r\n");
            }
            bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
            contentType = "text/csv;charset=UTF-8"; filename = "clamav-api-jobs.csv";
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected csv or json");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_TYPE, contentType).body(bytes);
    }



    /** Many REST API versions return {success:true,data:{...}} rather than flat results.
     * Unwrap the envelope at the server so all browser pages and tests see one stable shape. */
    static JsonNode payload(JsonNode raw) {
        JsonNode current = raw;
        for (int i = 0; i < 5 && current != null && current.isObject(); i++) {
            JsonNode inner = current.get("data");
            if (inner == null || inner.isNull()) break;
            current = inner;
        }
        return current == null ? raw : current;
    }

    static JsonNode unpackStats(JsonNode raw) {
        JsonNode data = payload(raw);
        if (data != null && data.isObject()) {
            for (String key : List.of("stats", "statistics", "summary")) {
                JsonNode value = data.get(key);
                if (value != null && value.isObject()) return value;
            }
        }
        return data;
    }

    private static RestApiBridge.ApiResponse unpackApiResponse(RestApiBridge.ApiResponse response) {
        return new RestApiBridge.ApiResponse(response.status(), payload(response.json()));
    }

    private ArrayNode fetchedJobs(String kind, Map<String,String> query) {
        // Filters are applied locally to accommodate REST API versions with different
        // query parameter conventions. Never interpret an unknown response as an empty list.
        RestApiBridge.ApiResponse response = api.read(kind, Map.of());
        boolean historyFallback = "history".equals(kind) && response.status() == 404;
        if (historyFallback) response = api.read("jobs", Map.of());
        if (response.status() >= 400) {
            throw new ResponseStatusException(HttpStatus.valueOf(response.status()),
                    "External API returned " + response.status() + " for " + kind);
        }
        ArrayNode records = findJobs(response.json(), 0);
        if (records == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "External API returned an unrecognized jobs/history response; check the API endpoint configuration");
        ArrayNode filtered = mapper.createArrayNode();
        for (JsonNode item: records) {
            if (historyFallback && !isFinished(item)) continue;
            if (matches(item, query)) filtered.add(item);
            if (filtered.size() >= api.reportMaxJobs()) break;
        }
        return filtered;
    }

    static ArrayNode findJobs(JsonNode raw, int depth) {
        if (raw == null || raw.isNull() || depth > 6) return null;
        if (raw.isArray()) return (ArrayNode) raw;
        if (!raw.isObject()) return null;
        for (String key : List.of("jobs", "history", "items", "results", "records", "entries", "scans", "data")) {
            JsonNode nested = raw.get(key);
            if (nested != null) {
                ArrayNode array = findJobs(nested, depth+1);
                if (array != null) return array;
            }
        }
        return null;
    }

    private static boolean isFinished(JsonNode item) {
        String status = value(item, "status", "state").toLowerCase(Locale.ROOT);
        return status.contains("complet") || status.contains("finish") || status.contains("fail")
                || status.contains("error") || status.contains("cancel") || status.contains("done");
    }

    private static boolean matches(JsonNode item, Map<String,String> query) {
        if (!query.getOrDefault("status", "").isBlank() &&
                !query.get("status").equalsIgnoreCase(value(item,"status"))) return false;
        if (!query.getOrDefault("mode", "").isBlank() &&
                !query.get("mode").equalsIgnoreCase(value(item,"mode"))) return false;
        if (!query.getOrDefault("infected", "").isBlank() &&
                Boolean.parseBoolean(query.get("infected")) != isInfected(item)) return false;
        if (!query.getOrDefault("request_id", "").isBlank() &&
                !query.get("request_id").equals(value(item,"request_id","requestId"))) return false;
        String timestamp = value(item,"created_at","createdAt","submittedAt","created","timestamp");
        if (!query.getOrDefault("from", "").isBlank() || !query.getOrDefault("to", "").isBlank()) {
            try {
                Instant created = Instant.parse(timestamp);
                if (query.containsKey("from") && created.isBefore(OffsetDateTime.parse(query.get("from")).toInstant())) return false;
                if (query.containsKey("to") && created.isAfter(OffsetDateTime.parse(query.get("to")).toInstant())) return false;
            } catch (DateTimeParseException e) { return false; }
        }
        return true;
    }

    private ResponseEntity<JsonNode> jobListing(String kind, Map<String,String> query) {
        Map<String,String> filters=params(query);
        ArrayNode all=fetchedJobs(kind, filters);
        int page=Integer.parseInt(filters.getOrDefault("page","1"));
        int size=Integer.parseInt(filters.getOrDefault("page_size","25"));
        long start=((long) page - 1) * size;
        ArrayNode jobs=mapper.createArrayNode();
        for(long i=start;i<all.size() && i<start+size;i++) jobs.add(all.get((int)i));
        ObjectNode result=mapper.createObjectNode();
        result.set("jobs",jobs);
        result.put("total",all.size());
        result.put("page",page);
        result.put("page_size",size);
        result.put("limited",all.size()>=api.reportMaxJobs());
        return secured(result,200);
    }

/** Only documented filters reach the upstream REST server. Invalid input is rejected,
 * not silently discarded. The export format is handled locally and never forwarded. */
static Map<String,String> params(Map<String,String> source) {
    Set<String> allowed = Set.of("page", "page_size", "limit", "offset", "view", "status",
            "mode", "infected", "from", "to", "request_id");
    Map<String,String> result = new LinkedHashMap<>();
    for (var entry : source.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if ("format".equals(key)) continue; // export's local-only parameter
        if (!allowed.contains(key) || value == null || value.length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or oversized API filter");
        if (value.isBlank()) continue;
        switch (key) {
            case "page", "page_size", "limit", "offset" -> {
                if (!value.matches("[0-9]{1,8}"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination value");
                long number = Long.parseLong(value);
                if (number > 100000 || (("page".equals(key) || "page_size".equals(key) || "limit".equals(key)) && number < 1))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination value out of range");
            }
            case "infected" -> {
                if (!"true".equals(value) && !"false".equals(value))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid infected filter");
            }
            case "mode" -> {
                if (!"sync".equals(value) && !"async".equals(value))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scan mode");
            }
            case "view" -> {
                if (!"jobs".equals(value) && !"history".equals(value))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid view");
            }
            case "status", "request_id" -> {
                if (!value.matches("[A-Za-z0-9._:-]{1,128}"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid API filter value");
            }
            case "from", "to" -> {
                try { OffsetDateTime.parse(value); }
                catch (DateTimeParseException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be ISO-8601 with UTC offset");
                }
            }
            default -> { /* All keys are covered above. */ }
        }
        result.put(key, value);
    }
    if (result.containsKey("from") && result.containsKey("to") &&
            OffsetDateTime.parse(result.get("from")).toInstant().isAfter(OffsetDateTime.parse(result.get("to")).toInstant()))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From date must be before To date");
    return result;
}
    static ArrayNode asArray(JsonNode data) {
        if (data.isArray()) return (ArrayNode)data;
        for (String field : List.of("jobs","history","items","results","data"))
            if (data.has(field) && data.get(field).isArray()) return (ArrayNode)data.get(field);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"External API report is missing job records");
    }
    static String value(JsonNode node,String... keys) {
        for(String key:keys) if(node.hasNonNull(key)) return node.path(key).asText("");
        return "";
    }
    static boolean isInfected(JsonNode job) {
        if (job.path("infected").asBoolean(false) || job.path("infectedCount").asInt(0) > 0) return true;
        String verdict = value(job,"verdict","result").toUpperCase(Locale.ROOT);
        return verdict.contains("INFECT") || verdict.contains("VIRUS") || verdict.contains("FOUND");
    }
    static boolean isFailed(JsonNode job) {
        String status=value(job,"status","verdict").toUpperCase(Locale.ROOT);
        return status.contains("ERROR") || status.contains("FAIL");
    }
    static String csv(String value) {
        String sanitized = value.replaceAll("^[=+@\\t\\r\\n-]+", "'"); // mitigate spreadsheet formula injection
        return "\"" + sanitized.replace("\"","\"\"") + "\"";
    }
    private static ResponseEntity<JsonNode> respond(RestApiBridge.ApiResponse response) {return secured(response.json(),response.status());}
    private static ResponseEntity<JsonNode> secured(JsonNode json,int status) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
