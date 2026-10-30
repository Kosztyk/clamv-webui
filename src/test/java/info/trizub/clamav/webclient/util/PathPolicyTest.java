package info.trizub.clamav.webclient.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PathPolicyTest {
    @TempDir Path temp;

    @Test void acceptsCanonicalFileInsideAllowedRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path file = Files.writeString(root.resolve("sample.txt"), "clean");
        assertThat(PathPolicy.requireAllowed(file, List.of(root))).isEqualTo(file.toRealPath());
    }

    @Test void deniesMissingAndUnallowedTargets() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path outside = Files.writeString(temp.resolve("outside"), "x");
        assertThatThrownBy(() -> PathPolicy.requireAllowed(outside, List.of(root)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathPolicy.requireAllowed(root.resolve("missing"), List.of(root)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void symlinkCannotEscapeAllowedRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
        Path link = root.resolve("escape.txt");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException e) { assumeTrue(false, "no symlink support"); }
        assertThat(PathPolicy.isUnderAllowedRoots(link, List.of(root))).isFalse();
    }
}
