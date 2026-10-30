package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditServiceTest {
    @Test void recordsActorActionOutcomeAndRequestAddress() {
        AuditEventRepository repo = mock(AuditEventRepository.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("User-Agent")).thenReturn("Phase0Test");
        new AuditService(repo).record(new UsernamePasswordAuthenticationToken("admin", "", java.util.List.of()),
            req, "SCAN_UPLOAD", "files=1", "SUCCESS", "job1");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(event.capture());
        assertThat(event.getValue().getUsername()).isEqualTo("admin");
        assertThat(event.getValue().getJobId()).isEqualTo("job1");
        assertThat(event.getValue().getIp()).isEqualTo("127.0.0.1");
        assertThat(event.getValue().getTransport()).isEqualTo("BROWSER");
    }

    @Test void classifiesApiRequestAndNullUriWithoutThrowing() {
        AuditEventRepository repo = mock(AuditEventRepository.class);
        HttpServletRequest req = mock(HttpServletRequest.class);
        AuditService audit = new AuditService(repo);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);

        when(req.getRequestURI()).thenReturn("/api/v1/jobs", (String) null);
        audit.record(null, req, "API_TEST", "test", "SUCCESS", null);
        audit.recordScan(null, req, "SCAN_PATH", "FAILED", 3L, "job-id");

        verify(repo, times(2)).save(event.capture());
        assertThat(event.getAllValues().get(0).getTransport()).isEqualTo("API_BASIC_OR_SESSION");
        assertThat(event.getAllValues().get(1).getTransport()).isEqualTo("BROWSER");
        assertThat(event.getAllValues().get(1).getEndpointId()).isEqualTo(3L);
    }
}
