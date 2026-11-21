package info.trizub.clamav.webclient.security;

/** Pure arithmetic for pre-multipart Content-Length admission checks. */
public final class UploadRequestLimits {
  private UploadRequestLimits() {}

  // Reserve enough room for multipart boundaries, file metadata, CSRF and form fields.
  // This is an optimization hint, NOT the per-file security boundary.
  private static final long MULTIPART_OVERHEAD = 64L * 1024;

  public static long ceiling(long requestMax, long effectiveFileMax, int declaredFiles) {
    if (requestMax <= 0 || effectiveFileMax <= 0 || declaredFiles < 1)
      throw new IllegalArgumentException("Invalid upload policy");
    long perFile = saturatedAdd(effectiveFileMax, MULTIPART_OVERHEAD);
    long aggregate = saturatedAdd(saturatedMultiply(perFile, declaredFiles), MULTIPART_OVERHEAD);
    return Math.min(requestMax, aggregate);
  }

  private static long saturatedAdd(long a, long b) {
    return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
  }

  private static long saturatedMultiply(long a, int b) {
    return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
  }
}
