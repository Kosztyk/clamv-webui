package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.RestTransportPolicy;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials remain in the server environment; never include this bean in a UI model. */
@Component
public class RestApiConfig {
  public final String baseUrl;
  public final String apiKey;
  public final String formKey;
  public final Duration timeout;
  public final Duration uploadTimeout;
  public final int reportMaxJobs;

  @Autowired
  public RestApiConfig(
      @Value("${CLAMAV_REST_URL:}") String url,
      @Value("${CLAMAV_REST_API_KEY:}") String apiKey,
      @Value("${CLAMAV_REST_FORM_KEY:FILES}") String formKey,
      @Value("${CLAMAV_REST_TIMEOUT_SECONDS:10}") int timeout,
      @Value("${CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS:3600}") int uploadTimeout,
      @Value("${CLAMAV_REST_REPORT_MAX_JOBS:5000}") int reportMaxJobs,
      @Value("${CLAMAV_REST_ALLOW_INSECURE:false}") boolean allowInsecure) {
    String normalized = url.trim().replaceAll("/+$", "");
    RestTransportPolicy.validate(normalized, apiKey, allowInsecure);
    if (!normalized.isEmpty()) {
      URI uri = URI.create(normalized);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException(
            "CLAMAV_REST_URL must be an HTTP(S) URL without credentials, query or fragment");
      }
      if (!normalized.endsWith("/api/v1")) normalized += "/api/v1";
    }
    if (!formKey.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid CLAMAV_REST_FORM_KEY");
    this.baseUrl = normalized;
    this.apiKey = apiKey;
    this.formKey = formKey;
    this.timeout = Duration.ofSeconds(Math.max(1, Math.min(120, timeout)));
    this.uploadTimeout = Duration.ofSeconds(Math.max(1, uploadTimeout));
    this.reportMaxJobs = Math.max(100, Math.min(10000, reportMaxJobs));
  }

  /** Compatibility constructor remains fail-closed for existing callers. */
  public RestApiConfig(
      String url,
      String apiKey,
      String formKey,
      int timeout,
      int uploadTimeout,
      int reportMaxJobs) {
    this(url, apiKey, formKey, timeout, uploadTimeout, reportMaxJobs, false);
  }

  public boolean enabled() {
    return !baseUrl.isEmpty();
  }
}
