package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuditServiceTest {

  @Test
  void recordsActorActionOutcomeAndRequestAddress() {
    AuditEventWriter writer = mock(AuditEventWriter.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn("127.0.0.1");
    when(req.getHeader("User-Agent")).thenReturn("Phase0Test");
    new AuditService(writer)
        .record(
            new UsernamePasswordAuthenticationToken("admin", ""),
            req,
            "SCAN_UPLOAD",
            "files=1",
            "SUCCESS",
            "job1");
    ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);
    verify(writer).write(captured.capture());
    assertThat(captured.getValue().getUsername()).isEqualTo("admin");
    assertThat(captured.getValue().getJobId()).isEqualTo("job1");
    assertThat(captured.getValue().getIp()).isEqualTo("127.0.0.1");
    assertThat(captured.getValue().getTransport()).isEqualTo("BROWSER");
  }

  @Test
  void classifiesApiRequestAndNullUriWithoutThrowing() {
    AuditEventWriter writer = mock(AuditEventWriter.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    AuditService audit = new AuditService(writer);
    ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);

    when(req.getRequestURI()).thenReturn("/api/v1/jobs", (String) null);
    audit.record(null, req, "API_TEST", "test", "SUCCESS", null);
    audit.recordScan(null, req, "SCAN_PATH", "FAILED", 3L, "job-id");

    verify(writer, times(2)).write(captured.capture());
    assertThat(captured.getAllValues().get(0).getTransport()).isEqualTo("API_BASIC_OR_SESSION");
    assertThat(captured.getAllValues().get(1).getTransport()).isEqualTo("BROWSER");
    assertThat(captured.getAllValues().get(1).getEndpointId()).isEqualTo(3L);
  }

  @Test
  void oversizedAgentAndEveryUntrustedFieldAreBoundedToSchema() {
    AuditEventWriter writer = mock(AuditEventWriter.class);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("User-Agent")).thenReturn("U".repeat(10_240));
    when(req.getRemoteAddr()).thenReturn("1".repeat(500));
    String longValue = "X".repeat(11_000);

    assertThatCode(
            () ->
                new AuditService(writer)
                    .record(
                        new UsernamePasswordAuthenticationToken("A".repeat(500), ""),
                        req,
                        longValue,
                        longValue,
                        longValue,
                        longValue))
        .doesNotThrowAnyException();

    ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);
    verify(writer).write(captured.capture());
    AuditEvent event = captured.getValue();
    assertThat(event.getUserAgent()).hasSize(255).isEqualTo("U".repeat(255));
    assertThat(event.getUsername()).hasSize(64);
    assertThat(event.getAction()).hasSize(64);
    assertThat(event.getDetails()).hasSize(1024);
    assertThat(event.getOutcome()).hasSize(64);
    assertThat(event.getJobId()).hasSize(64);
    assertThat(event.getIp()).hasSize(64);
    assertThat(event.getTransport()).hasSizeLessThanOrEqualTo(32);
  }

  @Test
  void scanAndWorkerResultFieldsAlsoRespectSchema() {
    AuditEventWriter writer = mock(AuditEventWriter.class);
    AuditService audit = new AuditService(writer);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn("2".repeat(500));
    when(req.getHeader("User-Agent")).thenReturn("U".repeat(10_240));
    audit.recordScan(
        new UsernamePasswordAuthenticationToken("A".repeat(500), ""),
        req,
        "X".repeat(500),
        "Y".repeat(500),
        12L,
        "J".repeat(500));

    ScanJob job = new ScanJob();
    job.setId("I".repeat(500));
    job.setSubmittedBy("B".repeat(500));
    job.setSubmittedTransport("T".repeat(200));
    audit.recordResult(job);

    ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);
    verify(writer, times(2)).write(captured.capture());
    AuditEvent scan = captured.getAllValues().get(0);
    assertThat(scan.getUsername()).hasSize(64);
    assertThat(scan.getAction()).hasSize(64);
    assertThat(scan.getOutcome()).hasSize(64);
    assertThat(scan.getJobId()).hasSize(64);
    assertThat(scan.getIp()).hasSize(64);
    assertThat(scan.getUserAgent()).hasSize(255);
    AuditEvent result = captured.getAllValues().get(1);
    assertThat(result.getUsername()).hasSize(64);
    assertThat(result.getJobId()).hasSize(64);
    assertThat(result.getTransport()).hasSize(32);
  }

  @Test
  void persistenceFailureCannotTurnCompletedActionIntoError() {
    AuditEventWriter writer = mock(AuditEventWriter.class);
    doThrow(new DataIntegrityViolationException("simulated constraint violation"))
        .when(writer)
        .write(any(AuditEvent.class));
    AuditService audit = new AuditService(writer);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("User-Agent")).thenReturn("X".repeat(10_240));

    assertThatCode(
            () -> audit.record(null, req, "ENDPOINT_CREATE", "name@host:3310", "SUCCESS", null))
        .doesNotThrowAnyException();
    assertThatCode(() -> audit.recordScan(null, req, "SCAN_UPLOAD", "QUEUED", 1L, "job-id"))
        .doesNotThrowAnyException();
    assertThatCode(() -> audit.recordResult(new ScanJob())).doesNotThrowAnyException();
    verify(writer, times(3)).write(any(AuditEvent.class));
  }

  @Test
  void auditWriterUsesNewTransactionAndFlushesImmediately() throws Exception {
    Transactional transaction =
        AuditEventWriter.class
            .getMethod("write", AuditEvent.class)
            .getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

    AuditEventRepository repo = mock(AuditEventRepository.class);
    AuditEvent event = new AuditEvent("user", "ACTION", "detail", "OK", "id", "127.0.0.1", "UA");
    new AuditEventWriter(repo).write(event);
    verify(repo).saveAndFlush(event);
  }

  @Test
  void truncationNeverCutsSurrogatePairs() {
    String unicode = "A".repeat(254) + "\uD83D\uDE00" + "Z";
    assertThat(AuditService.limit(unicode, 255)).isEqualTo("A".repeat(254));
    assertThat(AuditService.limit(null, 255)).isNull();
    assertThat(AuditService.limit("ok", 255)).isEqualTo("ok");
  }
}
