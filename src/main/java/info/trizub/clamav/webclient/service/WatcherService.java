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

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Service
public class WatcherService {

    private static final Logger log = LoggerFactory.getLogger(WatcherService.class);

    private final SettingsService settings;
    private final WatchedDirectoryRepository watchRepo;
    private final ProcessedFileRepository processedRepo;
    private final ScanJobService scanJobService;

    private final AtomicLong lastRunMs = new AtomicLong(0);

    public WatcherService(SettingsService settings,
                          WatchedDirectoryRepository watchRepo,
                          ProcessedFileRepository processedRepo,
                          ScanJobService scanJobService) {
        this.settings = settings;
        this.watchRepo = watchRepo;
        this.processedRepo = processedRepo;
        this.scanJobService = scanJobService;
    }

    @Scheduled(fixedDelay = 30000)
    public void poll() {
        if (!settings.watchEnabled()) return;

        long now = System.currentTimeMillis();
        long interval = settings.watchPollSeconds() * 1000L;
        long last = lastRunMs.get();
        if (now - last < interval) return;
        if (!lastRunMs.compareAndSet(last, now)) return;

        List<WatchedDirectory> dirs = watchRepo.findByEnabledTrue();
        if (dirs.isEmpty()) return;

        for (WatchedDirectory wd : dirs) {
            try {
                Path root = PathPolicy.normalize(wd.getPath());
                if (!Files.isDirectory(root)) continue;
                if (!PathPolicy.isUnderAllowedRoots(root, settings.allowedRoots())) {
                    log.warn("Watch path not allowed by policy: {}", root);
                    continue;
                }

                try (Stream<Path> stream = Files.walk(root, 5)) {
                    stream
                        .filter(Files::isRegularFile)
                        .limit(1000)
                        .forEach(p -> {
                            try {
                                long lm = Files.getLastModifiedTime(p).toMillis();
                                long sz = Files.size(p);
                                var existing = processedRepo.findByPath(p.toAbsolutePath().normalize().toString());
                                if (existing.isPresent()) {
                                    ProcessedFile pf = existing.get();
                                    if (pf.getLastModified() == lm && pf.getSizeBytes() == sz) return;
                                    pf.setLastModified(lm);
                                    pf.setSizeBytes(sz);
                                    processedRepo.save(pf);
                                } else {
                                    processedRepo.save(new ProcessedFile(p.toAbsolutePath().normalize().toString(), lm, sz, null));
                                }
                                scanJobService.createWatchFileJob(p, wd.getEndpoint(), "watcher");
                            } catch (Exception ignored) {}
                        });
                }
            } catch (Exception e) {
                log.warn("Watcher error: {}", e.getMessage());
            }
        }
    }

    
/**
 * Reset internal timing so the next scheduled poll can run immediately.
 * This does not toggle the persisted setting; it only affects in-process behavior.
 */
public synchronized void start() {
    lastRunMs.set(0);
    log.info("Watcher started (poll loop enabled by settings.watchEnabled=true).");
}

/**
 * Prevent immediate re-polling. The scheduled poll() will still run, but will no-op if disabled.
 * This does not toggle the persisted setting; it only affects in-process behavior.
 */
public synchronized void stop() {
    lastRunMs.set(System.currentTimeMillis());
    log.info("Watcher stopped (poll loop will no-op unless enabled again).");
}

/**
     * Starts (or stops) the directory watcher according to current settings.
     * Safe to call at startup.
     */
    public synchronized void startIfEnabled() {
        if (!settings.watchEnabled()) {
            stop();
            return;
        }
        start();
    }
}
