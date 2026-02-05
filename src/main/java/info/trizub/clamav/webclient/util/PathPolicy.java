package info.trizub.clamav.webclient.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class PathPolicy {

    public static Path normalize(String p) {
        return Paths.get(p).toAbsolutePath().normalize();
    }

    public static boolean isUnderAllowedRoots(Path requested, List<Path> allowedRoots) {
        for (Path root : allowedRoots) {
            if (requested.startsWith(root)) return true;
        }
        return false;
    }
}
