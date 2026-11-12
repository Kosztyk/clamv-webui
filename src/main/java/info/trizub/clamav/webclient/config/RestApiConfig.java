package info.trizub.clamav.webclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;

/** Credentials remain in the server environment; never include this bean in a UI model. */
@Component
public class RestApiConfig {
    public final String baseUrl;
    public final String apiKey;
    public final String formKey;
    public final Duration timeout;
    public final Duration uploadTimeout;
    public final int reportMaxJobs;

    public RestApiConfig(@Value("${clamav.rest.url:}") String url,
                         @Value("${clamav.rest.api-key:}") String apiKey,
                         @Value("${clamav.rest.form-key:FILES}") String formKey,
                         @Value("${clamav.rest.timeout-seconds:10}") int timeout,
                         @Value("${clamav.rest.upload-timeout-seconds:3600}") int uploadTimeout,
                         @Value("${clamav.rest.report-max-jobs:5000}") int reportMaxJobs) {
        String normalized = url.trim().replaceAll("/+$", "");
        if (!normalized.isEmpty()) {
            URI uri = URI.create(normalized);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ||
                uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("CLAMAV_REST_URL must be an HTTP(S) URL without credentials, query or fragment");
            }
            if (!normalized.endsWith("/api/v1")) normalized += "/api/v1";
        }
        if (!formKey.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid CLAMAV_REST_FORM_KEY");
        this.baseUrl = normalized;
        this.apiKey = apiKey;
        this.formKey = formKey;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(120, timeout)));
        this.uploadTimeout = Duration.ofSeconds(Math.max(1, uploadTimeout));
        this.reportMaxJobs = Math.max(100, Math.min(10000, reportMaxJobs));
    }
    public boolean enabled() { return !baseUrl.isEmpty(); }
}
