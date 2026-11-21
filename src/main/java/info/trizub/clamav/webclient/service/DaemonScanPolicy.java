package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.config.DaemonConfiguration;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.util.PathPolicy;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

/** Mounted daemon settings can restrict the default endpoint, never widen allowed roots. */
@Service
public class DaemonScanPolicy {
  private static final Logger log = LoggerFactory.getLogger(DaemonScanPolicy.class);
  private final ClamdConfigurationObserver observer;
  private final SettingsService settings;
  private final EndpointService endpoints;

  // Shared across directory jobs. A scan never creates one thread (or unbounded futures) per file.
  // Daemon threads do not keep a stopped Spring application alive.
  private static final int DIRECTORY_THREADS = 4;
  private static final int MAX_IN_FLIGHT = 16;
  private static final int MAX_REPORTED_FAILURES = 100;
  private static final int MAX_REPORTED_DETECTION_PATHS = 1024;
  private static final int MAX_SIGNATURES_PER_PATH = 16;
  private static final ExecutorService DIRECTORY_POOL =
      Executors.newFixedThreadPool(
          DIRECTORY_THREADS,
          task -> {
            Thread thread = new Thread(task, "clamd-directory-worker");
            thread.setDaemon(true);
            return thread;
          });

  public DaemonScanPolicy(ClamdConfigurationObserver observer, SettingsService settings) {
    this(observer, settings, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DaemonScanPolicy(
      ClamdConfigurationObserver observer, SettingsService settings, EndpointService endpoints) {
    this.observer = observer;
    this.settings = settings;
    this.endpoints = endpoints;
  }

  public boolean appliesTo(ClamdEndpoint endpoint) {
    if (!observer.discoveryEnabled() || endpoint == null) return false;
    if (endpoints == null) return "default".equals(endpoint.getName());
    ClamdEndpoint primary = endpoints.defaultEndpoint();
    return primary != null
        && (primary == endpoint
            || primary.getId() != null && primary.getId().equals(endpoint.getId()));
  }

  private DaemonConfiguration configuration(ClamdEndpoint endpoint) {
    if (!appliesTo(endpoint)) return null;
    DaemonConfiguration config = observer.effective();
    if (config == null)
      throw new IllegalArgumentException(
          "Daemon configuration is unavailable or invalid; scanning the default endpoint is paused.");
    return config;
  }

  public long uploadMaxBytes(ClamdEndpoint endpoint) {
    long limit = settings.uploadMaxBytes();
    DaemonConfiguration config = configuration(endpoint);
    if (config != null) {
      // Apply the stream ceiling to uploads, including optional path-scan fallback.
      if (config.streamMaxLength() != null) limit = Math.min(limit, config.streamMaxLength());
      limit = restrict(limit, config.maxScanSize());
      limit = restrict(limit, config.maxFileSize());
    }
    return limit;
  }

  public void requireUpload(long size, ClamdEndpoint endpoint) {
    if (size < 0 || size > uploadMaxBytes(endpoint))
      throw new IllegalArgumentException(
          "File exceeds the effective upload limit of " + uploadMaxBytes(endpoint) + " bytes.");
  }

  public boolean isExcluded(Path path, ClamdEndpoint endpoint) {
    DaemonConfiguration config = configuration(endpoint);
    if (config == null) return false;
    for (Path current = path.toAbsolutePath().normalize();
        current != null;
        current = current.getParent()) {
      String name = current.toString();
      for (var expression : config.exclusions()) {
        if (expression.matcher(name).find()
            || expression.matcher(name.endsWith("/") ? name : name + "/").find()) return true;
      }
    }
    return false;
  }

  public void requireNotExcluded(Path path, ClamdEndpoint endpoint) {
    if (isExcluded(path, endpoint))
      throw new IllegalArgumentException("Path is excluded by the daemon scan policy.");
  }

  public void requirePath(Path path, ClamdEndpoint endpoint) {
    requireNotExcluded(path, endpoint);
    DaemonConfiguration config = configuration(endpoint);
    if (config != null && Files.isRegularFile(path)) {
      try {
        long limit = restrict(restrict(Long.MAX_VALUE, config.maxScanSize()), config.maxFileSize());
        if (Files.size(path) > limit)
          throw new IllegalArgumentException(
              "File exceeds the daemon scan-size limit of " + limit + " bytes.");
      } catch (IOException ex) {
        throw new IllegalArgumentException("Cannot inspect scan file.", ex);
      }
    }
  }

  /**
   * One directory scan can contain a mixture of detections and unreadable files. A nonzero failure
   * count means that an OK scan result is NOT a clean verdict. The stored diagnostic is bounded to
   * avoid retaining millions of path strings in memory.
   */
  public record PathScanReport(
      ScanResult result,
      int scannedFiles,
      int failedFiles,
      List<String> failures,
      boolean directoryCommand) {
    public PathScanReport {
      failures = List.copyOf(failures);
    }

    public boolean partial() {
      return failedFiles != 0;
    }

    public String failureSummary() {
      if (!partial()) return null;
      StringBuilder message =
          new StringBuilder("Directory scan incomplete: ")
              .append(failedFiles)
              .append(" file(s) could not be scanned; ")
              .append(scannedFiles)
              .append(" file(s) scanned successfully.");
      for (String failure : failures) message.append('\n').append(failure);
      if (failedFiles > failures.size())
        message.append("\nAdditional failures omitted: ").append(failedFiles - failures.size());
      return message.toString();
    }
  }

  /** Compatibility entry point; callers that need partial diagnostics use scanPathReport. */
  public ScanResult scanPath(ClamdClient client, ClamdEndpoint endpoint, Path target)
      throws IOException {
    PathScanReport report = scanPathReport(client, endpoint, target);
    if (report.partial()) throw new IOException(report.failureSummary());
    return report.result();
  }

  /**
   * Fast path: a vetted tree with no exclusions, symlinks or skipped objects uses one native
   * MULTISCAN command. If clamd reports an error, retry as bounded file scans to identify the
   * failed file(s), preserving virus detections from successful files.
   *
   * <p>With exclusions or preflight concerns, scan only eligible regular files using a shared
   * four-worker pool. At most sixteen futures are outstanding per directory job.
   */
  public PathScanReport scanPathReport(ClamdClient client, ClamdEndpoint endpoint, Path target)
      throws IOException {
    requirePath(target, endpoint);
    if (!Files.isDirectory(target))
      return new PathScanReport(client.parallelScan(target), 1, 0, List.of(), false);

    // A non-default daemon can have a different configuration, but it may
    // still follow directory symlinks. Never send an unvetted tree command.
    if (!appliesTo(endpoint)) {
      DaemonConfiguration snapshot = observer.effective();
      List<Path> roots = new ArrayList<>(settings.allowedRoots());
      PathPolicy.ScanScope scope = PathPolicy.scanScope(roots);
      scope.requireAllowed(target);
      DirectoryPreflight preflight =
          preflightDirectoryCommand(target, scope, Long.MAX_VALUE, snapshot, roots);
      if (preflight.safe()) {
        verifyPolicyUnchanged(snapshot, roots);
        try {
          return new PathScanReport(
              client.parallelScan(target), preflight.files(), 0, List.of(), true);
        } catch (IOException ex) {
          log.warn(
              "Remote directory scan failed ({}); retrying safely enumerated file paths",
              ex.getClass().getSimpleName());
        }
      }
      return scanFilteredDirectory(
          client, target, scope, List.of(), Long.MAX_VALUE, snapshot, roots);
    }

    DaemonConfiguration initial = configuration(endpoint);
    List<Path> allowedRoots = new ArrayList<>(settings.allowedRoots());
    PathPolicy.ScanScope scope = PathPolicy.scanScope(allowedRoots);
    scope.requireAllowed(target);
    List<Pattern> excluded = initial.exclusions();
    long fileLimit =
        restrict(restrict(Long.MAX_VALUE, initial.maxScanSize()), initial.maxFileSize());

    DirectoryPreflight preflight =
        excluded.isEmpty()
            ? preflightDirectoryCommand(target, scope, fileLimit, initial, allowedRoots)
            : new DirectoryPreflight(false, 0);
    if (preflight.safe()) {
      verifyPolicyUnchanged(initial, allowedRoots);
      try {
        return new PathScanReport(
            client.parallelScan(target), preflight.files(), 0, List.of(), true);
      } catch (IOException commandFailed) {
        log.warn(
            "Native directory MULTISCAN did not complete ({}); retrying eligible files with bounded workers",
            commandFailed.getClass().getSimpleName());
        // A single clamd ERROR line can hide successful scans/detections. Fall back
        // to file-level scanning so that the job reports partial success and failures.
        // Never change the configured path authorization or exclusion policy.
      }
    }
    return scanFilteredDirectory(client, target, scope, excluded, fileLimit, initial, allowedRoots);
  }

  /** Quick preflight is required because clamd can otherwise follow symlinks that we skip. */
  private record DirectoryPreflight(boolean safe, int files) {}

  private DirectoryPreflight preflightDirectoryCommand(
      Path target,
      PathPolicy.ScanScope scope,
      long fileLimit,
      DaemonConfiguration initial,
      List<Path> roots)
      throws IOException {
    final boolean[] safe = {true};
    final int[] files = {0};
    Files.walkFileTree(
        target,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            verifyPolicyUnchanged(initial, roots);
            try {
              if (!dir.equals(scope.requireAllowed(dir))) safe[0] = false;
            } catch (IllegalArgumentException ex) {
              safe[0] = false;
            }
            return safe[0] ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()
                || attrs.isSymbolicLink()
                || attrs.size() > fileLimit
                || !Files.isReadable(file)) {
              safe[0] = false;
              return FileVisitResult.TERMINATE;
            }
            try {
              scope.requireAllowed(file);
            } catch (IllegalArgumentException ex) {
              safe[0] = false;
            }
            if (safe[0]) files[0]++;
            return safe[0] ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException ex) {
            safe[0] = false;
            return FileVisitResult.TERMINATE;
          }
        });
    return new DirectoryPreflight(safe[0] && files[0] > 0, files[0]);
  }

  private PathScanReport scanFilteredDirectory(
      ClamdClient client,
      Path target,
      PathPolicy.ScanScope scope,
      List<Pattern> exclusions,
      long fileLimit,
      DaemonConfiguration initial,
      List<Path> roots)
      throws IOException {
    DirectoryCollector collector = new DirectoryCollector(client, target);
    try {
      Files.walkFileTree(
          target,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              verifyPolicyUnchanged(initial, roots);
              if (matchesExclusion(dir, exclusions)) return FileVisitResult.SKIP_SUBTREE;
              // Do not rescan ancestor paths for each file; excluded parent dirs were pruned.
              try {
                scope.requireAllowed(dir);
              } catch (IllegalArgumentException ex) {
                collector.failed(dir, "Cannot safely traverse directory");
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              verifyPolicyUnchanged(initial, roots);
              if (!attrs.isRegularFile()
                  || attrs.isSymbolicLink()
                  || matchesExclusion(file, exclusions)) return FileVisitResult.CONTINUE;
              Path real;
              try {
                real =
                    scope.requireAllowed(file); // Re-resolve each leaf (symlink escape prevention).
                if (attrs.size() > fileLimit) {
                  collector.failed(file, "Exceeds configured daemon scan-size limit");
                  return FileVisitResult.CONTINUE;
                }
              } catch (IllegalArgumentException | SecurityException ex) {
                collector.failed(file, "Cannot safely access file");
                return FileVisitResult.CONTINUE;
              }
              collector.submit(real); // Interruption is fatal, not a per-file error.
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException ex) {
              collector.failed(file, "Unreadable file or directory");
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException ex) {
              if (ex != null) collector.failed(dir, "Cannot complete directory traversal");
              return FileVisitResult.CONTINUE;
            }
          });
      collector.drain();
      return collector.report();
    } finally {
      collector.cancelPending();
    }
  }

  private void verifyPolicyUnchanged(DaemonConfiguration snapshot, List<Path> roots) {
    // Secondary endpoints are not governed by the locally discovered daemon
    // configuration. Discovery can also be disabled, in which case effective()
    // deliberately returns null. Still recheck the shared allowed-root policy.
    if ((snapshot != null && !snapshot.samePolicyAs(observer.effective()))
        || !roots.equals(settings.allowedRoots()))
      throw new IllegalArgumentException(
          "Daemon policy or allowed roots changed during the scan; resubmit the job.");
  }

  /** Same path and trailing-slash matching used by isExcluded, without testing ancestors again. */
  private static boolean matchesExclusion(Path path, List<Pattern> exclusions) {
    if (exclusions.isEmpty()) return false;
    String name = path.toAbsolutePath().normalize().toString();
    for (Pattern expression : exclusions)
      if (expression.matcher(name).find()
          || expression.matcher(name.endsWith("/") ? name : name + "/").find()) return true;
    return false;
  }

  private record FileResult(Path file, ScanResult result, String problem) {}

  private static final class DirectoryCollector {
    private final ClamdClient client;
    private final Path root;
    private final CompletionService<FileResult> completed =
        new ExecutorCompletionService<>(DIRECTORY_POOL);
    private final Set<Future<FileResult>> outstanding = new HashSet<>();
    private final Map<String, Collection<String>> found = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int scanned;
    private int failed;

    DirectoryCollector(ClamdClient client, Path root) {
      this.client = client;
      this.root = root;
    }

    void submit(Path file) throws IOException {
      Future<FileResult> future =
          completed.submit(
              () -> {
                try {
                  return new FileResult(file, client.parallelScan(file), null);
                } catch (IOException | RuntimeException ex) {
                  return new FileResult(file, null, reason(ex));
                }
              });
      outstanding.add(future);
      if (outstanding.size() >= MAX_IN_FLIGHT) takeOne();
    }

    void drain() throws IOException {
      while (!outstanding.isEmpty()) takeOne();
    }

    private void takeOne() throws IOException {
      try {
        Future<FileResult> future = completed.take();
        outstanding.remove(future);
        FileResult outcome = future.get();
        if (outcome.problem() != null) {
          failed(outcome.file(), outcome.problem());
          return;
        }
        if (outcome.result() instanceof ScanResult.VirusFound viruses) {
          viruses
              .getFoundViruses()
              .forEach(
                  (name, signatures) -> {
                    if (name == null
                        || name.length() > 4096
                        || (!found.containsKey(name)
                            && found.size() >= MAX_REPORTED_DETECTION_PATHS)) {
                      failed(
                          outcome.file(), "Detection report limit exceeded; some entries omitted");
                      return;
                    }
                    Collection<String> recorded =
                        found.computeIfAbsent(name, k -> new LinkedHashSet<>());
                    if (signatures == null) return;
                    for (String signature : signatures) {
                      if (signature == null
                          || signature.length() > 256
                          || recorded.size() >= MAX_SIGNATURES_PER_PATH) {
                        failed(outcome.file(), "Detection signature limit exceeded");
                        break;
                      }
                      recorded.add(signature);
                    }
                  });
        } else if (!(outcome.result() instanceof ScanResult.OK)) {
          failed(outcome.file(), "Unknown daemon scan response");
          return;
        }
        scanned++;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Directory scan interrupted", ex);
      } catch (ExecutionException ex) {
        throw new IOException("Directory scan worker failed", ex.getCause());
      }
    }

    private static String reason(Exception ex) {
      // A bounded, non-sensitive error category: do not persist daemon exception text
      // containing internal server paths, hostnames or unrelated filesystem information.
      if (ex instanceof AccessDeniedException
          || ex.getMessage() != null
              && ex.getMessage().toLowerCase(Locale.ROOT).contains("permission denied"))
        return "Permission denied";
      if (ex instanceof NoSuchFileException) return "File disappeared during scan";
      if (ex instanceof java.net.SocketTimeoutException) return "ClamAV scan timed out";
      return "ClamAV could not scan file";
    }

    void failed(Path file, String reason) {
      failed++;
      if (failures.size() < MAX_REPORTED_FAILURES) {
        // Only show paths beneath the already-authorized scan root; do not leak outside paths.
        String relative;
        try {
          relative = root.relativize(file).toString();
        } catch (IllegalArgumentException ex) {
          relative = "[path unavailable]";
        }
        if (relative.length() > 240) relative = relative.substring(0, 240) + "...";
        failures.add(relative + ": " + reason);
      }
    }

    PathScanReport report() {
      if (scanned == 0 && failed == 0)
        throw new IllegalArgumentException(
            "No eligible regular files remain after daemon scan-policy filtering.");
      ScanResult result =
          found.isEmpty() ? ScanResult.OK.INSTANCE : new ScanResult.VirusFound(found);
      return new PathScanReport(result, scanned, failed, failures, false);
    }

    void cancelPending() {
      for (Future<FileResult> future : outstanding) future.cancel(true);
    }
  }

  private static long restrict(long limit, Long daemonLimit) {
    // MaxScanSize and MaxFileSize use 0 to disable their limit.
    return daemonLimit == null || daemonLimit == 0 ? limit : Math.min(limit, daemonLimit);
  }
}
