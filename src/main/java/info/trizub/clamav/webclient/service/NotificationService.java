package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int DISCORD_CONTENT_MAX_LENGTH = 2000;

    private final SettingsService settings;
    private final ObjectMapper mapper;

    public NotificationService(SettingsService settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public void notifyIfNeeded(ScanJob job) {
        if (!settings.webhookEnabled()) return;
        String url = settings.webhookUrl();
        if (url == null || url.isBlank()) return;

        try {
            Map<String, Object> payload = buildPayload(job, url);

            RestClient.create().post().uri(url)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Include the exception itself so failures with an empty/null message (for
            // example NullPointerException) still produce a useful stack trace.
            log.warn("Webhook notification failed: {}", e.toString(), e);
        }
    }

    static Map<String, Object> buildPayload(ScanJob job, String url) {
        if (job == null) {
            throw new IllegalArgumentException("Scan job must not be null");
        }

        if (isDiscordWebhook(url)) {
            Map<String, Object> discordPayload = new LinkedHashMap<>();
            discordPayload.put("content", buildDiscordContent(job));
            // Scan targets and error messages may contain user-controlled text. Disable
            // mentions so a filename/path such as "@everyone" cannot create a ping.
            discordPayload.put("allowed_mentions", Map.of("parse", List.of()));
            return discordPayload;
        }

        // Map.of(...) rejects null keys and values. Several ScanJob properties are
        // intentionally nullable depending on the scan outcome, so use a mutable map
        // that can represent those values in the JSON payload.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getId());
        payload.put("type", job.getType() != null ? job.getType().name() : null);
        payload.put("target", job.getTarget());
        payload.put("endpoint", job.getEndpoint() != null ? job.getEndpoint().getName() : null);
        payload.put("submittedBy", job.getSubmittedBy());
        payload.put("verdict", job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN");
        payload.put("foundViruses", job.getFoundVirusesJson());
        payload.put("error", job.getErrorMessage());
        payload.put("quarantinePath", job.getQuarantinePath());
        return payload;
    }

    static boolean isDiscordWebhook(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null) {
                return false;
            }

            host = host.toLowerCase(Locale.ROOT);
            boolean discordHost = host.equals("discord.com")
                    || host.endsWith(".discord.com")
                    || host.equals("discordapp.com")
                    || host.endsWith(".discordapp.com");

            return discordHost && path.startsWith("/api/webhooks/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String buildDiscordContent(ScanJob job) {
        String type = job.getType() != null ? job.getType().name() : "UNKNOWN";
        String verdict = job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN";
        String target = displayValue(job.getTarget(), "unknown");

        StringBuilder content = new StringBuilder();
        content.append("**ClamAV scan ").append(type).append("** - verdict: `")
                .append(escapeInlineCode(verdict)).append("`")
                .append("\nTarget: `").append(escapeInlineCode(target)).append("`");

        if (job.getFoundVirusesJson() != null && !job.getFoundVirusesJson().isBlank()) {
            content.append("\nFound: `")
                    .append(escapeInlineCode(job.getFoundVirusesJson()))
                    .append("`");
        }

        if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
            content.append("\nError: `")
                    .append(escapeInlineCode(job.getErrorMessage()))
                    .append("`");
        }

        if (job.getQuarantinePath() != null && !job.getQuarantinePath().isBlank()) {
            content.append("\nQuarantine: `")
                    .append(escapeInlineCode(job.getQuarantinePath()))
                    .append("`");
        }

        if (job.getId() != null && !job.getId().isBlank()) {
            content.append("\nJob: `")
                    .append(escapeInlineCode(job.getId()))
                    .append("`");
        }

        return truncate(content.toString(), DISCORD_CONTENT_MAX_LENGTH);
    }

    private static String displayValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escapeInlineCode(String value) {
        return value == null ? "" : value.replace("`", "\\`");
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
