package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int DISCORD_CONTENT_MAX_LENGTH = 2000;

    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final WebhookDeliveryClient client;
    private final ExecutorService executor;

    public NotificationService(SettingsService settings, ObjectMapper mapper, WebhookDeliveryClient client,
            @Qualifier("webhookNotificationExecutor") ExecutorService executor) {
        this.settings = settings;
        this.mapper = mapper;
        this.client = client;
        this.executor = executor;
    }

    public void notifyIfNeeded(ScanJob job) {
        try {
            if (!settings.webhookEnabled()) return;
            String url = settings.webhookUrl();
            if (url == null || url.isBlank()) return;
            URI destination = client.validateUrl(url);
            // Snapshot before dispatch: a background thread must never dereference a JPA job/endpoint.
            String body = mapper.writeValueAsString(buildPayload(job, url));
            executor.execute(() -> deliver(destination, body));
        } catch (RejectedExecutionException e) {
            log.warn("Webhook notification dropped: delivery queue is full or shutting down. Scan results are preserved.");
        } catch (Exception e) {
            // HTTP client exceptions may include the token-bearing webhook URL.
            // Do not log exception messages or stack traces containing that secret.
            log.warn("Webhook notification failed ({}). Check receiver and network settings.",
                    e.getClass().getSimpleName());
        }
    }

    private void deliver(URI destination, String body) {
        try {
            client.post(destination, body);
        } catch (Exception e) {
            // URLs, request/response bodies and exception messages may contain credentials or filenames.
            log.warn("Webhook notification failed ({}). Check receiver, address policy and network settings.",
                e.getClass().getSimpleName());
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
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
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
