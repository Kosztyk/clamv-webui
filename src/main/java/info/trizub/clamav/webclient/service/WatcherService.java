package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ProcessedFile;
import info.trizub.clamav.webclient.model.WatchedDirectory;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WatcherService {
  private static final Logger log = LoggerFactory.getLogger(WatcherService.class);
  private final SettingsService settings;
  private final WatchedDirectoryRepository watchRepo;
  private final ProcessedFileRepository processedRepo;
  private final WatchFileSubmissionService submissions;
  private final Clock clock;
  private final AtomicLong lastRunMs = new AtomicLong(0);
  private final AtomicBoolean polling = new AtomicBoolean();

  @org.springframework.beans.factory.annotation.Autowired
  public WatcherService(
      SettingsService settings,
      WatchedDirectoryRepository watchRepo,
      ProcessedFileRepository processedRepo,
      WatchFileSubmissionService submissions) {
    this(settings, watchRepo, processedRepo, submissions, Clock.systemUTC());
  }

  WatcherService(
      SettingsService settings,
      WatchedDirectoryRepository watchRepo,
      ProcessedFileRepository processedRepo,
      WatchFileSubmissionService submissions,
      Clock clock) {
    this.settings = settings;
    this.watchRepo = watchRepo;
    this.processedRepo = processedRepo;
    this.submissions = submissions;
    this.clock = clock;
  }

  // Limit both filesystem work and marker loading on every one-second scheduler tick.
  // A cycle continues on subsequent ticks; watchPollSeconds applies between full cycles.
  static final int ENTRIES_PER_TICK = 256;
  static final int LOOKUP_BATCH_SIZE = 128;
  private static final long TICK_BUDGET_NANOS =
      java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750);
  private final Map<WatchKey, DirectoryCursor> cursors = new HashMap<>();

  /** A completed root has its own deadline; a huge neighbor never delays it. */
  private final Map<WatchKey, Long> nextEligibleMs = new HashMap<>();

  private int nextDirectoryIndex;
  private volatile boolean resetRequested;
  private volatile boolean destroyed;

  private record WatchKey(Long id, String path, Long endpointId) {}

  private record WatchTarget(WatchKey key, WatchedDirectory directory) {}

  private record FileSnapshot(Path path, String key, long modified, long size) {}

  private record Entry(Path path, BasicFileAttributes attributes) {}

  private static final class PollStats {
    long files, submitted, unchanged, skipped, failures;
  }

  @Scheduled(fixedDelay = 1000)
  public void poll() {
    if (destroyed || !polling.compareAndSet(false, true)) return;
    try {
      if (resetRequested) {
        resetCycle();
        resetRequested = false;
      }
      if (destroyed) return;
      if (!settings.watchEnabled()) {
        resetCycle();
        return;
      }
      long now = clock.millis();
      if (cursors.isEmpty()
          && nextEligibleMs.isEmpty()
          && now - lastRunMs.get() < Math.max(1, settings.watchPollSeconds()) * 1000L) return;
      List<WatchTarget> targets =
          watchRepo.findByEnabledTrue().stream()
              .filter(d -> d.getEndpoint() != null && d.getEndpoint().isEnabled())
              .map(
                  d ->
                      new WatchTarget(
                          new WatchKey(d.getId(), d.getPath(), d.getEndpoint().getId()), d))
              .sorted(Comparator.comparing(t -> String.valueOf(t.key().path())))
              .toList();
      if (targets.isEmpty()) {
        resetCycle();
        lastRunMs.set(now);
        return;
      }
      Set<WatchKey> enabled = new HashSet<>();
      for (WatchTarget target : targets) enabled.add(target.key());
      // Closing stale cursors also releases directory descriptors when settings change.
      for (Iterator<Map.Entry<WatchKey, DirectoryCursor>> it = cursors.entrySet().iterator();
          it.hasNext(); ) {
        var current = it.next();
        if (!enabled.contains(current.getKey())) {
          current.getValue().close();
          it.remove();
        }
      }
      nextEligibleMs.keySet().retainAll(enabled);
      long intervalMs = Math.multiplyExact(Math.max(1, settings.watchPollSeconds()), 1000L);
      // Initial scan is immediate; each subsequent root scan is independently due.
      lastRunMs.set(now);
      List<Path> roots = allowedRoots();
      int remaining = ENTRIES_PER_TICK;
      long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
      for (int visited = 0;
          visited < targets.size()
              && remaining > 0
              && System.nanoTime() < deadline
              && !resetRequested
              && !destroyed
              && !Thread.currentThread().isInterrupted();
          visited++) {
        WatchTarget target = targets.get(Math.floorMod(nextDirectoryIndex++, targets.size()));
        if (now < nextEligibleMs.getOrDefault(target.key(), Long.MIN_VALUE)) continue;
        DirectoryCursor cursor = cursors.get(target.key());
        if (cursor == null) {
          Path root = PathPolicy.normalize(target.directory().getPath());
          if (!Files.isDirectory(root) || !withinRoots(root, roots)) {
            log.warn("Watcher root is missing or outside allowed roots: {}", root);
            nextEligibleMs.put(target.key(), now + intervalMs);
            continue;
          }
          cursor = new DirectoryCursor(root);
          cursors.put(target.key(), cursor);
        } else if (!Files.isDirectory(cursor.root) || !withinRoots(cursor.root, roots)) {
          log.warn("Watcher root changed or became inaccessible: {}", cursor.root);
          cursor.close();
          cursors.remove(target.key());
          nextEligibleMs.put(target.key(), now + intervalMs);
          continue;
        }
        int examined = inspectBatch(cursor, target.directory(), roots, remaining, deadline);
        remaining -= examined;
        if (cursor.finished) {
          log.info(
              "Watcher cycle {}: files={}, submitted={}, unchanged={}, skipped={}, failures={}",
              cursor.root,
              cursor.stats.files,
              cursor.stats.submitted,
              cursor.stats.unchanged,
              cursor.stats.skipped,
              cursor.stats.failures);
          cursor.close();
          cursors.remove(target.key());
          nextEligibleMs.put(target.key(), now + intervalMs);
        }
      }
    } catch (Exception e) {
      log.warn(
          "Watcher poll failed ({}); uncommitted files remain eligible for a future cycle",
          e.getClass().getSimpleName());
      resetCycle();
    } finally {
      if (resetRequested || destroyed) {
        resetCycle();
        resetRequested = false;
      }
      polling.set(false);
      // A concurrent stop may have arrived just before the flag was released.
      if (resetRequested) requestReset();
    }
  }

  private List<Path> allowedRoots() {
    List<Path> roots = new ArrayList<>();
    for (Path configured : settings.allowedRoots()) {
      try {
        Path real = configured.toRealPath();
        if (Files.isDirectory(real)) roots.add(real);
        else log.warn("Watcher allowed root is not a directory: {}", configured);
      } catch (IOException e) {
        log.warn("Watcher allowed root is missing or inaccessible: {}", configured);
      }
    }
    return List.copyOf(roots);
  }

  private static boolean allowed(Path real, List<Path> roots) {
    return roots.stream().anyMatch(real::startsWith);
  }

  private static boolean withinRoots(Path path, List<Path> roots) {
    try {
      return allowed(path.toRealPath(), roots);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Depth-first, lazy directory iterator. Only the currently open ancestor streams are retained
   * between ticks (O(directory depth), independent of total files). Filesystem changes during
   * traversal are picked up by the next complete cycle.
   */
  private static final class DirectoryCursor implements AutoCloseable {
    final Path root;
    final PollStats stats = new PollStats();
    final Deque<DirectoryFrame> frames = new ArrayDeque<>();
    boolean started;
    boolean finished;

    DirectoryCursor(Path root) {
      this.root = root;
    }

    Entry next(List<Path> allowedRoots) {
      if (finished) return null;
      if (!started) {
        started = true;
        if (!open(root)) {
          finished = true;
          return null;
        }
      }
      while (!frames.isEmpty()) {
        DirectoryFrame frame = frames.peek();
        Path child;
        try {
          if (!frame.iterator.hasNext()) {
            closeTop();
            continue;
          }
          child = frame.iterator.next();
        } catch (DirectoryIteratorException | SecurityException e) {
          stats.failures++;
          log.warn(
              "Watcher cannot continue reading directory {} ({}); remaining entries retry next cycle",
              frame.path,
              e.getClass().getSimpleName());
          closeTop();
          continue;
        }
        BasicFileAttributes attrs;
        try {
          attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException e) {
          stats.failures++;
          log.warn(
              "Watcher cannot inspect {} ({}); it will be retried",
              child,
              e.getClass().getSimpleName());
          return new Entry(child, null);
        }
        if (attrs.isDirectory()) {
          if (withinRoots(child, allowedRoots)) open(child);
          else {
            stats.skipped++;
            log.warn("Watcher directory resolves outside allowed roots: {}", child);
          }
        }
        return new Entry(child, attrs);
      }
      finished = true;
      return null;
    }

    private boolean open(Path directory) {
      DirectoryStream<Path> stream = null;
      try {
        stream = Files.newDirectoryStream(directory);
        frames.push(new DirectoryFrame(directory, stream, stream.iterator()));
        return true;
      } catch (IOException | RuntimeException e) {
        stats.failures++;
        log.warn(
            "Watcher cannot open directory {} ({}); it will be retried",
            directory,
            e.getClass().getSimpleName());
        if (stream != null) {
          try {
            stream.close();
          } catch (IOException closeError) {
            log.warn("Watcher could not close failed stream {}", directory);
          }
        }
        return false;
      }
    }

    private void closeTop() {
      DirectoryFrame frame = frames.pop();
      try {
        frame.stream.close();
      } catch (IOException e) {
        log.warn(
            "Watcher could not close directory stream {} ({})",
            frame.path,
            e.getClass().getSimpleName());
      }
    }

    @Override
    public void close() {
      while (!frames.isEmpty()) closeTop();
      finished = true;
    }
  }

  private record DirectoryFrame(Path path, DirectoryStream<Path> stream, Iterator<Path> iterator) {}

  private int inspectBatch(
      DirectoryCursor cursor,
      WatchedDirectory directory,
      List<Path> roots,
      int limit,
      long deadline) {
    List<FileSnapshot> batch = new ArrayList<>(Math.min(limit, LOOKUP_BATCH_SIZE));
    int examined = 0;
    while (examined < limit
        && System.nanoTime() < deadline
        && !resetRequested
        && !destroyed
        && !Thread.currentThread().isInterrupted()) {
      Entry entry = cursor.next(roots);
      if (entry == null) break;
      examined++;
      if (entry.attributes() == null || entry.attributes().isDirectory()) continue;
      try {
        Path file = entry.path();
        Path real = file.toRealPath();
        if (!allowed(real, roots)) {
          cursor.stats.skipped++;
          log.warn("Watcher file resolves outside allowed roots: {}", file);
          continue;
        }
        BasicFileAttributes attrs =
            entry.attributes().isSymbolicLink()
                ? Files.readAttributes(real, BasicFileAttributes.class)
                : entry.attributes();
        if (!attrs.isRegularFile()) {
          if (entry.attributes().isSymbolicLink() && attrs.isDirectory()) {
            cursor.stats.skipped++;
            log.warn("Watcher does not follow directory symlinks: {}", file);
          }
          continue;
        }
        cursor.stats.files++;
        batch.add(
            new FileSnapshot(
                file,
                file.toAbsolutePath().normalize().toString(),
                attrs.lastModifiedTime().toMillis(),
                attrs.size()));
        if (batch.size() == LOOKUP_BATCH_SIZE) {
          submitBatch(batch, cursor.stats, directory);
          batch.clear();
        }
      } catch (IOException | RuntimeException e) {
        cursor.stats.failures++;
        log.warn(
            "Watcher cannot inspect {} ({}); it will be retried",
            entry.path(),
            e.getClass().getSimpleName());
      }
    }
    if (!batch.isEmpty()) submitBatch(batch, cursor.stats, directory);
    return examined;
  }

  private void submitBatch(
      List<FileSnapshot> snapshots, PollStats stats, WatchedDirectory directory) {
    if (resetRequested || destroyed || Thread.currentThread().isInterrupted()) return;
    Map<String, ProcessedFile> previous = new HashMap<>(snapshots.size());
    try {
      List<String> paths = snapshots.stream().map(FileSnapshot::key).toList();
      Long endpointId = directory.getEndpoint().getId();
      if (endpointId == null)
        throw new IllegalStateException("Watched endpoint has no persisted identity");
      for (ProcessedFile marker : processedRepo.findByPathInAndEndpointId(paths, endpointId))
        previous.put(marker.getPath(), marker);
    } catch (Exception e) {
      stats.failures += snapshots.size();
      log.warn(
          "Watcher marker lookup failed for {} files ({}); they will retry next cycle",
          snapshots.size(),
          e.getClass().getSimpleName());
      return;
    }
    for (FileSnapshot snapshot : snapshots) {
      if (resetRequested || destroyed || Thread.currentThread().isInterrupted()) return;
      try {
        ProcessedFile marker = previous.get(snapshot.key());
        if (marker != null
            && marker.getLastModified() == snapshot.modified()
            && marker.getSizeBytes() == snapshot.size()
            && marker.getLastScanJobId() != null
            && (marker.getRetryAfter() == null
                || marker.getRetryAfter().isAfter(clock.instant()))) {
          stats.unchanged++;
          continue;
        }
        ProcessedFile submitted =
            submissions.submit(
                snapshot.path(),
                directory.getEndpoint(),
                marker,
                snapshot.modified(),
                snapshot.size());
        previous.put(snapshot.key(), submitted);
        stats.submitted++;
      } catch (Exception e) {
        stats.failures++;
        log.warn(
            "Watcher submission failed for {} ({}); uncommitted work will retry next cycle, committed QUEUED jobs use queue reconciliation",
            snapshot.path(),
            e.getClass().getSimpleName());
      }
    }
  }

  private void resetCycle() {
    for (DirectoryCursor cursor : cursors.values()) cursor.close();
    cursors.clear();
    nextEligibleMs.clear();
    nextDirectoryIndex = 0;
  }

  /**
   * Keyset batches avoid skipping rows when earlier batches are deleted. Inaccessible paths are
   * retained.
   */
  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  public synchronized void pruneDeletedMarkers() {
    long after = 0, removed = 0;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        List<ProcessedFile> batch = processedRepo.findTop500ByIdGreaterThanOrderByIdAsc(after);
        if (batch.isEmpty()) break;
        List<Long> stale = new ArrayList<>();
        for (ProcessedFile marker : batch) {
          try {
            // Legacy global fingerprints have no endpoint and must not suppress
            // scans on newly assigned endpoints. Remove them incrementally.
            if (marker.getEndpointId() == null
                || Files.notExists(
                    PathPolicy.normalize(marker.getPath()), LinkOption.NOFOLLOW_LINKS))
              stale.add(marker.getId());
          } catch (RuntimeException e) {
            log.warn(
                "Watcher cannot check old marker {} ({})",
                marker.getPath(),
                e.getClass().getSimpleName());
          }
        }
        if (!stale.isEmpty()) {
          processedRepo.deleteAllByIdInBatch(stale);
          removed += stale.size();
        }
        after = batch.get(batch.size() - 1).getId();
      }
      if (removed > 0) log.info("Watcher pruned {} markers for deleted paths", removed);
    } catch (Exception e) {
      log.warn(
          "Watcher marker pruning deferred ({}); it will be retried", e.getClass().getSimpleName());
    }
  }

  /** Ask the owner of a running poll to close its cursors; do not close in-use streams. */
  private void requestReset() {
    resetRequested = true;
    if (polling.compareAndSet(false, true)) {
      try {
        resetCycle();
        resetRequested = false;
      } finally {
        polling.set(false);
      }
    }
  }

  /** Reset timing; the persisted enabled switch still controls polling. */
  public synchronized void start() {
    requestReset();
    lastRunMs.set(0);
    log.info("Watcher started (poll loop enabled by settings.watchEnabled=true).");
  }

  public synchronized void stop() {
    // The scheduling setting remains authoritative; release any open traversal cursors.
    requestReset();
    lastRunMs.set(clock.millis());
    log.info("Watcher stopped (poll loop will no-op unless enabled again).");
  }

  @jakarta.annotation.PreDestroy
  public void shutdown() {
    destroyed = true;
    requestReset();
  }

  public synchronized void startIfEnabled() {
    if (settings.watchEnabled()) start();
    else stop();
  }
}
