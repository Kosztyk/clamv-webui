package info.trizub.clamav.webclient.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import info.trizub.clamav.webclient.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiV1DtoTest {
  @Test
  void legacyApiJobsMethodReferenceKeepsOriginalSummaryContract() {
    Instant now = Instant.parse("2026-11-12T12:00:00Z");
    ScanJobSummary infected =
        new ScanJobSummary(
            "legacy-infected",
            ScanJobType.UPLOAD,
            ScanJobStatus.FINISHED,
            ScanVerdict.VIRUS_FOUND,
            "sample.bin",
            null,
            null,
            now,
            4L,
            "default",
            123L,
            now.minusSeconds(10),
            now);
    ScanJobSummary failed =
        new ScanJobSummary(
            "legacy-error",
            ScanJobType.PATH,
            ScanJobStatus.FINISHED,
            ScanVerdict.ERROR,
            "/private/path",
            null,
            null,
            now,
            4L,
            "default",
            null,
            now.minusSeconds(1),
            now);
    // This is the exact method reference used by /api/jobs: compiling this test
    // catches an incompatible signature change before the Docker build.
    var legacy =
        java.util.List.of(infected, failed).stream().map(ApiV1Dtos.Job::fromSummary).toList();
    assertThat(legacy).hasSize(2);
    assertThat(legacy.get(0).filename()).isEqualTo("sample.bin");
    assertThat(legacy.get(0).signatures()).isEmpty();
    assertThat(legacy.get(0).quarantineStatus()).isEqualTo("UNKNOWN");
    assertThat(legacy.get(0).durationMillis()).isEqualTo(10000L);
    assertThat(legacy.get(1).filename()).isNull();
    assertThat(legacy.get(1).error().code()).isEqualTo("SCAN_FAILED");
    assertThat(legacy.get(1).toString()).doesNotContain("/private/path");
    // The three-argument API v1 overload must remain present and functional.
    assertThat(ApiV1Dtos.Job.fromSummary(infected, "/private/quarantine", null).quarantineStatus())
        .isEqualTo("RECORDED");
  }

  @Test
  void pageSummariesPreserveSignaturesSanitizedErrorsAndQuarantineWithoutPaths() {
    ScanJob virus = new ScanJob();
    virus.setId("infected");
    virus.setType(ScanJobType.UPLOAD);
    virus.setStatus(ScanJobStatus.FINISHED);
    virus.setVerdict(ScanVerdict.VIRUS_FOUND);
    virus.setTarget("sample.txt");
    virus.setQuarantinePath("/app/data/private/secret.bin");
    virus.setFoundVirusesJson("{\"/private/source\":[\"Eicar-Test-Signature\"]}");
    ScanJobSummary summary =
        new ScanJobSummary(
            virus.getId(),
            virus.getType(),
            virus.getStatus(),
            virus.getVerdict(),
            virus.getTarget(),
            null,
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    var diag =
        new ScanJobListDiagnostics(
            virus.getId(), virus.getFoundVirusesJson(), null, virus.getQuarantinePath());
    var listed = ApiV1Dtos.Job.fromSummary(summary, virus.getQuarantinePath(), diag);
    var detailed = ApiV1Dtos.Job.from(virus);
    assertThat(listed.signatures()).isEqualTo(detailed.signatures());
    assertThat(listed.quarantineStatus()).isEqualTo("RECORDED");
    assertThat(listed.toString()).doesNotContain("/private/source", "/app/data/private");

    virus.setQuarantinePath(null);
    virus.setErrorMessage(info.trizub.clamav.webclient.service.QuarantineService.UPLOAD_FAILURE);
    diag =
        new ScanJobListDiagnostics(
            virus.getId(), virus.getFoundVirusesJson(), virus.getErrorMessage(), null);
    assertThat(ApiV1Dtos.Job.fromSummary(summary, null, diag).quarantineStatus())
        .isEqualTo("FAILED");
    assertThat(ApiV1Dtos.Job.fromSummary(summary, null, diag).error().code())
        .isEqualTo("QUARANTINE_FAILED");

    ScanJob error = new ScanJob();
    error.setId("policy-error");
    error.setType(ScanJobType.PATH);
    error.setStatus(ScanJobStatus.FINISHED);
    error.setVerdict(ScanVerdict.ERROR);
    error.setErrorMessage("Path is excluded by the daemon scan policy.");
    ScanJobSummary failed =
        new ScanJobSummary(
            error.getId(),
            error.getType(),
            error.getStatus(),
            error.getVerdict(),
            "/private/file",
            null,
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    assertThat(
            ApiV1Dtos.Job.fromSummary(
                    failed,
                    null,
                    new ScanJobListDiagnostics(error.getId(), null, error.getErrorMessage(), null))
                .error())
        .isEqualTo(ApiV1Dtos.Job.from(error).error());
    assertThat(ApiV1Dtos.Job.fromSummary(failed, null, null).filename()).isNull();

    // Inconsistent legacy records still retain the same quarantine status as the detail endpoint.
    error.setVerdict(ScanVerdict.OK);
    error.setQuarantinePath("/private/quarantine");
    ScanJobSummary clean =
        new ScanJobSummary(
            error.getId(),
            error.getType(),
            error.getStatus(),
            error.getVerdict(),
            error.getTarget(),
            null,
            null,
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    assertThat(ApiV1Dtos.Job.fromSummary(clean, error.getQuarantinePath(), null).quarantineStatus())
        .isEqualTo(ApiV1Dtos.Job.from(error).quarantineStatus());
  }

  @Test
  void pathJobNeverReturnsServerPathsOrErrorText() {
    ScanJob job = new ScanJob();
    job.setId("abc123");
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setTarget("/secrets/private/file.bin");
    job.setStoredPath("/app/data/uploads/secret");
    job.setQuarantinePath("/app/data/quarantine/secret");
    job.setErrorMessage("internal path");
    job.setFoundVirusesJson("{\"/secrets/private/file.bin\":[\"EICAR\"]}");
    var dto = ApiV1Dtos.Job.from(job);
    assertThat(dto.id()).isEqualTo("abc123");
    assertThat(dto.filename()).isNull();
    assertThat(dto.verdict()).isEqualTo("VIRUS_FOUND");
    assertThat(dto.quarantineStatus()).isEqualTo("RECORDED");
    assertThat(dto.toString())
        .doesNotContain("/secrets", "/app/data", "internal path", "storedPath");
  }

  @Test
  void uploadJobReturnsOnlyDisplayFilename() {
    ScanJob job = new ScanJob();
    job.setId("a");
    job.setType(ScanJobType.UPLOAD);
    job.setTarget("sample.txt");
    job.setStoredPath("/app/data/uploads/secret-sample.txt");
    var dto = ApiV1Dtos.Job.from(job);
    assertThat(dto.filename()).isEqualTo("sample.txt");
    assertThat(dto.toString()).doesNotContain("/app/data");
  }

  @Test
  void returnsOnlyDistinctSortedSignaturesAndExecutionDuration() {
    ScanJob job = new ScanJob();
    job.setId("signatures");
    job.setType(ScanJobType.PATH);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setSubmittedAt(Instant.parse("2026-10-30T00:00:00Z"));
    job.setStartedAt(Instant.parse("2026-10-30T00:10:00Z"));
    job.setFinishedAt(job.getStartedAt().plusMillis(2500));
    job.setFoundVirusesJson(
        "{\"/private/one\":[\"Test.Z\",\"Test.A\",\"Test.Z\"],\"/private/two\":[\"Test.A\"]}");
    var value = ApiV1Dtos.Job.from(job);
    assertThat(value.signatures()).containsExactly("Test.A", "Test.Z");
    assertThat(value.durationMillis()).isEqualTo(2500);
    assertThat(value.toString()).doesNotContain("/private");
  }

  @Test
  void missingCorruptAndOversizedDetectionMetadataNeverLeaksRawContent() {
    ScanJob job = new ScanJob();
    job.setId("metadata");
    job.setType(ScanJobType.PATH);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    for (String raw :
        new String[] {null, "{invalid /private/raw", "[]", "x".repeat(1024 * 1024 + 1)}) {
      job.setFoundVirusesJson(raw);
      assertThat(ApiV1Dtos.Job.from(job).signatures()).isEmpty();
    }
  }

  @Test
  void legacyTextSignatureIsSupportedAndUnsafeValuesAreOmitted() {
    ScanJob job = new ScanJob();
    job.setId("legacy");
    job.setType(ScanJobType.PATH);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setFoundVirusesJson(
        "{\"/private/file\":\"Test.Legacy\",\"file2\":[\"/private/leak\",\"unsafe\\nname\",\"Test.Safe\",42]}");
    assertThat(ApiV1Dtos.Job.from(job).signatures()).containsExactly("Test.Legacy", "Test.Safe");
  }

  @Test
  void runningAndInvalidTimestampsHaveNoFabricatedDuration() {
    ScanJob job = new ScanJob();
    job.setId("time");
    job.setType(ScanJobType.UPLOAD);
    job.setStartedAt(Instant.now());
    assertThat(ApiV1Dtos.Job.from(job).durationMillis()).isNull();
    job.setFinishedAt(job.getStartedAt().minusSeconds(1));
    assertThat(ApiV1Dtos.Job.from(job).durationMillis()).isNull();
  }

  @Test
  void cleanVerdictDoesNotExposeStaleDetectionMetadata() {
    ScanJob job = new ScanJob();
    job.setId("clean");
    job.setType(ScanJobType.UPLOAD);
    job.setVerdict(ScanVerdict.OK);
    job.setFoundVirusesJson("{\"file\":[\"Test.Stale\"]}");
    assertThat(ApiV1Dtos.Job.from(job).signatures()).isEmpty();
  }

  @Test
  void quarantineStatusDistinguishesUnrecordedUploadsFromUnknownPathHistory() {
    ScanJob job = new ScanJob();
    job.setType(ScanJobType.UPLOAD);
    assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("PENDING");
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.OK);
    assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("NOT_APPLICABLE");
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("NOT_RECORDED");
    job.setType(ScanJobType.PATH);
    assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("UNKNOWN");
  }

  @Test
  void publicErrorsUseFixedCodesAndSummariesWithoutReflectingRawDetails() {
    ScanJob job = new ScanJob();
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.ERROR);
    String[][] cases = {
      {"Path is excluded by the daemon scan policy.", "POLICY_REJECTED"},
      {"No eligible regular files remain after daemon scan-policy filtering.", "NO_ELIGIBLE_FILES"},
      {
        "Daemon configuration is unavailable or invalid; scanning the default endpoint is paused.",
        "CONFIGURATION_UNAVAILABLE"
      },
      {"File exceeds the effective upload limit of 100 bytes.", "LIMIT_EXCEEDED"},
      {"File exceeds the daemon scan-size limit of 100 bytes.", "LIMIT_EXCEEDED"},
      {"connection /private/config password=secret\nraw daemon output", "SCAN_FAILED"},
      {null, "SCAN_FAILED"}
    };
    for (String[] entry : cases) {
      job.setErrorMessage(entry[0]);
      var error = ApiV1Dtos.Job.from(job).error();
      assertThat(error.code()).isEqualTo(entry[1]);
      assertThat(error.toString()).doesNotContain("/private", "password=", "raw daemon");
    }
    job.setVerdict(ScanVerdict.OK);
    assertThat(ApiV1Dtos.Job.from(job).error()).isNull();
  }

  @org.junit.jupiter.api.Test
  void truncatedMalwareReportIsExplicitlyIncompleteInsteadOfUnverifiedSuccess() {
    var job = new ScanJob();
    job.setId("truncated");
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setErrorMessage(info.trizub.clamav.webclient.service.DetectionReportLimiter.WARNING);
    var dto = ApiV1Dtos.Job.from(job);
    assertThat(dto.quarantineStatus()).isEqualTo("INCOMPLETE");
    assertThat(dto.error().code()).isEqualTo("DETECTION_REPORT_TRUNCATED");
    job.setErrorMessage(
        info.trizub.clamav.webclient.service.DetectionReportLimiter.WARNING
            + "\n"
            + info.trizub.clamav.webclient.service.QuarantineService.SOURCE_FAILURE);
    assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("FAILED");
    assertThat(ApiV1Dtos.Job.from(job).error().code()).isEqualTo("QUARANTINE_FAILED");
  }

  @org.junit.jupiter.api.Test
  void combinedPartialScanAndQuarantineWarningStillReturnsQuarantineFailure() {
    var job = new ScanJob();
    job.setId("partial-malware");
    job.setType(ScanJobType.PATH);
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setErrorMessage(
        "Directory scan incomplete: 2 files inaccessible\n"
            + info.trizub.clamav.webclient.service.QuarantineService.SOURCE_FAILURE);
    var detail = ApiV1Dtos.Job.from(job);
    assertThat(detail.quarantineStatus()).isEqualTo("FAILED");
    assertThat(detail.error().code()).isEqualTo("QUARANTINE_FAILED");
  }
}
