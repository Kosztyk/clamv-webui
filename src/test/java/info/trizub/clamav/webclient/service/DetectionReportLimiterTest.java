package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

class DetectionReportLimiterTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void exactUtf8BoundHoldsForLongNamesAndUnicodeSignatures() throws Exception {
    Map<String, Collection<String>> input = new LinkedHashMap<>();
    for (int i = 0; i < 1024; ++i)
      input.put(
          "/data/" + i + "_" + "𐍈".repeat(30) + "_" + "p".repeat(80),
          List.of("Virus." + "🌍".repeat(20), "Malware." + "x".repeat(100)));
    var result = DetectionReportLimiter.limit(new ScanResult.VirusFound(input), mapper);
    assertThat(result.warning()).contains("omitted");
    var kept = ((ScanResult.VirusFound) result.result()).getFoundViruses();
    assertThat(kept.size()).isBetween(1, 1024);
    assertThat(mapper.writeValueAsBytes(kept).length)
        .isLessThanOrEqualTo(DetectionReportLimiter.MAX_UTF8_BYTES);
  }

  @Test
  void tooManySignaturesOrLongFieldsCannotCreateOversizedReport() throws Exception {
    Map<String, Collection<String>> input =
        Map.of("/data/malware", java.util.Collections.nCopies(300, "EICAR-" + "x".repeat(200)));
    var result = DetectionReportLimiter.limit(new ScanResult.VirusFound(input), mapper);
    assertThat(result.warning()).isNotNull();
    var kept = ((ScanResult.VirusFound) result.result()).getFoundViruses();
    assertThat(kept.get("/data/malware")).hasSize(16);
    assertThat(mapper.writeValueAsBytes(kept).length).isLessThan(65535);
  }

  @Test
  void smallPositiveVerdictsStayUnchangedAndFailuresStayPositive() {
    Map<String, Collection<String>> input = Map.of("/safe/virus", List.of("EICAR-Test"));
    var result = DetectionReportLimiter.limit(new ScanResult.VirusFound(input), mapper);
    assertThat(result.warning()).isNull();
    assertThat(((ScanResult.VirusFound) result.result()).getFoundViruses()).isEqualTo(input);
    assertThat(DetectionReportLimiter.limit(ScanResult.OK.INSTANCE, mapper).result())
        .isSameAs(ScanResult.OK.INSTANCE);
    var invalid =
        DetectionReportLimiter.limit(
            new ScanResult.VirusFound(Map.of("x".repeat(5000), List.of("test"))), mapper);
    assertThat(invalid.warning()).isNotNull();
    assertThat(invalid.result()).isInstanceOf(ScanResult.VirusFound.class);
  }
}
