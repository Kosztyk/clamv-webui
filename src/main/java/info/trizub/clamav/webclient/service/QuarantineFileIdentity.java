package info.trizub.clamav.webclient.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Durable content evidence for a file before any quarantine journal reservation. Reads in bounded
 * chunks and rejects changes during evidence collection. A path alone (or size/mtime alone) is
 * never sufficient to authorize restart moves.
 */
public final class QuarantineFileIdentity {
  public record Evidence(long sizeBytes, String sha256) {
    public Evidence {
      if (sizeBytes < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid quarantine content evidence");
    }
  }

  private QuarantineFileIdentity() {}

  public static Evidence capture(Path path) throws IOException {
    BasicFileAttributes before = attributes(path);
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
    byte[] buffer = new byte[64 * 1024];
    long size = 0;
    // NOFOLLOW_LINKS prevents opening a swapped-in symlink to an unexpected file.
    try (InputStream in =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        digest.update(buffer, 0, n);
        size += n;
      }
    }
    BasicFileAttributes after = attributes(path);
    if (before.size() != size
        || after.size() != size
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !Objects.equals(before.fileKey(), after.fileKey()))
      throw new IOException("Source changed while capturing quarantine evidence");
    return new Evidence(size, HexFormat.of().formatHex(digest.digest()));
  }

  public static boolean matches(Path path, Evidence evidence) throws IOException {
    return evidence != null && evidence.equals(capture(path));
  }

  private static BasicFileAttributes attributes(Path path) throws IOException {
    BasicFileAttributes attrs =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile())
      throw new IOException("Only regular files can have quarantine content evidence");
    return attrs;
  }
}
