package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.ClamdConfigurationObserver;
import info.trizub.clamav.webclient.config.DaemonConfiguration;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.util.PathPolicy;
import org.springframework.stereotype.Service;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Mounted daemon settings can restrict the default endpoint, never widen allowed roots. */
@Service
public class DaemonScanPolicy {
    private final ClamdConfigurationObserver observer;
    private final SettingsService settings;

    public DaemonScanPolicy(ClamdConfigurationObserver observer, SettingsService settings) {
        this.observer = observer;
        this.settings = settings;
    }

    public boolean appliesTo(ClamdEndpoint endpoint) {
        return observer.discoveryEnabled() && endpoint != null && "default".equals(endpoint.getName());
    }

    private DaemonConfiguration configuration(ClamdEndpoint endpoint) {
        if (!appliesTo(endpoint)) return null;
        DaemonConfiguration config = observer.effective();
        if (config == null) throw new IllegalArgumentException("Daemon configuration is unavailable or invalid; scanning the default endpoint is paused.");
        return config;
    }

    public long uploadMaxBytes(ClamdEndpoint endpoint) {
        long limit = settings.uploadMaxBytes();
        DaemonConfiguration config = configuration(endpoint);
        if (config != null) {
            // Apply the stream ceiling to uploads, including optional path-scan fallback.
            if (config.streamMaxLength() != null) limit = Math.min(limit,config.streamMaxLength());
            limit = restrict(limit,config.maxScanSize());
            limit = restrict(limit,config.maxFileSize());
        }
        return limit;
    }

    public void requireUpload(long size, ClamdEndpoint endpoint) {
        if (size < 0 || size > uploadMaxBytes(endpoint))
            throw new IllegalArgumentException("File exceeds the effective upload limit of " + uploadMaxBytes(endpoint) + " bytes.");
    }

    public boolean isExcluded(Path path, ClamdEndpoint endpoint) {
        DaemonConfiguration config = configuration(endpoint);
        if (config == null) return false;
        for (Path current=path.toAbsolutePath().normalize();current!=null;current=current.getParent()) {
            String name = current.toString();
            for (var expression : config.exclusions()) {
                if (expression.matcher(name).find() || expression.matcher(name.endsWith("/") ? name : name + "/").find()) return true;
            }
        }
        return false;
    }

    public void requireNotExcluded(Path path, ClamdEndpoint endpoint) {
        if (isExcluded(path,endpoint)) throw new IllegalArgumentException("Path is excluded by the daemon scan policy.");
    }

    public void requirePath(Path path, ClamdEndpoint endpoint) {
        requireNotExcluded(path,endpoint);
        DaemonConfiguration config = configuration(endpoint);
        if (config != null && Files.isRegularFile(path)) {
            try {
                long limit = restrict(restrict(Long.MAX_VALUE,config.maxScanSize()),config.maxFileSize());
                if (Files.size(path)>limit) throw new IllegalArgumentException("File exceeds the daemon scan-size limit of " + limit + " bytes.");
            } catch (IOException ex) { throw new IllegalArgumentException("Cannot inspect scan file.",ex); }
        }
    }

    /** Filtering each regular file keeps refreshed exclusions effective during recursive scans. */
    public ScanResult scanPath(ClamdClient client, ClamdEndpoint endpoint, Path target) throws IOException {
        requirePath(target,endpoint);
        if (!appliesTo(endpoint) || !Files.isDirectory(target)) return client.parallelScan(target);
        Map<String,Collection<String>> found = new LinkedHashMap<>();
        AtomicInteger scanned = new AtomicInteger();
        Files.walkFileTree(target,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir,endpoint)) return FileVisitResult.SKIP_SUBTREE;
                PathPolicy.requireAllowed(dir,settings.allowedRoots());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || isExcluded(file,endpoint)) return FileVisitResult.CONTINUE;
                Path real = PathPolicy.requireAllowed(file,settings.allowedRoots());
                requirePath(real,endpoint);
                ScanResult result = client.parallelScan(real);
                scanned.incrementAndGet();
                if (result instanceof ScanResult.VirusFound viruses) {
                    viruses.getFoundViruses().forEach((name,signatures) ->
                        found.computeIfAbsent(name,k -> new LinkedHashSet<>()).addAll(signatures));
                } else if (!(result instanceof ScanResult.OK)) throw new IOException("Unknown daemon scan result.");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException ex) throws IOException { throw ex; }
        });
        if (scanned.get()==0) throw new IllegalArgumentException("No eligible regular files remain after daemon scan-policy filtering.");
        return found.isEmpty() ? ScanResult.OK.INSTANCE : new ScanResult.VirusFound(found);
    }

    private static long restrict(long limit, Long daemonLimit) {
        // MaxScanSize and MaxFileSize use 0 to disable their limit.
        return daemonLimit == null || daemonLimit==0 ? limit : Math.min(limit,daemonLimit);
    }
}
