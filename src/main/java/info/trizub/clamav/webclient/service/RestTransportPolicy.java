package info.trizub.clamav.webclient.service;

import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared fail-fast rule for both REST clients and the Configuration Center probe. Only an explicit
 * administrator opt-in may send credentials over non-loopback HTTP. Do not resolve hostnames to
 * grant loopback exceptions (DNS rebinding / spoofing).
 */
public final class RestTransportPolicy {
  private static final Logger log = LoggerFactory.getLogger(RestTransportPolicy.class);

  private RestTransportPolicy() {}

  public static void validate(String rawUrl, String apiKey, boolean allowInsecure) {
    if (rawUrl == null || rawUrl.isBlank()) return;
    final URI uri;
    try {
      uri = URI.create(rawUrl.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("CLAMAV_REST_URL must be a valid HTTP(S) URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
        || uri.getHost() == null
        || uri.getHost().isBlank()
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getRawQuery() != null) {
      throw new IllegalArgumentException(
          "CLAMAV_REST_URL must be an HTTP(S) URL without credentials, query or fragment");
    }
    if (!scheme.equalsIgnoreCase("http")
        || apiKey == null
        || apiKey.isBlank()
        || loopback(uri.getHost())) return;
    if (!allowInsecure) {
      throw new IllegalArgumentException(
          "Credentials over non-loopback HTTP are blocked. Use HTTPS or explicitly enable CLAMAV_REST_ALLOW_INSECURE=true (Configuration Center: Allow insecure REST API credentials)");
    }
    log.warn(
        "SECURITY WARNING: external REST scanner credentials will be sent over unencrypted HTTP because CLAMAV_REST_ALLOW_INSECURE=true");
  }

  static boolean loopback(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.equals("localhost")) return true;
    if (normalized.startsWith("[") && normalized.endsWith("]"))
      normalized = normalized.substring(1, normalized.length() - 1);
    if (normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) return true;
    String[] parts = normalized.split("\\.", -1);
    if (parts.length != 4 || !parts[0].equals("127")) return false;
    for (String part : parts) {
      if (part.isEmpty() || !part.matches("[0-9]{1,3}")) return false;
      if (Integer.parseInt(part) > 255) return false;
    }
    return true;
  }
}
