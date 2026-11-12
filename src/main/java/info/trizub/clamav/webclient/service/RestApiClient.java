package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.config.RestApiConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class RestApiClient {
    public static class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
    private final RestApiConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    public RestApiClient(RestApiConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public RestApiConfig config() { return config; }
    public JsonNode get(String path) { return get(path, Map.of()); }
    public JsonNode get(String path, Map<String, String> query) {
        String suffix = query.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return send(path + (suffix.isEmpty() ? "" : "?" + suffix), "GET", HttpRequest.BodyPublishers.noBody(), null, config.timeout);
    }
    public JsonNode retry(String id) {
        return send("jobs/" + validId(id) + "/webhook/retry", "POST", HttpRequest.BodyPublishers.noBody(), null, config.timeout);
    }
    public JsonNode upload(List<MultipartFile> files, String webhook, String requestId, long maxBytes) {
        if (files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) throw new ApiException(400, "Select at least one non-empty file");
        long total = 0;
        for (MultipartFile f : files) {
            if (f.getSize() > maxBytes - total) throw new ApiException(413, "Upload exceeds the GUI upload limit");
            total += f.getSize();
        }
        if (webhook != null && !webhook.isBlank() && !webhook.matches("[A-Za-z0-9_-]{1,64}")) throw new ApiException(400, "Invalid webhook ID");
        String boundary = "clamav-web-" + UUID.randomUUID();
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        field(parts, boundary, "request_id", requestId);
        if (webhook != null && !webhook.isBlank()) field(parts, boundary, "webhook_id", webhook);
        for (MultipartFile file : files) {
            String name = Objects.toString(file.getOriginalFilename(), "upload.bin").replaceAll("[\\r\\n\\x00-\\x1f\"\\\\]", "_");
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + config.formKey + "\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofInputStream(() -> {
                try { return file.getInputStream(); } catch (IOException e) { throw new UncheckedIOException(e); }
            }));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
        return send("asyncscan", "POST", HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)),
                "multipart/form-data; boundary=" + boundary, config.uploadTimeout);
    }
    private void field(List<HttpRequest.BodyPublisher> parts, String boundary, String name, String value) {
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n"));
    }
    private JsonNode send(String path, String method, HttpRequest.BodyPublisher body, String contentType, Duration timeout) {
        if (!config.enabled()) throw new ApiException(503, "REST API is not configured. Set CLAMAV_REST_URL and recreate the GUI container.");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl + "/" + path))
                .timeout(timeout).header("Accept", "application/json").method(method, body);
        if (!config.apiKey.isBlank()) builder.header("X-API-Key", config.apiKey);
        if (contentType != null) builder.header("Content-Type", contentType);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                String reason = switch (status) {
                    case 401, 403 -> "REST API rejected authentication. Check CLAMAV_REST_API_KEY.";
                    case 404 -> "REST endpoint or job not found. Check the API build and history retention.";
                    case 409 -> "Only failed webhook deliveries can be retried.";
                    case 413 -> "REST API rejected the upload size or number of files.";
                    case 429, 503 -> "REST API is busy or unavailable. Try again later.";
                    case 400 -> "REST API rejected the request. Check filters, files and webhook ID.";
                    default -> "REST API request failed (HTTP " + status + ").";
                };
                throw new ApiException(status >= 400 && status < 500 ? status : 502, reason);
            }
            JsonNode envelope = mapper.readTree(response.body());
            if (envelope == null || !envelope.path("success").asBoolean() || !envelope.hasNonNull("data"))
                throw new ApiException(502, "Unexpected REST API response. Use the job-enabled 1.6.13-kosz.1 build.");
            return envelope.get("data");
        } catch (HttpTimeoutException e) {
            throw new ApiException(504, "REST API request timed out. For uploads, check API jobs before resubmitting.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "REST API request interrupted");
        } catch (IOException e) {
            throw new ApiException(502, "Cannot reach or decode the REST API. Check its URL and container connectivity.");
        }
    }
    public static String validId(String id) {
        if (id == null || !id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new ApiException(400, "Invalid API job ID");
        return id;
    }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
