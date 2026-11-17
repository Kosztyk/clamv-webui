package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.util.PathPolicy;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QuarantineService {
  public static final String UPLOAD_FAILURE =
      "Quarantine failed. Upload cleanup will attempt to remove the infected uploaded copy; inspect the server logs.";
  public static final String SOURCE_FAILURE =
      "Quarantine failed for one or more infected source files. Upload cleanup does not delete source files; inspect the server logs.";

  public record Result(Path path, boolean failed) {}

  private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

  /** Do not swallow a failed rollback after a post-move identity mismatch. */
  public static final class UnrestoredIdentityMismatch extends IllegalStateException {
    private UnrestoredIdentityMismatch(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final SettingsService settings;

  public QuarantineService(SettingsService settings) {
    this.settings = settings;
  }

  public Path quarantine(Path file) {
    return attempt(file, null).path();
  }

  /** Uploaded copies use the job ID; source scans use a fresh ID for each quarantined file. */
  public Result attempt(Path file, String jobId) {
    return move(file, jobId, null, null, false, null, null);
  }

  /**
   * Quarantine a source file only when the daemon-reported path is within BOTH the current
   * configured roots AND the original, authorized scan target. This is intentionally separate from
   * upload quarantine: a ClamD filename is untrusted input, never an instruction to move an
   * arbitrary host file.
   */
  public Result attemptSource(
      Path detected, Path scanTarget, List<Path> allowedRoots, boolean scannedDirectory) {
    // A null policy must never downgrade a source move into the unscoped upload path.
    try {
      authorizeSource(detected, scanTarget, allowedRoots, scannedDirectory);
      QuarantineFileIdentity.Evidence evidence = QuarantineFileIdentity.capture(detected);
      return move(
          detected,
          null,
          scanTarget,
          allowedRoots == null ? List.of() : allowedRoots,
          scannedDirectory,
          null,
          evidence);
    } catch (Exception ex) {
      log.warn(
          "Unjournaled source quarantine identity check failed ({})",
          ex.getClass().getSimpleName());
      return new Result(null, true);
    }
  }

  /** Throw before writing the journal if a daemon-reported path is not authorized. */
  public void authorizeSource(
      Path detected, Path scanTarget, List<Path> roots, boolean wasDirectory) {
    validateSource(detected, scanTarget, roots == null ? List.of() : roots, wasDirectory);
  }

  public Result attemptSourceWithArtifactId(
      Path detected,
      Path scanTarget,
      List<Path> roots,
      boolean wasDirectory,
      String artifactId,
      Path reservedDestination) {
    // Legacy six-argument API is intentionally fail-closed: the caller must
    // supply the content evidence committed in the journal.
    throw new IllegalArgumentException("Journaled source quarantine requires content evidence");
  }

  public Result attemptSourceWithArtifactId(
      Path detected,
      Path scanTarget,
      List<Path> roots,
      boolean wasDirectory,
      String artifactId,
      Path reservedDestination,
      QuarantineFileIdentity.Evidence evidence) {
    if (artifactId == null
        || !artifactId.matches("[0-9a-f]{32}")
        || reservedDestination == null
        || !reservedDestination.getFileName().toString().equals(artifactId + ".bin")
        || evidence == null)
      throw new IllegalArgumentException("Invalid journaled source destination or identity");
    return move(
        detected,
        artifactId,
        scanTarget,
        roots == null ? List.of() : roots,
        wasDirectory,
        reservedDestination,
        evidence);
  }

  /** Upload quarantine uses the same persisted, deterministic destination as source quarantine. */
  public Result attemptUploadWithArtifactId(Path uploaded, String jobId, Path reservedDestination) {
    // Kept only as a fail-closed compatibility boundary; callers must supply
    // the identity fingerprint committed in the journal before any move.
    throw new IllegalArgumentException("Journaled upload quarantine requires content evidence");
  }

  public Result attemptUploadWithArtifactId(
      Path uploaded,
      String jobId,
      Path reservedDestination,
      QuarantineFileIdentity.Evidence evidence) {
    if (jobId == null
        || !jobId.matches("[0-9a-f]{32}")
        || reservedDestination == null
        || !reservedDestination.getFileName().toString().equals(jobId + ".bin")
        || evidence == null)
      throw new IllegalArgumentException("Invalid upload journal identity or destination");
    return move(uploaded, jobId, null, null, false, reservedDestination, evidence);
  }

  private Result move(
      Path file,
      String jobId,
      Path sourceTarget,
      List<Path> allowedRoots,
      boolean scannedDirectory,
      Path reservedDestination,
      QuarantineFileIdentity.Evidence expectedIdentity) {
    if (!settings.quarantineEnabled()) return new Result(null, false);
    Path moved = null;
    try {
      if (allowedRoots != null) validateSource(file, sourceTarget, allowedRoots, scannedDirectory);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("Only regular files can be quarantined");
      Path qdir =
          reservedDestination == null ? settings.quarantineDir() : reservedDestination.getParent();
      // Quarantine configuration must not silently redirect a journaled move.
      if (reservedDestination != null
          && !qdir.toAbsolutePath()
              .normalize()
              .equals(settings.quarantineDir().toAbsolutePath().normalize()))
        throw new IllegalArgumentException("Quarantine location changed after journal reservation");
      if (qdir.toAbsolutePath()
          .normalize()
          .startsWith(settings.uploadDir().toAbsolutePath().normalize()))
        throw new IllegalArgumentException("Quarantine must be outside the upload directory");
      info.trizub.clamav.webclient.util.PrivateFiles.directory(qdir);
      // Recheck immediately before the move; authorization never depends
      // on paths supplied by ClamD alone, even if the filesystem changed.
      if (allowedRoots != null) validateSource(file, sourceTarget, allowedRoots, scannedDirectory);
      if (expectedIdentity != null && !QuarantineFileIdentity.matches(file, expectedIdentity))
        throw new IllegalArgumentException(
            "Source content differs from recorded quarantine evidence");
      String id =
          jobId != null && jobId.matches("[0-9a-f]{32}")
              ? jobId
              : UUID.randomUUID().toString().replace("-", "");
      Path destination =
          reservedDestination == null ? qdir.resolve(id + ".bin") : reservedDestination;
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("Quarantine destination already exists");
      moved = Files.move(file, destination);
      // Detect a swap between the final source read and Files.move. If the
      // content differs, restore without overwriting any newer source file.
      if (expectedIdentity != null && !QuarantineFileIdentity.matches(moved, expectedIdentity)) {
        try {
          Files.move(moved, file);
          moved = null;
        } catch (Exception restoreError) {
          log.error(
              "Identity mismatch after move; could not restore original location {}",
              file,
              restoreError);
          throw new UnrestoredIdentityMismatch(
              "Quarantine identity mismatch and safe rollback failed", restoreError);
        }
        throw new IllegalArgumentException("Source content changed during quarantine move");
      }
      info.trizub.clamav.webclient.util.PrivateFiles.restrict(moved, "rw-------");
      return new Result(moved, false);
    } catch (Exception ex) {
      if (ex instanceof UnrestoredIdentityMismatch mismatch) throw mismatch;
      if (allowedRoots != null)
        log.warn("Source quarantine rejected or failed ({})", ex.getClass().getSimpleName());
      else log.warn("Quarantine operation failed for {}", file, ex);
      // Preserve evidence if the move succeeded but permission hardening failed afterward.
      return new Result(moved, true);
    }
  }

  private static void validateSource(
      Path detected, Path originalTarget, List<Path> roots, boolean scannedDirectory) {
    if (detected == null
        || originalTarget == null
        || !detected.isAbsolute()
        || !originalTarget.isAbsolute()
        || !detected.equals(detected.normalize())
        || !originalTarget.equals(originalTarget.normalize()))
      throw new IllegalArgumentException("Source quarantine requires absolute canonical paths");
    // Scope is rebuilt from current settings for each candidate. Symlinks,
    // parent aliases and roots that were removed/repointed must fail closed.
    Path scanTarget = PathPolicy.requireAllowed(originalTarget, roots);
    Path candidate = PathPolicy.requireAllowed(detected, roots);
    if (!originalTarget.equals(scanTarget) || !detected.equals(candidate))
      throw new IllegalArgumentException("Source quarantine path is not canonical");
    boolean directory = Files.isDirectory(scanTarget, LinkOption.NOFOLLOW_LINKS);
    if (directory != scannedDirectory)
      throw new IllegalArgumentException("Original scan target changed type after scan");
    if (directory
        ? !candidate.startsWith(scanTarget) || candidate.equals(scanTarget)
        : !candidate.equals(scanTarget))
      throw new IllegalArgumentException("Detected file is outside the original scan target");
    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Detected source is not a regular file");
  }

  /** Failure marker may follow a partial directory-scan diagnostic on another line. */
  public static boolean failureRecorded(String message) {
    if (message == null) return false;
    for (String line : message.split("\\R")) {
      if (UPLOAD_FAILURE.equals(line) || SOURCE_FAILURE.equals(line)) return true;
    }
    return false;
  }
}
