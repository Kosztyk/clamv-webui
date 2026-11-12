package info.trizub.clamav.webclient.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Check trusted root prefixes before filesystem resolution, then reject symlink escapes. */
public final class PathPolicy {
    public static final String DENIED_MESSAGE = "Path is not allowed or does not exist";
    private record Root(Path configured, Path canonical) { }
    private PathPolicy() { }

    public static Path normalize(String raw) {
        if (raw == null || raw.isBlank()) throw denied();
        try { return Paths.get(raw).toAbsolutePath().normalize(); }
        catch (InvalidPathException | SecurityException e) { throw denied(); }
    }

    /** Filesystem resolution for an already screened scan target, or an administrator's root selection. */
    public static Path canonical(Path path) {
        if (path == null) throw denied();
        try { return path.toRealPath(); }
        catch (IOException | SecurityException e) { throw denied(); }
    }

    public static boolean isUnderAllowedRoots(Path requested, List<Path> allowedRoots) {
        try { requireAllowed(requested, allowedRoots); return true; }
        catch (IllegalArgumentException denied) { return false; }
    }

    /** Only deployment/admin-controlled roots are inspected before screening a submitted target. */
    private static List<Root> roots(List<Path> configured) {
        List<Root> roots = new ArrayList<>();
        if (configured == null) return roots;
        for (Path root : configured) {
            if (root == null) continue;
            try {
                Path spelling = root.toAbsolutePath().normalize();
                Path real = spelling.toRealPath();
                if (Files.isDirectory(real)) roots.add(new Root(spelling, real));
            } catch (IOException | SecurityException ignored) {
                // Missing/unreadable configured roots never expand access.
            }
        }
        return roots;
    }

    public static Path requireAllowed(Path requested, List<Path> configuredRoots) {
        if (requested == null) throw denied();
        Path lexical;
        try { lexical = requested.toAbsolutePath().normalize(); }
        catch (SecurityException e) { throw denied(); }
        List<Root> roots = roots(configuredRoots);
        // Configured root aliases are trusted spellings. Arbitrary outside aliases are not resolved.
        if (roots.stream().noneMatch(root -> lexical.startsWith(root.canonical()) || lexical.startsWith(root.configured())))
            throw denied();
        Path resolved = canonical(lexical);
        if (roots.stream().noneMatch(root -> resolved.startsWith(root.canonical()))) throw denied();
        return resolved;
    }

    /** Keep a prior authorization result pinned; only configured roots are resolved again. */
    public static Path requireCanonicalAllowed(Path resolved, List<Path> configuredRoots) {
        if (resolved == null || !resolved.isAbsolute() || !resolved.normalize().equals(resolved)
                || roots(configuredRoots).stream().noneMatch(root -> resolved.startsWith(root.canonical())))
            throw denied();
        return resolved;
    }

    private static IllegalArgumentException denied() { return new IllegalArgumentException(DENIED_MESSAGE); }
}
