package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
 import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Server-side adapter to the external ClamAV REST service. The upstream address and
 * credentials are deployment configuration, never supplied by a browser request.
 * Upstream URL paths are explicit configurable settings, not arbitrary proxy targets.
 */
@Service
public class RestApiBridge {
    public record ApiResponse(int status, JsonNode json) {}

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String authMode;
    private final String formKey;
    private final String webhookField;
    private final int reportMaxJobs;
    private final Duration timeout;
    private final Duration uploadTimeout;
    private final Map<String, String> paths;

    public RestApiBridge(ObjectMapper mapper,
                         @Value("${CLAMAV_REST_URL:}") String baseUrl,
                         @Value("${CLAMAV_REST_API_KEY:}") String apiKey,
                         @Value("${CLAMAV_REST_AUTH_MODE:x-api-key}") String authMode,
                         @Value("${CLAMAV_REST_FORM_KEY:FILES}") String formKey,
                         @Value("${CLAMAV_REST_TIMEOUT_SECONDS:10}") int timeoutSeconds,
                         @Value("${CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS:3600}") int uploadTimeoutSeconds,
                         @Value("${CLAMAV_REST_REPORT_MAX_JOBS:5000}") int reportMaxJobs,
                         @Value("${CLAMAV_REST_STATS_PATH:/api/v1/stats}") String statsPath,
                         @Value("${CLAMAV_REST_JOBS_PATH:/api/v1/jobs}") String jobsPath,
                         @Value("${CLAMAV_REST_HISTORY_PATH:/api/v1/history}") String historyPath,
                         @Value("${CLAMAV_REST_WEBHOOKS_PATH:/api/v1/webhooks}") String webhooksPath,
                         @Value("${CLAMAV_REST_SCAN_PATH:/api/v1/asyncscan}") String scanPath,
                         @Value("${CLAMAV_REST_SCAN_HISTORY_PATH:/api/v1/scan-history}") String scanHistoryPath,
                         @Value("${CLAMAV_REST_WEBHOOK_FIELD:webhookId}") String webhookField) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.authMode = authMode;
        this.formKey = formKey.matches("[A-Za-z][A-Za-z0-9_-]{0,50}") ? formKey : "FILES";
        this.webhookField = webhookField.matches("[A-Za-z][A-Za-z0-9_-]{0,50}") ? webhookField : "webhookId";
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(120, timeoutSeconds)));
        this.uploadTimeout = Duration.ofSeconds(Math.max(1, Math.min(86400, uploadTimeoutSeconds)));
        this.reportMaxJobs = Math.max(1, Math.min(100000, reportMaxJobs));
        this.paths = Map.of("summary", safePath(statsPath), "jobs", safePath(jobsPath),
                "history", safePath(historyPath), "webhooks", safePath(webhooksPath),
                "scan-history", safePath(scanHistoryPath), "upload", safePath(scanPath));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        if (!this.baseUrl.isBlank()) {
            URI uri = URI.create(this.baseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ||
                uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException("CLAMAV_REST_URL must be an http(s) origin or base path without credentials or query");
            }
        }
    }

    private static String safePath(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//") ||
            value.contains("..") || value.contains("?") || value.contains("#") || value.contains(":") || value.contains("\\")) {
            throw new IllegalArgumentException("REST API paths must be absolute server paths without traversal/query");
        }
        return value;
    }

    public boolean configured() { return !baseUrl.isBlank(); }
    public int reportMaxJobs() { return reportMaxJobs; }

    private URI url(String path, Map<String, String> params) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "External ClamAV REST API is not configured");
        StringBuilder builder = new StringBuilder(baseUrl).append(path);
        if (params != null && !params.isEmpty()) {
            builder.append('?'); boolean first = true;
            for (var e : params.entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) continue;
                if (!first) builder.append('&');
                builder.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                builder.append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return URI.create(builder.toString());
    }

    private HttpRequest.Builder headers(URI uri, Duration requestTimeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Accept", "application/json");
        if (!apiKey.isBlank()) {
            if ("bearer".equalsIgnoreCase(authMode)) request.header("Authorization", "Bearer " + apiKey);
            else request.header("X-API-Key", apiKey);
        }
        return request;
    }

    private ApiResponse execute(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External API redirects are not followed");
            }
            JsonNode json;
            try { json = mapper.readTree(response.body()); }
            catch (Exception malformed) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External API returned non-JSON data"); }
            return new ApiResponse(response.statusCode(), json);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cannot reach the configured external REST API");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External API request interrupted");
        }
    }

    public ApiResponse read(String operation, Map<String, String> params) {
        String path = paths.get(operation);
        if (path == null || "upload".equals(operation)) throw new IllegalArgumentException("Unsupported REST operation");
        return execute(headers(url(path, params), timeout).GET().build());
    }

    public ApiResponse detail(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID");
        return execute(headers(url(paths.get("jobs") + "/" + id, Map.of()), timeout).GET().build());
    }


    public ApiResponse progress(String id) {
        validateId(id);
        return execute(headers(url(paths.get("jobs") + "/" + id + "/progress", Map.of()), timeout).GET().build());
    }
    public ApiResponse webhookStatus(String id) {
        validateId(id);
        return execute(headers(url(paths.get("jobs") + "/" + id + "/webhook", Map.of()), timeout).GET().build());
    }
    public ApiResponse retryWebhook(String id) {
        validateId(id);
        return execute(headers(url(paths.get("jobs") + "/" + id + "/webhook/retry", Map.of()), timeout)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID");
    }

    /** Streams multipart bodies to the upstream API; files never pass through the browser-facing API key. */
    public ApiResponse upload(List<MultipartFile> files, String webhookId) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(MultipartFile::isEmpty))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one non-empty file");
        if (files.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many files");
        if (webhookId != null && !webhookId.isBlank() && !webhookId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook ID");
        String boundary = "ClamAVWebUI" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                ArrayList<InputStream> parts = new ArrayList<>();
                if (webhookId != null && !webhookId.isBlank()) {
                    parts.add(new ByteArrayInputStream(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + webhookField + "\"\r\n\r\n" + webhookId + "\r\n").getBytes(StandardCharsets.UTF_8)));
                }
                for (MultipartFile file : files) {
                    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("upload")
                            .replace('\r', '_').replace('\n', '_').replace('"', '_').replace('\\', '_').replace('/', '_');
                    String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + formKey + "\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
                    parts.add(new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8)));
                    parts.add(file.getInputStream());
                    parts.add(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8)));
                }
                parts.add(new ByteArrayInputStream(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
                return new SequenceInputStream(Collections.enumeration(parts));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        HttpRequest request = headers(url(paths.get("upload"), Map.of()), uploadTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build();
        return execute(request);
    }
}
