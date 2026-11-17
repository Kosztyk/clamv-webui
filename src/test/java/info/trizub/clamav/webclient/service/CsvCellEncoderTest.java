package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: issue #41, CSV spreadsheet protection must preserve the entire source value. */
class CsvCellEncoderTest {
  @Test
  void prefixesDangerousFirstCharactersWithoutReplacingAnySourceCharacters() {
    for (String input :
        List.of(
            "-5",
            "+5",
            "=SUM(1,2)",
            "==x",
            "++id",
            "--id",
            "@SUM(1)",
            "\tTAB",
            "\rCR",
            "\nLF",
            "|DDE",
            "%value")) {
      assertThat(unquote(CsvCellEncoder.encode(input)))
          .as("Spreadsheet prefix for %s", input.replace("\r", "\\r").replace("\n", "\\n"))
          .isEqualTo("'" + input);
    }
  }

  @Test
  void preservesOrdinaryValuesAndCsvEscapingExactly() {
    for (String input :
        List.of(
            "",
            "hello",
            "123",
            "0.25",
            "abc-5",
            "word|pipe",
            "text %value",
            "a,b",
            "embedded \"quote\"",
            "two\nlines",
            " leading text",
            "final ",
            "résumé",
            "\u00a0ordinary text")) {
      assertThat(unquote(CsvCellEncoder.encode(input)))
          .as("CSV round trip: %s", input)
          .isEqualTo(input);
    }
    assertThat(CsvCellEncoder.encode("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
  }

  @Test
  void handlesLeadingWhitespaceRepeatedPrefixesAndQuotedFormulas() {
    for (String input :
        List.of(
            "  =1+1", "\u00a0@NOW()", "  -5", "\t=CMD", "=\"2\"", "  |cmd", " %value", "\r\n+1")) {
      assertThat(unquote(CsvCellEncoder.encode(input))).isEqualTo("'" + input);
    }
    assertThat(CsvCellEncoder.encode("=\"cmd\",42")).isEqualTo("\"'=\"\"cmd\"\",42\"");
  }

  private static String unquote(String encoded) {
    assertThat(encoded).startsWith("\"").endsWith("\"");
    return encoded.substring(1, encoded.length() - 1).replace("\"\"", "\"");
  }
}
