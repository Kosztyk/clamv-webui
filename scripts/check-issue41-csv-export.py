#!/usr/bin/env python3
"""Issue #41: static source contracts plus real Java 21 CSV encoder execution.

This verifies the fix without pretending it is a full Maven/Spring integration run.
"""
from pathlib import Path
import subprocess
import tempfile
import sys

root = Path(__file__).resolve().parents[1]
main = root / 'src/main/java/info/trizub/clamav/webclient'
tests = root / 'src/test/java/info/trizub/clamav/webclient'
codec = main / 'service/CsvCellEncoder.java'
web = (main / 'web/RestMonitorController.java').read_text(encoding='utf-8')
report = (main / 'service/RestReportService.java').read_text(encoding='utf-8')
test = (tests / 'service/CsvCellEncoderTest.java').read_text(encoding='utf-8')
integration = (tests / 'web/RestMonitorIntegrationTest.java').read_text(encoding='utf-8')
checks = {
    'shared source CSV encoder exists': codec.is_file(),
    'job CSV uses shared encoder': 'return CsvCellEncoder.encode(value);' in web,
    'detailed report CSV uses shared encoder': 'return CsvCellEncoder.encode(value);' in report,
    'broken destructive regex eliminated': 'replaceAll("^[=+' not in web,
    'dangerous prefix is added, not replaced': '"\'" + value' in codec.read_text(encoding='utf-8'),
    'pipes and percent protected': "c == '|' || c == '%'" in codec.read_text(encoding='utf-8'),
    'CSV quotes escaped': 'replace("\\\"", "\\\"\\\"")' in codec.read_text(encoding='utf-8'),
    'negative number test present': '"-5"' in test,
    'repeated prefix test present': '"==x"' in test,
    'round-trip normal test present': 'preservesOrdinaryValuesAndCsvEscapingExactly' in test,
    'HTTP export regression present': 'csvExportProtectsFormulasWithoutCorruptingIdsOrOrdinaryFields' in integration,
    'detailed report export regression present': 'detailedCsvUsesTheSameLosslessSpreadsheetProtection' in (tests/'service/RestReportServiceTest.java').read_text(),
    'JSON export remains supported': '"json".equalsIgnoreCase(format)' in web,
}
for name, passed in checks.items():
    print(('PASS' if passed else 'FAIL') + ': ' + name)
if not all(checks.values()):
    sys.exit(1)

# Compile and execute the actual encoder in isolation using only the installed JDK.
smoke = r'''import info.trizub.clamav.webclient.service.CsvCellEncoder;
public final class Issue41CsvSmoke {
  private static String decoded(String s) {
    if (!s.startsWith("\"") || !s.endsWith("\"")) throw new AssertionError(s);
    return s.substring(1, s.length() - 1).replace("\"\"", "\"");
  }
  private static void expect(String input, boolean escaped) {
    String expected = escaped ? "'" + input : input;
    String encoded = CsvCellEncoder.encode(input);
    if (!expected.equals(decoded(encoded))) throw new AssertionError("Bad encoding: " + input + ": " + encoded);
  }
  public static void main(String[] args) {
    for (String value : new String[]{"-5", "+1", "==x", "@SUM(1)", "|cmd", "%x", "\tvalue", "\rvalue", "\nvalue", "  =x", "\u00a0@x", "  -5", "--id", "=\"Hi\""}) expect(value, true);
    for (String value : new String[]{"", "5", "a-5", "a|b", "abc", " a ", "ab,cd", "some\ntext", "\u00e9lan", "say \"hello\""}) expect(value, false);
    if (!"\"a,\"\"b\"\"\"".equals(CsvCellEncoder.encode("a,\"b\""))) throw new AssertionError("CSV quote escaping failed");
    System.out.println("PASS: Java 21 encoder behavior (24 scenarios and quote encoding)");
  }
}
'''
with tempfile.TemporaryDirectory(prefix='issue41-csv-') as directory:
    folder = Path(directory)
    (folder / 'Issue41CsvSmoke.java').write_text(smoke, encoding='utf-8')
    try:
        subprocess.run(['javac', '--release', '21', '-d', str(folder), str(codec), str(folder/'Issue41CsvSmoke.java')], check=True)
        subprocess.run(['java', '-cp', str(folder), 'Issue41CsvSmoke'], check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        print('FAIL: Java 21 smoke execution:', e)
        sys.exit(1)
print(f'Issue #41 offline checks passed: {len(checks)}/{len(checks)} static source checks and Java smoke test.')
