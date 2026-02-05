package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

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
            Map<String, Object> payload = Map.of(
                    "jobId", job.getId(),
                    "type", job.getType().name(),
                    "target", job.getTarget(),
                    "endpoint", job.getEndpoint() != null ? job.getEndpoint().getName() : null,
                    "submittedBy", job.getSubmittedBy(),
                    "verdict", job.getVerdict() != null ? job.getVerdict().name() : null,
                    "foundViruses", job.getFoundVirusesJson(),
                    "error", job.getErrorMessage(),
                    "quarantinePath", job.getQuarantinePath()
            );
            RestClient.create().post().uri(url)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Webhook notification failed: {}", e.getMessage());
        }
    }
}
