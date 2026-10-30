package info.trizub.clamav.webclient.api.v1;

import info.trizub.clamav.webclient.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ApiV1DtoTest {
    @Test void pathJobNeverReturnsServerPathsOrErrorText() {
        ScanJob job = new ScanJob(); job.setId("abc123"); job.setType(ScanJobType.PATH);
        job.setStatus(ScanJobStatus.FINISHED); job.setVerdict(ScanVerdict.VIRUS_FOUND);
        job.setTarget("/secrets/private/file.bin"); job.setStoredPath("/app/data/uploads/secret");
        job.setQuarantinePath("/app/data/quarantine/secret"); job.setErrorMessage("internal path");
        job.setFoundVirusesJson("{\"/secrets/private/file.bin\":[\"EICAR\"]}");
        var dto = ApiV1Dtos.Job.from(job);
        assertThat(dto.id()).isEqualTo("abc123"); assertThat(dto.filename()).isNull();
        assertThat(dto.verdict()).isEqualTo("VIRUS_FOUND");
        assertThat(dto.quarantineStatus()).isEqualTo("RECORDED");
        assertThat(dto.toString()).doesNotContain("/secrets", "/app/data", "internal path", "storedPath");
    }
    @Test void uploadJobReturnsOnlyDisplayFilename() {
        ScanJob job = new ScanJob(); job.setId("a"); job.setType(ScanJobType.UPLOAD);
        job.setTarget("sample.txt"); job.setStoredPath("/app/data/uploads/secret-sample.txt");
        var dto = ApiV1Dtos.Job.from(job);
        assertThat(dto.filename()).isEqualTo("sample.txt");
        assertThat(dto.toString()).doesNotContain("/app/data");
    }
    @Test void returnsOnlyDistinctSortedSignaturesAndExecutionDuration() {
        ScanJob job=new ScanJob();job.setId("signatures");job.setType(ScanJobType.PATH);
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        job.setSubmittedAt(Instant.parse("2026-10-30T00:00:00Z"));
        job.setStartedAt(Instant.parse("2026-10-30T00:10:00Z"));job.setFinishedAt(job.getStartedAt().plusMillis(2500));
        job.setFoundVirusesJson("{\"/private/one\":[\"Test.Z\",\"Test.A\",\"Test.Z\"],\"/private/two\":[\"Test.A\"]}");
        var value=ApiV1Dtos.Job.from(job);
        assertThat(value.signatures()).containsExactly("Test.A","Test.Z");assertThat(value.durationMillis()).isEqualTo(2500);
        assertThat(value.toString()).doesNotContain("/private");
    }
    @Test void missingCorruptAndOversizedDetectionMetadataNeverLeaksRawContent() {
        ScanJob job=new ScanJob();job.setId("metadata");job.setType(ScanJobType.PATH);job.setVerdict(ScanVerdict.VIRUS_FOUND);
        for (String raw:new String[]{null,"{invalid /private/raw","[]","x".repeat(1024*1024+1)}) {
            job.setFoundVirusesJson(raw);assertThat(ApiV1Dtos.Job.from(job).signatures()).isEmpty();
        }
    }
    @Test void legacyTextSignatureIsSupportedAndUnsafeValuesAreOmitted() {
        ScanJob job=new ScanJob();job.setId("legacy");job.setType(ScanJobType.PATH);job.setVerdict(ScanVerdict.VIRUS_FOUND);
        job.setFoundVirusesJson("{\"/private/file\":\"Test.Legacy\",\"file2\":[\"/private/leak\",\"unsafe\\nname\",\"Test.Safe\",42]}");
        assertThat(ApiV1Dtos.Job.from(job).signatures()).containsExactly("Test.Legacy","Test.Safe");
    }
    @Test void runningAndInvalidTimestampsHaveNoFabricatedDuration() {
        ScanJob job=new ScanJob();job.setId("time");job.setType(ScanJobType.UPLOAD);job.setStartedAt(Instant.now());
        assertThat(ApiV1Dtos.Job.from(job).durationMillis()).isNull();
        job.setFinishedAt(job.getStartedAt().minusSeconds(1));assertThat(ApiV1Dtos.Job.from(job).durationMillis()).isNull();
    }
    @Test void cleanVerdictDoesNotExposeStaleDetectionMetadata() {
        ScanJob job=new ScanJob();job.setId("clean");job.setType(ScanJobType.UPLOAD);job.setVerdict(ScanVerdict.OK);
        job.setFoundVirusesJson("{\"file\":[\"Test.Stale\"]}");
        assertThat(ApiV1Dtos.Job.from(job).signatures()).isEmpty();
    }
    @Test void quarantineStatusDistinguishesUnrecordedUploadsFromUnknownPathHistory() {
        ScanJob job=new ScanJob();job.setType(ScanJobType.UPLOAD);
        assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("PENDING");
        job.setStatus(ScanJobStatus.FINISHED);job.setVerdict(ScanVerdict.OK);
        assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("NOT_APPLICABLE");
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("NOT_RECORDED");
        job.setType(ScanJobType.PATH);
        assertThat(ApiV1Dtos.Job.from(job).quarantineStatus()).isEqualTo("UNKNOWN");
    }
    @Test void publicErrorsUseFixedCodesAndSummariesWithoutReflectingRawDetails() {
        ScanJob job=new ScanJob();job.setType(ScanJobType.PATH);job.setStatus(ScanJobStatus.FINISHED);job.setVerdict(ScanVerdict.ERROR);
        String[][] cases={
            {"Path is excluded by the daemon scan policy.","POLICY_REJECTED"},
            {"No eligible regular files remain after daemon scan-policy filtering.","NO_ELIGIBLE_FILES"},
            {"Daemon configuration is unavailable or invalid; scanning the default endpoint is paused.","CONFIGURATION_UNAVAILABLE"},
            {"File exceeds the effective upload limit of 100 bytes.","LIMIT_EXCEEDED"},
            {"File exceeds the daemon scan-size limit of 100 bytes.","LIMIT_EXCEEDED"},
            {"connection /private/config password=secret\nraw daemon output", "SCAN_FAILED"},
            {null,"SCAN_FAILED"}
        };
        for (String[] entry:cases) {
            job.setErrorMessage(entry[0]);var error=ApiV1Dtos.Job.from(job).error();
            assertThat(error.code()).isEqualTo(entry[1]);assertThat(error.toString()).doesNotContain("/private","password=","raw daemon");
        }
        job.setVerdict(ScanVerdict.OK);assertThat(ApiV1Dtos.Job.from(job).error()).isNull();
    }
}
