package info.trizub.clamav.webclient.db;

import static org.assertj.core.api.Assertions.*;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:issue28history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=info.trizub.clamav.webclient.db.ScanJobSqlInspector"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Issue28JobHistoryTest {
  @Autowired ScanJobRepository repository;
  @Autowired ClamdEndpointRepository endpoints;

  @BeforeEach
  void reset() {
    repository.deleteAll();
    ScanJobSqlInspector.reset();
  }

  private ScanJob job(String id, ScanJobStatus status, Instant submitted, ClamdEndpoint endpoint) {
    ScanJob result = new ScanJob();
    result.setId(id);
    result.setType(ScanJobType.UPLOAD);
    result.setTarget("upload-" + id);
    result.setSubmittedAt(submitted);
    result.setStatus(status);
    result.setEndpoint(endpoint);
    result.setFoundVirusesJson("A".repeat(300000));
    result.setErrorMessage("B".repeat(300000));
    return result;
  }

  @Test
  void summariesFetchOnlyScalarColumnsAndUseDeterministicPages() {
    var endpoint =
        endpoints.save(
            new ClamdEndpoint("issue28", "localhost", 3310, xyz.capybara.clamav.Platform.UNIX));
    var now = Instant.parse("2026-11-12T12:00:00Z");
    repository.saveAllAndFlush(
        List.of(
            job("a", ScanJobStatus.FINISHED, now, endpoint),
            job("b", ScanJobStatus.QUEUED, now, endpoint),
            job("c", ScanJobStatus.RUNNING, now.minusSeconds(60), null)));
    ScanJobSqlInspector.reset();
    var ordering = Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id"));
    var first = repository.findSummaries(PageRequest.of(0, 2, ordering));
    var second = repository.findSummaries(PageRequest.of(1, 2, ordering));
    assertThat(first.getTotalElements()).isEqualTo(3);
    assertThat(first.getContent()).extracting(ScanJobSummary::id).containsExactly("b", "a");
    assertThat(first.getContent().get(0).endpointName()).isEqualTo("issue28");
    assertThat(second.getContent()).extracting(ScanJobSummary::id).containsExactly("c");
    var sql = String.join("\n", ScanJobSqlInspector.queries());
    assertThat(sql)
        .doesNotContain("found_viruses_json", "error_message", "stored_path", "quarantine_path");
    ScanJobSqlInspector.reset();
    assertThat(repository.findRecentSummaries(PageRequest.of(0, 2)))
        .extracting(ScanJobSummary::id)
        .containsExactly("b", "a");
    assertThat(String.join("\n", ScanJobSqlInspector.queries()))
        .doesNotContain("found_viruses_json", "error_message", "stored_path", "quarantine_path");
  }

  @Test
  void apiV1FilteredSummariesAndScopedIdReadsNeverSelectTextColumns() {
    var endpoint =
        endpoints.save(
            new ClamdEndpoint(
                "api-v1-projection", "localhost", 3310, xyz.capybara.clamav.Platform.UNIX));
    var now = Instant.parse("2026-11-12T12:00:00Z");
    var a = job("api-a", ScanJobStatus.FINISHED, now, endpoint);
    a.setVerdict(ScanVerdict.VIRUS_FOUND);
    a.setSubmittedTransport("API_TOKEN");
    var b = job("api-b", ScanJobStatus.QUEUED, now.minusSeconds(1), endpoint);
    repository.saveAllAndFlush(List.of(a, b));
    ScanJobSqlInspector.reset();
    var page =
        repository.findFilteredSummaries(
            endpoint.getId(),
            ScanJobStatus.FINISHED,
            ScanVerdict.VIRUS_FOUND,
            true,
            List.of(endpoint.getId()),
            PageRequest.of(0, 25));
    assertThat(page.getContent()).extracting(ScanJobSummary::id).containsExactly("api-a");
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).submittedTransport()).isEqualTo("API_TOKEN");
    assertThat(repository.findSummariesByIdIn(List.of("api-a")))
        .extracting(ScanJobSummary::id)
        .containsExactly("api-a");
    assertThat(String.join("\n", ScanJobSqlInspector.queries()))
        .doesNotContain("found_viruses_json", "error_message", "quarantine_path", "stored_path");
  }

  @Test
  void apiDiagnosticTextQueryOnlyTargetsRequestedPageAndVerdicts() {
    var endpoint =
        new ClamdEndpoint("diagnostic-test", "127.0.0.1", 3310, xyz.capybara.clamav.Platform.UNIX);
    endpoint = endpoints.saveAndFlush(endpoint);
    var virus = job("diag-virus", ScanJobStatus.FINISHED, Instant.now(), endpoint);
    virus.setVerdict(ScanVerdict.VIRUS_FOUND);
    virus.setFoundVirusesJson("{\"file\":[\"Eicar-Test-Signature\"]}");
    var clean = job("diag-clean", ScanJobStatus.FINISHED, Instant.now(), endpoint);
    clean.setVerdict(ScanVerdict.OK);
    repository.saveAllAndFlush(List.of(virus, clean));
    ScanJobSqlInspector.reset();
    var page = repository.findSummaries(PageRequest.of(0, 10));
    var onlyMetadata = String.join("\n", ScanJobSqlInspector.queries());
    assertThat(onlyMetadata)
        .doesNotContain("found_viruses_json", "error_message", "quarantine_path");
    var ids = page.getContent().stream().map(ScanJobSummary::id).toList();
    assertThat(repository.findApiListQuarantine(ids)).hasSize(2);
    assertThat(
            repository.findApiListDiagnostics(
                List.of("diag-virus"), List.of(ScanVerdict.VIRUS_FOUND, ScanVerdict.ERROR)))
        .singleElement()
        .satisfies(row -> assertThat(row.foundVirusesJson()).contains("Eicar-Test-Signature"));
    assertThat(
            repository.findApiListDiagnostics(
                List.of("diag-clean"), List.of(ScanVerdict.VIRUS_FOUND, ScanVerdict.ERROR)))
        .isEmpty();
  }

  @Test
  void interruptedJobsAreResetInBulkAndOnlyFirstHundredIdsAreLoaded() {
    var now = Instant.parse("2026-11-12T12:00:00Z");
    repository.saveAllAndFlush(
        List.of(
            job("running", ScanJobStatus.RUNNING, now, null),
            job("queued", ScanJobStatus.QUEUED, now.minusSeconds(10), null),
            job("done", ScanJobStatus.FINISHED, now.minusSeconds(20), null)));
    ScanJobSqlInspector.reset();
    assertThat(repository.requeueInterrupted(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED))
        .isEqualTo(1);
    assertThat(repository.findQueuedIds(ScanJobStatus.QUEUED, PageRequest.of(0, 1)))
        .containsExactly("queued");
    assertThat(repository.findById("running").orElseThrow().getStatus())
        .isEqualTo(ScanJobStatus.QUEUED);
    assertThat(repository.findById("done").orElseThrow().getStatus())
        .isEqualTo(ScanJobStatus.FINISHED);
    var sql = String.join("\n", ScanJobSqlInspector.queries());
    assertThat(sql).contains("update scan_jobs");
  }
}
