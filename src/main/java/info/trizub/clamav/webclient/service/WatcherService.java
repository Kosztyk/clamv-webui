package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ProcessedFile;
import info.trizub.clamav.webclient.model.WatchedDirectory;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    public WatcherService(SettingsService settings, WatchedDirectoryRepository watchRepo,
                          ProcessedFileRepository processedRepo, WatchFileSubmissionService submissions) {
        this(settings, watchRepo, processedRepo, submissions, Clock.systemUTC());
    }
    WatcherService(SettingsService settings, WatchedDirectoryRepository watchRepo,
                   ProcessedFileRepository processedRepo, WatchFileSubmissionService submissions, Clock clock) {
        this.settings = settings; this.watchRepo = watchRepo; this.processedRepo = processedRepo;
        this.submissions = submissions; this.clock = clock;
    }

    // A full traversal may exceed the interval. Never overlap polls or abandon the tail of a tree.
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!settings.watchEnabled() || !polling.compareAndSet(false, true)) return;
        try {
            long now = clock.millis(), last = lastRunMs.get();
            if (now - last < settings.watchPollSeconds() * 1000L) return;
            if (!lastRunMs.compareAndSet(last, now)) return;
            List<WatchedDirectory> dirs = watchRepo.findByEnabledTrue();
            if (dirs.isEmpty()) return;
            List<Path> roots = allowedRoots();
            for (WatchedDirectory directory : dirs) {
                if (directory.getEndpoint() == null || !directory.getEndpoint().isEnabled()) continue;
                try { pollDirectory(directory, roots); }
                catch (Exception e) {
                    log.warn("Watcher could not inspect directory {} ({}); it will be retried", directory.getPath(), e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.warn("Watcher poll failed ({}); enabled directories will be retried", e.getClass().getSimpleName());
        } finally { polling.set(false); }
    }

    private List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (Path configured : settings.allowedRoots()) {
            try {
                Path real = configured.toRealPath();
                if (Files.isDirectory(real)) roots.add(real);
                else log.warn("Watcher allowed root is not a directory: {}", configured);
            } catch (IOException e) { log.warn("Watcher allowed root is missing or inaccessible: {}", configured); }
        }
        return List.copyOf(roots);
    }
    private static boolean allowed(Path real, List<Path> roots) { return roots.stream().anyMatch(real::startsWith); }
    private static final class PollStats { long files, submitted, unchanged, skipped, failures; }

    private void pollDirectory(WatchedDirectory directory, List<Path> roots) throws IOException {
        Path root = PathPolicy.normalize(directory.getPath());
        if (!Files.isDirectory(root)) {
            log.warn("Watcher directory is missing or inaccessible: {}", root); return;
        }
        if (!allowed(root.toRealPath(), roots)) { log.warn("Watch path not allowed by policy: {}", root); return; }
        String name = root.toString(), separator = root.getFileSystem().getSeparator();
        String prefix = name.endsWith(separator) ? name : name + separator;
        Map<String, ProcessedFile> previous = new HashMap<>();
        // StartingWith escapes SQL wildcard characters; the separator excludes sibling roots.
        for (ProcessedFile marker : processedRepo.findByPathStartingWith(prefix)) previous.put(marker.getPath(), marker);
        PollStats stats = new PollStats();
        // No file-count or depth cap. Directory symlinks are not followed.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                try {
                    if (!allowed(path.toRealPath(), roots)) {
                        stats.skipped++; log.warn("Watcher directory resolves outside allowed roots: {}", path);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                } catch (IOException e) {
                    stats.failures++; log.warn("Watcher cannot resolve directory {} ({}); it will be retried", path, e.getClass().getSimpleName());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Path real = file.toRealPath();
                    if (!allowed(real, roots)) {
                        stats.skipped++; log.warn("Watcher file resolves outside allowed roots: {}", file);
                        return FileVisitResult.CONTINUE;
                    }
                    BasicFileAttributes attributes = attrs.isSymbolicLink() ? Files.readAttributes(real, BasicFileAttributes.class) : attrs;
                    if (!attributes.isRegularFile()) {
                        if (attrs.isSymbolicLink() && attributes.isDirectory()) {
                            stats.skipped++; log.warn("Watcher does not follow directory symlinks: {}", file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    stats.files++;
                    String key = file.toAbsolutePath().normalize().toString();
                    ProcessedFile marker = previous.get(key);
                    long modified = attributes.lastModifiedTime().toMillis(), size = attributes.size();
                    if (marker != null && marker.getLastModified() == modified && marker.getSizeBytes() == size) {
                        stats.unchanged++; return FileVisitResult.CONTINUE;
                    }
                    ProcessedFile submitted = submissions.submit(file, directory.getEndpoint(), marker, modified, size);
                    previous.put(key, submitted); stats.submitted++;
                } catch (Exception e) {
                    stats.failures++;
                    log.warn("Watcher submission failed for {} ({}); uncommitted work will retry next poll, committed QUEUED jobs use queue reconciliation",
                        file, e.getClass().getSimpleName());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                stats.failures++; log.warn("Watcher cannot inspect {} ({}); it will be retried", file, e.getClass().getSimpleName());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException e) {
                if (e != null) {
                    stats.failures++; log.warn("Watcher could not finish directory {} ({}); it will be retried", path, e.getClass().getSimpleName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("Watcher poll {}: files={}, submitted={}, unchanged={}, skipped={}, failures={}",
            root, stats.files, stats.submitted, stats.unchanged, stats.skipped, stats.failures);
    }

    /** Keyset batches avoid skipping rows when earlier batches are deleted. Inaccessible paths are retained. */
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
                        if (Files.notExists(PathPolicy.normalize(marker.getPath()), LinkOption.NOFOLLOW_LINKS)) stale.add(marker.getId());
                    } catch (RuntimeException e) { log.warn("Watcher cannot check old marker {} ({})", marker.getPath(), e.getClass().getSimpleName()); }
                }
                if (!stale.isEmpty()) { processedRepo.deleteAllByIdInBatch(stale); removed += stale.size(); }
                after = batch.get(batch.size() - 1).getId();
            }
            if (removed > 0) log.info("Watcher pruned {} markers for deleted paths", removed);
        } catch (Exception e) { log.warn("Watcher marker pruning deferred ({}); it will be retried", e.getClass().getSimpleName()); }
    }

    /** Reset timing; the persisted enabled switch still controls polling. */
    public synchronized void start() {
        lastRunMs.set(0); log.info("Watcher started (poll loop enabled by settings.watchEnabled=true).");
    }
    public synchronized void stop() {
        lastRunMs.set(clock.millis()); log.info("Watcher stopped (poll loop will no-op unless enabled again).");
    }
    public synchronized void startIfEnabled() { if (settings.watchEnabled()) start(); else stop(); }
}
