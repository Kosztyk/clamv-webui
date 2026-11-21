package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import info.trizub.clamav.webclient.config.WebhookNotificationConfiguration;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class Issue39WebhookPrivacySignatureTest {
  private static final String KEY =
      "private-webhook-signing-secret-abcdefghijklmnopqrstuvwxyz-012345";
  private final ObjectMapper json = new ObjectMapper();

  private ScanJob job() {
    ScanJob job = new ScanJob();
    job.setId("job-39");
    job.setType(ScanJobType.PATH);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setTarget("/app/secret/customer-001.exe");
    job.setQuarantinePath("/mnt/quarantine/private-customer-001.exe");
    job.setErrorMessage("Connection refused by database.internal at /mnt/private");
    job.setSubmittedBy("operator@example.internal");
    job.setFoundVirusesJson("{\"/mnt/secret/customer-001.exe\":[\"Eicar-Test-Signature\"]}");
    return job;
  }

  @Test
  void defaultWebhookHasStructuredFindingsWithoutPathsHostnamesOrExceptions() throws Exception {
    var payload =
        json.valueToTree(
            NotificationService.buildPayload(job(), "https://hooks.example.org/notify"));
    assertThat(payload.path("foundViruses").isObject()).isTrue();
    assertThat(payload.path("foundViruses").path("item-1").get(0).asText())
        .isEqualTo("Eicar-Test-Signature");
    assertThat(payload.toString())
        .doesNotContain(
            "customer-001",
            "/app/",
            "/mnt/",
            "database.internal",
            "operator@example.internal",
            "quarantinePath",
            "target",
            "error");
    var discord =
        json.valueToTree(
            NotificationService.buildPayload(job(), "https://discord.com/api/webhooks/1/token"));
    assertThat(discord.toString()).doesNotContain("customer-001", "database.internal", "/mnt/");
    assertThat(discord.path("allowed_mentions").path("parse").size()).isZero();
  }

  @Test
  void administratorCanOptInToSensitiveFields() throws Exception {
    var payload =
        json.valueToTree(
            NotificationService.buildPayload(
                job(), "https://hooks.example.org/notify", json, true, true, true));
    assertThat(payload.path("target").asText()).contains("customer-001.exe");
    assertThat(payload.path("foundViruses").has("/mnt/secret/customer-001.exe")).isTrue();
    assertThat(payload.path("error").asText()).contains("database.internal");
    assertThat(payload.path("submittedBy").asText()).contains("operator@");
  }

  @Test
  void realHttpReceiverCanVerifyHmacOnExactBody() throws Exception {
    var received = new ArrayBlockingQueue<String[]>(2);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          try (exchange) {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            received.add(
                new String[] {
                  exchange.getRequestHeaders().getFirst("X-Signature"),
                  new String(bytes, StandardCharsets.UTF_8)
                });
            exchange.sendResponseHeaders(204, -1);
          }
        });
    server.start();
    var properties = new WebhookNotificationProperties();
    properties.setAllowPrivateAddresses(true);
    var pool = new WebhookNotificationConfiguration().webhookNotificationExecutor(properties);
    try (var policy = new WebhookAddressPolicy(properties);
        var client = new WebhookDeliveryClient(policy, properties)) {
      SettingsService settings = mock(SettingsService.class);
      when(settings.webhookEnabled()).thenReturn(true);
      when(settings.webhookSigningSecret()).thenReturn(KEY);
      when(settings.webhookUrl())
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
      new NotificationService(settings, json, client, pool).notifyIfNeeded(job());
      String[] entry = received.poll(3, TimeUnit.SECONDS);
      assertThat(entry).isNotNull();
      Mac verifier = Mac.getInstance("HmacSHA256");
      verifier.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] actual = HexFormat.of().parseHex(entry[0].replace("sha256=", ""));
      assertThat(
              MessageDigest.isEqual(
                  actual, verifier.doFinal(entry[1].getBytes(StandardCharsets.UTF_8))))
          .isTrue();
      assertThat(entry[1]).doesNotContain("customer-001.exe", "/mnt/", "database.internal");
    } finally {
      pool.shutdownNow();
      server.stop(0);
    }
  }

  @Test
  void noKeyMeansNoUnsignedDelivery() {
    var client = mock(WebhookDeliveryClient.class);
    var settings = mock(SettingsService.class);
    when(settings.webhookEnabled()).thenReturn(true);
    when(settings.webhookUrl()).thenReturn("https://receiver.example/hooks");
    var pool =
        new WebhookNotificationConfiguration()
            .webhookNotificationExecutor(new WebhookNotificationProperties());
    try {
      new NotificationService(settings, json, client, pool).notifyIfNeeded(job());
      verifyNoInteractions(client);
    } finally {
      pool.shutdownNow();
    }
  }
}
