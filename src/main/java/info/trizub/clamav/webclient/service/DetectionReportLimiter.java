package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/**
 * Bounds *serialized UTF-8* detection evidence on every scan transport. MySQL and MariaDB TEXT
 * columns have a byte (not character) limit of 65,535 bytes. A bounded count of paths alone is not
 * sufficient to stay under that limit. Truncation is always reported; it never changes a positive
 * verdict to OK.
 */
public final class DetectionReportLimiter {
  public static final int MAX_PATHS = 1024;
  public static final int MAX_SIGNATURES_PER_PATH = 16;
  public static final int MAX_UTF8_BYTES = 48 * 1024;
  public static final String WARNING =
      "ClamAV detection report exceeded safety limits; some infected paths or signatures were omitted and may require manual quarantine.";

  private DetectionReportLimiter() {}

  public record Limited(ScanResult result, String warning) {}

  public static Limited limit(ScanResult raw, ObjectMapper mapper) {
    if (!(raw instanceof ScanResult.VirusFound virus)) return new Limited(raw, null);
    Map<String, Collection<String>> original = virus.getFoundViruses();
    Map<String, Collection<String>> bounded = new LinkedHashMap<>();
    int bytes = 2; // JSON object braces
    boolean truncated = original == null || original.isEmpty();
    if (original != null)
      for (var entry : original.entrySet()) {
        String path = entry.getKey();
        if (path == null || path.length() > 4096 || bounded.size() >= MAX_PATHS) {
          truncated = true;
          continue;
        }
        List<String> signatures = new ArrayList<>();
        Collection<String> source = entry.getValue();
        if (source != null)
          for (String signature : source) {
            if (signature == null
                || signature.length() > 256
                || signatures.size() >= MAX_SIGNATURES_PER_PATH) {
              truncated = true;
              continue;
            }
            signatures.add(signature);
          }
        int entryBytes;
        try {
          // Encodes escapes and non-ASCII characters exactly as Jackson will persist them.
          entryBytes = mapper.writeValueAsBytes(Map.of(path, signatures)).length - 2;
        } catch (JsonProcessingException ex) {
          throw new IllegalArgumentException("Cannot encode ClamAV detection result", ex);
        }
        if (bytes + entryBytes + (bounded.isEmpty() ? 0 : 1) > MAX_UTF8_BYTES) {
          truncated = true;
          continue;
        }
        bytes += entryBytes + (bounded.isEmpty() ? 0 : 1);
        bounded.put(path, List.copyOf(signatures));
      }
    return new Limited(new ScanResult.VirusFound(bounded), truncated ? WARNING : null);
  }

  public static String joinWarnings(String previous, String additional) {
    if (additional == null || additional.isBlank()) return previous;
    if (previous == null || previous.isBlank()) return additional;
    return previous + "\n" + additional;
  }
}
