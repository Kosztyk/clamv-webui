package info.trizub.clamav.webclient.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import xyz.capybara.clamav.Platform;

/** Validation for browser-created TCP ClamD endpoints, independent of the UI. */
public final class EndpointInputValidator {
  private EndpointInputValidator() {}

  public static String name(String input) {
    if (input == null
        || !input.equals(input.trim())
        || !input.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}"))
      throw new IllegalArgumentException(
          "Endpoint name: use 1–64 letters, numbers, spaces, dots, underscores or hyphens");
    return input;
  }

  public static String host(String input) {
    if (input == null
        || input.isBlank()
        || !input.equals(input.trim())
        || input.length() > 255
        || input.contains("/")
        || input.contains("\\")
        || input.contains("@")
        || input.contains("%"))
      throw new IllegalArgumentException(
          "Endpoint host: enter a hostname or IP address, not a socket path or URL");
    String unwrapped =
        input.startsWith("[") && input.endsWith("]")
            ? input.substring(1, input.length() - 1)
            : input;
    if (unwrapped.contains(":")) {
      if (!unwrapped.matches("[0-9a-fA-F:.]+"))
        throw new IllegalArgumentException("Endpoint host: invalid IPv6 address");
      try {
        if (!(InetAddress.getByName(unwrapped) instanceof Inet6Address))
          throw new IllegalArgumentException("Endpoint host: invalid IPv6 address");
      } catch (java.net.UnknownHostException e) {
        throw new IllegalArgumentException("Endpoint host: invalid IPv6 address", e);
      }
      return unwrapped; // Store the literal without URL brackets for socket connections.
    }
    // A dotted-quad IP must be numerically valid rather than an invalid
    // numeric hostname accidentally accepted by the DNS-character check.
    if (unwrapped.matches("[0-9.]+") && unwrapped.contains(".")) {
      String[] parts = unwrapped.split("\\.", -1);
      if (parts.length != 4)
        throw new IllegalArgumentException("Endpoint host: invalid IPv4 address");
      for (String part : parts) {
        try {
          if (part.isEmpty()
              || Integer.parseInt(part) > 255
              || (part.length() > 1 && part.startsWith("0")))
            throw new IllegalArgumentException("Endpoint host: invalid IPv4 address");
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Endpoint host: invalid IPv4 address", e);
        }
      }
    }
    // DNS labels may contain underscores for internal Docker/service discovery names.
    if (!unwrapped.matches(
        "[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?(?:\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*"))
      throw new IllegalArgumentException("Endpoint host: invalid hostname or IPv4 address");
    return unwrapped;
  }

  public static int port(String submitted) {
    try {
      int value = Integer.parseInt(submitted);
      if (value >= 1 && value <= 65535) return value;
    } catch (NumberFormatException ignored) {
    }
    throw new IllegalArgumentException("Endpoint port: use a number from 1 to 65535");
  }

  public static void port(int value) {
    if (value < 1 || value > 65535)
      throw new IllegalArgumentException("Endpoint port: use a number from 1 to 65535");
  }

  public static Platform platform(String submitted) {
    if (submitted == null)
      throw new IllegalArgumentException("Endpoint platform: choose UNIX or WINDOWS");
    try {
      Platform result = Platform.valueOf(submitted.toUpperCase(Locale.ROOT));
      if (result != Platform.UNIX && result != Platform.WINDOWS)
        throw new IllegalArgumentException("Endpoint platform: choose UNIX or WINDOWS");
      return result;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Endpoint platform: choose UNIX or WINDOWS");
    }
  }

  public static void validate(String name, String host, int port, Platform platform) {
    name(name);
    host(host);
    port(port);
    if (platform == null || (platform != Platform.UNIX && platform != Platform.WINDOWS))
      throw new IllegalArgumentException("Endpoint platform: choose UNIX or WINDOWS");
  }
}
