package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import info.trizub.clamav.webclient.model.ScanJob;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private static final int DISCORD_CONTENT_MAX_LENGTH = 2000;

  private final SettingsService settings;
  private final ObjectMapper mapper;
  private final WebhookDeliveryClient client;
  private final ExecutorService executor;

  public NotificationService(
      SettingsService settings,
      ObjectMapper mapper,
      WebhookDeliveryClient client,
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
      // Existing installations may have an enabled webhook but no signing key yet.
      // Fail closed (no unsigned delivery) until configured in Configuration Center.
      String secret = settings.webhookSigningSecret();
      if (!WebhookSignature.validSecret(secret)) {
        log.warn(
            "Webhook delivery disabled: configure a signing secret of at least 32 bytes in Configuration Center.");
        return;
      }
      URI destination = client.validateUrl(url);
      // Snapshot configuration and JPA fields before dispatch; no background dereferencing.
      String body =
          mapper.writeValueAsString(
              buildPayload(
                  job,
                  url,
                  mapper,
                  settings.webhookIncludePaths(),
                  settings.webhookIncludeErrors(),
                  settings.webhookIncludeIdentity()));
      String signature = WebhookSignature.sign(secret, body);
      executor.execute(() -> deliver(destination, body, signature));
    } catch (RejectedExecutionException e) {
      log.warn(
          "Webhook notification dropped: delivery queue is full or shutting down. Scan results are preserved.");
    } catch (Exception e) {
      // HTTP client exceptions may include the token-bearing webhook URL.
      // Do not log exception messages or stack traces containing that secret.
      log.warn(
          "Webhook notification failed ({}). Check receiver and network settings.",
          e.getClass().getSimpleName());
    }
  }

  private void deliver(URI destination, String body, String signature) {
    try {
      client.post(destination, body, signature);
    } catch (Exception e) {
      // URLs, request/response bodies and exception messages may contain credentials or filenames.
      log.warn(
          "Webhook notification failed ({}). Check receiver, address policy and network settings.",
          e.getClass().getSimpleName());
    }
  }

  /**
   * Default projection never transmits server paths, usernames, endpoint names or exception text.
   */
  static Map<String, Object> buildPayload(ScanJob job, String url) {
    return buildPayload(job, url, new ObjectMapper(), false, false, false);
  }

  static Map<String, Object> buildPayload(
      ScanJob job,
      String url,
      ObjectMapper mapper,
      boolean includePaths,
      boolean includeErrors,
      boolean includeIdentity) {
    if (job == null) throw new IllegalArgumentException("Scan job must not be null");
    Object findings = safeFindings(job.getFoundVirusesJson(), mapper, includePaths);
    if (isDiscordWebhook(url)) {
      Map<String, Object> discord = new LinkedHashMap<>();
      discord.put(
          "content",
          buildDiscordContent(job, findings, includePaths, includeErrors, includeIdentity));
      discord.put("allowed_mentions", Map.of("parse", List.of()));
      return discord;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("jobId", job.getId());
    payload.put("type", job.getType() != null ? job.getType().name() : null);
    payload.put("verdict", job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN");
    payload.put("foundViruses", findings);
    if (includePaths) {
      payload.put("target", job.getTarget());
      payload.put("quarantinePath", job.getQuarantinePath());
    }
    if (includeIdentity) {
      payload.put("endpoint", job.getEndpoint() != null ? job.getEndpoint().getName() : null);
      payload.put("submittedBy", job.getSubmittedBy());
    }
    if (includeErrors) payload.put("error", job.getErrorMessage());
    return payload;
  }

  private static Object safeFindings(String raw, ObjectMapper mapper, boolean includePaths) {
    if (raw == null || raw.isBlank()) return null;
    try {
      JsonNode node = mapper.readTree(raw);
      if (node == null || node.isNull()) return null;
      if (includePaths) return node; // administrator explicitly approved sensitive JSON keys
      ObjectNode sanitized = mapper.createObjectNode();
      if (node.isObject()) {
        int index = 0;
        for (var values = node.fields(); values.hasNext() && index < 1000; ) {
          var item = values.next();
          // Map keys are original scan targets: never use even their basenames.
          ArrayNode signatures = safeSignatures(item.getValue(), mapper);
          if (!signatures.isEmpty()) sanitized.set("item-" + (++index), signatures);
        }
      } else {
        ArrayNode signatures = safeSignatures(node, mapper);
        if (!signatures.isEmpty()) sanitized.set("item-1", signatures);
      }
      return sanitized;
    } catch (JsonProcessingException ignored) {
      // Legacy versions could store a bare virus signature rather than JSON.
      if (raw.matches("[A-Za-z0-9_.!:+-]{1,255}")) {
        ObjectNode sanitized = mapper.createObjectNode();
        ArrayNode list = mapper.createArrayNode();
        list.add(raw);
        sanitized.set("item-1", list);
        return sanitized;
      }
      // Never fall back to serializing an opaque error/path-bearing string.
      return mapper.createObjectNode();
    }
  }

  private static ArrayNode safeSignatures(JsonNode node, ObjectMapper mapper) {
    ArrayNode result = mapper.createArrayNode();
    if (node.isArray()) {
      for (JsonNode value : node) if (result.size() < 1000) addSafeSignature(result, value);
    } else addSafeSignature(result, node);
    return result;
  }

  private static void addSafeSignature(ArrayNode destination, JsonNode item) {
    if (!item.isTextual()) return;
    String signature = item.asText();
    // ClamAV signature identifiers, not arbitrary daemon text, file paths or hostnames.
    if (signature.matches("[A-Za-z0-9_.!:+-]{1,255}")) destination.add(signature);
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
      boolean discordHost =
          host.equals("discord.com")
              || host.endsWith(".discord.com")
              || host.equals("discordapp.com")
              || host.endsWith(".discordapp.com");

      return discordHost && path.startsWith("/api/webhooks/");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String buildDiscordContent(
      ScanJob job,
      Object findings,
      boolean includePaths,
      boolean includeErrors,
      boolean includeIdentity) {
    String type = job.getType() != null ? job.getType().name() : "UNKNOWN";
    String verdict = job.getVerdict() != null ? job.getVerdict().name() : "UNKNOWN";
    StringBuilder content = new StringBuilder();
    content
        .append("**ClamAV scan ")
        .append(type)
        .append("** - verdict: `")
        .append(escapeInlineCode(verdict))
        .append("`");
    if (includePaths) {
      content
          .append("\nTarget: `")
          .append(escapeInlineCode(displayValue(job.getTarget(), "unknown")))
          .append("`");
      if (job.getQuarantinePath() != null && !job.getQuarantinePath().isBlank())
        content
            .append("\nQuarantine: `")
            .append(escapeInlineCode(job.getQuarantinePath()))
            .append("`");
    }
    if (findings != null) {
      String safeText = findings.toString();
      content.append("\nFound: `").append(escapeInlineCode(safeText)).append("`");
    }
    if (includeIdentity) {
      if (job.getEndpoint() != null)
        content
            .append("\nEndpoint: `")
            .append(escapeInlineCode(job.getEndpoint().getName()))
            .append("`");
      if (job.getSubmittedBy() != null)
        content
            .append("\nSubmitted by: `")
            .append(escapeInlineCode(job.getSubmittedBy()))
            .append("`");
    }
    if (includeErrors && job.getErrorMessage() != null && !job.getErrorMessage().isBlank())
      content.append("\nError: `").append(escapeInlineCode(job.getErrorMessage())).append("`");
    if (job.getId() != null && !job.getId().isBlank())
      content.append("\nJob: `").append(escapeInlineCode(job.getId())).append("`");
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
