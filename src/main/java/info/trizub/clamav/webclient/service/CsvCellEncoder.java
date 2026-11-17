package info.trizub.clamav.webclient.service;

/**
 * CSV cells for exports intended to be opened with spreadsheet applications.
 *
 * <p>Never remove or replace a user-provided prefix. A leading apostrophe is added only when the
 * value could be interpreted as a spreadsheet formula, followed by ordinary RFC 4180-style CSV
 * quoting. Consumers needing the original data without spreadsheet protection should use the JSON
 * export.
 */
public final class CsvCellEncoder {
  private CsvCellEncoder() {}

  public static String encode(String value) {
    if (value == null) value = "";
    String protectedValue = needsApostrophe(value) ? "'" + value : value;
    return "\"" + protectedValue.replace("\"", "\"\"") + "\"";
  }

  static boolean needsApostrophe(String value) {
    if (value.isEmpty()) return false;
    // Leading line breaks / tabs can change how some spreadsheet apps
    // interpret the start of the cell, even when followed by plain text.
    char first = value.charAt(0);
    if (first == '\t' || first == '\r' || first == '\n') return true;

    // Some spreadsheet engines ignore leading whitespace before formula
    // evaluation; retain it verbatim while protecting the resulting cell.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '=' || c == '+' || c == '-' || c == '@' || c == '|' || c == '%') return true;
      if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
    }
    return false;
  }
}
