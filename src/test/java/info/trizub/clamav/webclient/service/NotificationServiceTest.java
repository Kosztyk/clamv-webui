package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    private ScanJob job(ScanVerdict verdict) {
        ScanJob j = new ScanJob();
        j.setId("job-1");
        j.setType(ScanJobType.UPLOAD);
        j.setTarget("example.txt");
        j.setSubmittedBy("admin");
        j.setVerdict(verdict);
        return j;
    }

    @Test void cleanScanAcceptsNullFields() throws Exception {
        JsonNode payload = json.valueToTree(NotificationService.buildPayload(job(ScanVerdict.OK), "https://example.org/webhook"));
        assertThat(payload.path("verdict").asText()).isEqualTo("OK");
        assertThat(payload.has("error")).isTrue();
        assertThat(payload.get("error").isNull()).isTrue();
        assertThat(payload.get("foundViruses").isNull()).isTrue();
    }

    @Test void infectedScanCarriesSignature() {
        ScanJob infected = job(ScanVerdict.VIRUS_FOUND);
        infected.setFoundVirusesJson("{\"test\":[\"Eicar-Test-Signature\"]}");
        JsonNode payload = json.valueToTree(NotificationService.buildPayload(infected, "https://example.org/webhook"));
        assertThat(payload.path("verdict").asText()).isEqualTo("VIRUS_FOUND");
        assertThat(payload.path("foundViruses").asText()).contains("Eicar-Test-Signature");
    }

    @Test void failedScanCarriesError() {
        ScanJob failed = job(ScanVerdict.ERROR);
        failed.setErrorMessage("daemon unavailable");
        JsonNode payload = json.valueToTree(NotificationService.buildPayload(failed, "https://example.org/webhook"));
        assertThat(payload.path("verdict").asText()).isEqualTo("ERROR");
        assertThat(payload.path("error").asText()).isEqualTo("daemon unavailable");
    }

    @Test void discordPayloadHasContentAndDisablesMentions() {
        ScanJob infected = job(ScanVerdict.VIRUS_FOUND);
        infected.setTarget("@everyone malicious`name.exe");
        infected.setFoundVirusesJson("Eicar-Test-Signature");
        JsonNode payload = json.valueToTree(NotificationService.buildPayload(infected,
                "https://discord.com/api/webhooks/123/secret"));
        assertThat(payload.path("content").asText()).contains("VIRUS_FOUND", "Eicar-Test-Signature");
        assertThat(payload.path("allowed_mentions").path("parse").size()).isZero();
        assertThat(payload.has("jobId")).isFalse();
        assertThat(NotificationService.isDiscordWebhook("https://discord.com.attacker.example/api/webhooks/x")).isFalse();
    }

    @Test void jsonIsPostedOverHttpOnCleanAndErrorJobs() throws Exception {
        var requests = new ArrayBlockingQueue<String>(3);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (exchange) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            SettingsService settings = mock(SettingsService.class);
            when(settings.webhookEnabled()).thenReturn(true);
            when(settings.webhookUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            NotificationService notification = new NotificationService(settings, json);
            notification.notifyIfNeeded(job(ScanVerdict.OK));
            notification.notifyIfNeeded(job(ScanVerdict.ERROR));
            String first = requests.poll(3, TimeUnit.SECONDS), second = requests.poll(3, TimeUnit.SECONDS);
            assertThat(first).contains("\"verdict\":\"OK\"");
            assertThat(second).contains("\"verdict\":\"ERROR\"");
        } finally { server.stop(0); }
    }
}
