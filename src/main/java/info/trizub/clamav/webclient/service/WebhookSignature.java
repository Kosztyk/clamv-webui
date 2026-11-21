package info.trizub.clamav.webclient.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over the exact UTF-8 HTTP request body. */
public final class WebhookSignature {
  private WebhookSignature() {}

  public static boolean validSecret(String secret) {
    return secret != null
        && !secret.isBlank()
        && secret.getBytes(StandardCharsets.UTF_8).length >= 32
        && secret.getBytes(StandardCharsets.UTF_8).length <= 4096;
  }

  public static String sign(String secret, String body) {
    if (!validSecret(secret))
      throw new IllegalArgumentException("Webhook signing secret must be at least 32 UTF-8 bytes");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256="
          + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
    }
  }
}
