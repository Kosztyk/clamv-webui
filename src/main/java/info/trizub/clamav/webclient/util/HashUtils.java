package info.trizub.clamav.webclient.util;

import java.io.InputStream;
import java.security.MessageDigest;

public class HashUtils {

  public static String sha256(InputStream in) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[8192];
      int r;
      while ((r = in.read(buf)) > 0) {
        digest.update(buf, 0, r);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
