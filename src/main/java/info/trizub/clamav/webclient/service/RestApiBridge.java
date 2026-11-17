package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side adapter to the external ClamAV REST service. The upstream address and credentials are
 * deployment configuration, never supplied by a browser request. Upstream URL paths are explicit
 * configurable settings, not arbitrary proxy targets.
 */
@Service
public class RestApiBridge {
  public record ApiResponse(int status, JsonNode json) {}

  // Bound every upstream response, including chunked responses with no Content-Length.
  static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

  private final ObjectMapper mapper;
  private final HttpClient client;
  private final URI baseUri;
  private final String apiKey;
  private final String authMode;
  private final String formKey;
  private final String webhookField;
  private final int reportMaxJobs;
  private final Duration timeout;
  private final Duration uploadTimeout;
  private final Map<String, String> paths;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.core.env.Environment environment;

  @Autowired
  public RestApiBridge(
      ObjectMapper mapper,
      @Value("${CLAMAV_REST_URL:}") String baseUrl,
      @Value("${CLAMAV_REST_API_KEY:}") String apiKey,
      @Value("${CLAMAV_REST_ALLOW_INSECURE:false}") boolean allowInsecure,
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
    String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
    this.apiKey = apiKey == null ? "" : apiKey;
    RestTransportPolicy.validate(normalizedBaseUrl, this.apiKey, allowInsecure);
    this.baseUri = normalizedBaseUrl.isBlank() ? null : URI.create(normalizedBaseUrl);
    this.authMode = authMode;
    this.formKey = formKey.matches("[A-Za-z][A-Za-z0-9_-]{0,50}") ? formKey : "FILES";
    this.webhookField =
        webhookField.matches("[A-Za-z][A-Za-z0-9_-]{0,50}") ? webhookField : "webhookId";
    this.timeout = Duration.ofSeconds(Math.max(1, Math.min(120, timeoutSeconds)));
    this.uploadTimeout = Duration.ofSeconds(Math.max(1, Math.min(86400, uploadTimeoutSeconds)));
    this.reportMaxJobs = Math.max(1, Math.min(100000, reportMaxJobs));
    this.paths =
        Map.of(
            "summary",
            safePath(statsPath),
            "jobs",
            safePath(jobsPath),
            "history",
            safePath(historyPath),
            "webhooks",
            safePath(webhooksPath),
            "scan-history",
            safePath(scanHistoryPath),
            "upload",
            safePath(scanPath));
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    if (baseUri != null
        && (!("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
            || baseUri.getHost() == null
            || baseUri.getUserInfo() != null
            || baseUri.getFragment() != null
            || baseUri.getQuery() != null)) {
      throw new IllegalArgumentException(
          "CLAMAV_REST_URL must be an http(s) origin or base path without credentials or query");
    }
  }

  /** Test/embedding compatibility: never opts in to insecure credential transport. */
  public RestApiBridge(
      ObjectMapper mapper,
      String baseUrl,
      String apiKey,
      String authMode,
      String formKey,
      int timeoutSeconds,
      int uploadTimeoutSeconds,
      int reportMaxJobs,
      String statsPath,
      String jobsPath,
      String historyPath,
      String webhooksPath,
      String scanPath,
      String scanHistoryPath,
      String webhookField) {
    this(
        mapper,
        baseUrl,
        apiKey,
        false,
        authMode,
        formKey,
        timeoutSeconds,
        uploadTimeoutSeconds,
        reportMaxJobs,
        statsPath,
        jobsPath,
        historyPath,
        webhooksPath,
        scanPath,
        scanHistoryPath,
        webhookField);
  }

  private static String safePath(String value) {
    if (value == null
        || !value.startsWith("/")
        || value.startsWith("//")
        || value.contains("..")
        || value.contains("?")
        || value.contains("#")
        || value.contains(":")
        || value.contains("\\")) {
      throw new IllegalArgumentException(
          "REST API paths must be absolute server paths without traversal/query");
    }
    URI.create(value); // Reject spaces and malformed percent escapes before serving requests.
    return value;
  }

  public boolean configured() {
    return baseUri != null
        && (environment == null
            || environment.getProperty("clamav.rest.enabled", Boolean.class, true));
  }

  public int reportMaxJobs() {
    return reportMaxJobs;
  }

  private URI url(String path, Map<String, String> params) {
    if (!configured())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "External ClamAV REST API is not configured");
    StringBuilder builder = new StringBuilder(baseUri.toString()).append(path);
    if (params != null && !params.isEmpty()) {
      builder.append('?');
      boolean first = true;
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
    // Credentials may only be sent to the deployment-configured origin.
    if (uri.getRawUserInfo() != null
        || !baseUri.getScheme().equals(uri.getScheme())
        || baseUri.getPort() != uri.getPort()
        || !baseUri.getHost().equals(uri.getHost())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "External REST API target does not match the configured origin");
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    if (!apiKey.isBlank()) {
      if ("bearer".equalsIgnoreCase(authMode)) request.header("Authorization", "Bearer " + apiKey);
      else request.header("X-API-Key", apiKey);
    }
    return request;
  }

  private ApiResponse execute(HttpRequest request) {
    try {
      // The response stream is bounded before JSON parsing and closed even on failure.
      // BodyHandlers.ofString/ofByteArray may buffer unbounded upstream responses.
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      try (InputStream body = response.body()) {
        if (status < 200 || status >= 300) {
          // Never include upstream error JSON, stack traces, or secrets in a browser response.
          // Preserve 404 for the history -> jobs compatibility fallback.
          return upstreamError(
              status == 404 ? 404 : 502,
              status == 404 ? "Upstream resource not found" : "External REST API request failed");
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > MAX_RESPONSE_BYTES) {
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "External REST API response exceeds 10 MiB limit");
        }
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "External REST API response exceeds 10 MiB limit");
        }
        try {
          JsonNode json = mapper.readTree(bytes);
          if (json == null) throw new IOException("Empty JSON response");
          return new ApiResponse(status, json);
        } catch (Exception malformed) {
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "External REST API returned invalid JSON");
        }
      }
    } catch (ResponseStatusException e) {
      throw e;
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Cannot read the configured external REST API response");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "External API request interrupted");
    }
  }

  private ApiResponse upstreamError(int status, String message) {
    com.fasterxml.jackson.databind.node.ObjectNode value = mapper.createObjectNode();
    value.put("error", status == 404 ? "UPSTREAM_NOT_FOUND" : "UPSTREAM_ERROR");
    value.put("message", message);
    return new ApiResponse(status, value);
  }

  public ApiResponse read(String operation, Map<String, String> params) {
    String path = paths.get(operation);
    if (path == null || "upload".equals(operation))
      throw new IllegalArgumentException("Unsupported REST operation");
    return execute(headers(url(path, params), timeout).GET().build());
  }

  public ApiResponse detail(String id) {
    if (!id.matches("[A-Za-z0-9_-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID");
    return execute(headers(url(paths.get("jobs") + "/" + id, Map.of()), timeout).GET().build());
  }

  public ApiResponse progress(String id) {
    validateId(id);
    return execute(
        headers(url(paths.get("jobs") + "/" + id + "/progress", Map.of()), timeout).GET().build());
  }

  public ApiResponse webhookStatus(String id) {
    validateId(id);
    return execute(
        headers(url(paths.get("jobs") + "/" + id + "/webhook", Map.of()), timeout).GET().build());
  }

  public ApiResponse retryWebhook(String id) {
    validateId(id);
    return execute(
        headers(url(paths.get("jobs") + "/" + id + "/webhook/retry", Map.of()), timeout)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID");
  }

  /**
   * Streams multipart bodies to the upstream API; files never pass through the browser-facing API
   * key.
   */
  public ApiResponse upload(List<MultipartFile> files, String webhookId) {
    if (files == null || files.isEmpty() || files.stream().anyMatch(MultipartFile::isEmpty))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose at least one non-empty file");
    if (files.size() > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many files");
    if (webhookId != null && !webhookId.isBlank() && !webhookId.matches("[A-Za-z0-9_-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook ID");
    String boundary = "ClamAVWebUI" + UUID.randomUUID().toString().replace("-", "");
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.ofInputStream(
            () -> {
              try {
                ArrayList<InputStream> parts = new ArrayList<>();
                if (webhookId != null && !webhookId.isBlank()) {
                  parts.add(
                      new ByteArrayInputStream(
                          ("--"
                                  + boundary
                                  + "\r\nContent-Disposition: form-data; name=\""
                                  + webhookField
                                  + "\"\r\n\r\n"
                                  + webhookId
                                  + "\r\n")
                              .getBytes(StandardCharsets.UTF_8)));
                }
                for (MultipartFile file : files) {
                  String name =
                      Optional.ofNullable(file.getOriginalFilename())
                          .orElse("upload")
                          .replace('\r', '_')
                          .replace('\n', '_')
                          .replace('"', '_')
                          .replace('\\', '_')
                          .replace('/', '_');
                  String header =
                      "--"
                          + boundary
                          + "\r\nContent-Disposition: form-data; name=\""
                          + formKey
                          + "\"; filename=\""
                          + name
                          + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
                  parts.add(new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8)));
                  parts.add(file.getInputStream());
                  parts.add(new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8)));
                }
                parts.add(
                    new ByteArrayInputStream(
                        ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
                return new SequenceInputStream(Collections.enumeration(parts));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    HttpRequest request =
        headers(url(paths.get("upload"), Map.of()), uploadTimeout)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body)
            .build();
    return execute(request);
  }
}
