package info.trizub.clamav.webclient.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** ClamD scan paths must exist and resolve within a configured, existing root. */
public final class PathPolicy {
    private PathPolicy() { }

    public static Path normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Scan path is required");
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    /** Resolve filesystem symlinks before any containment check. */
    public static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Scan path is missing or inaccessible: " + path, e);
        }
    }

    public static boolean isUnderAllowedRoots(Path requested, List<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) return false;
        Path realRequested = canonical(requested);
        for (Path root : allowedRoots) {
            try {
                Path realRoot = root.toRealPath();
                if (Files.isDirectory(realRoot) && realRequested.startsWith(realRoot)) return true;
            } catch (IOException ignored) {
                // Missing/unreadable roots must not expand access.
            }
        }
        return false;
    }

    /** Resolve canonical scan target and enforce current policy. */
    public static Path requireAllowed(Path requested, List<Path> roots) {
        Path resolved = canonical(requested);
        if (!isUnderAllowedRoots(resolved, roots)) {
            throw new IllegalArgumentException("Path is not allowed by scan policy");
        }
        return resolved;
    }
}
