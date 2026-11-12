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
    @Test void existingAndMissingOutsidePathsHaveExactlyTheSameSafeError() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path existing = Files.writeString(temp.resolve("private-key"), "private");
        Path missing = temp.resolve("missing-private-key");
        for (Path target : List.of(existing, missing, root.resolve("missing"))) {
            assertThatThrownBy(() -> PathPolicy.requireAllowed(target, List.of(root)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(PathPolicy.DENIED_MESSAGE).hasNoCause();
        }
    }
    @Test void outsideTargetIsNeverResolvedOrProbedBeforeLexicalRejection() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path target = org.mockito.Mockito.mock(Path.class);
        org.mockito.Mockito.when(target.toAbsolutePath()).thenReturn(target);
        org.mockito.Mockito.when(target.normalize()).thenReturn(target);
        assertThatThrownBy(() -> PathPolicy.requireAllowed(target, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE);
        org.mockito.Mockito.verify(target, org.mockito.Mockito.never()).toRealPath(org.mockito.ArgumentMatchers.any(java.nio.file.LinkOption[].class));
        org.mockito.Mockito.verify(target, org.mockito.Mockito.never()).getFileSystem();
    }
    @Test void traversalAndSiblingPrefixesCannotPassLexicalScreening() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path sibling = Files.createDirectory(temp.resolve("allowed-sibling"));
        Path file = Files.writeString(sibling.resolve("file"), "private");
        for (Path target : List.of(file, root.resolve("../allowed-sibling/file")))
            assertThatThrownBy(() -> PathPolicy.requireAllowed(target, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE);
    }
    @Test void existingDanglingAndLoopingSymlinkEscapesHaveOneError() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path outside = Files.writeString(temp.resolve("private"), "private");
        Path existing = Files.createSymbolicLink(root.resolve("escape"), outside);
        Path dangling = Files.createSymbolicLink(root.resolve("dangling"), temp.resolve("not-present"));
        Path loop = Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"));
        for (Path target : List.of(existing, dangling, loop)) {
            assertThatThrownBy(() -> PathPolicy.requireAllowed(target, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE).hasNoCause();
            assertThat(PathPolicy.isUnderAllowedRoots(target, List.of(root))).isFalse();
        }
    }
    @Test void configuredRootAliasAndItsCanonicalSpellingBothWork() throws Exception {
        Path root = Files.createDirectory(temp.resolve("actual-root"));
        Path file = Files.writeString(root.resolve("file"), "clean");
        Path alias = Files.createSymbolicLink(temp.resolve("configured-alias"), root);
        assertThat(PathPolicy.requireAllowed(alias.resolve("file"), List.of(alias))).isEqualTo(file.toRealPath());
        assertThat(PathPolicy.requireAllowed(file, List.of(alias))).isEqualTo(file.toRealPath());
    }
    @Test void arbitraryOutsideAliasIsDeniedEvenWhenItPointsIntoAllowedRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path file = Files.writeString(root.resolve("file"), "clean");
        Path alias = Files.createSymbolicLink(temp.resolve("untrusted-alias"), file);
        assertThatThrownBy(() -> PathPolicy.requireAllowed(alias, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE);
    }
    @Test void inRootSymlinksResolveAndRelativeInputIsNormalized() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path file = Files.writeString(root.resolve("file"), "clean");
        Path alias = Files.createSymbolicLink(root.resolve("alias"), file);
        assertThat(PathPolicy.requireAllowed(alias, List.of(root))).isEqualTo(file.toRealPath());
        Path relative = Path.of("").toAbsolutePath().relativize(file);
        assertThat(PathPolicy.requireAllowed(relative, List.of(root))).isEqualTo(file.toRealPath());
    }
    @Test void noValidConfiguredRootAndInvalidInputsFailClosedWithoutPathDetails() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path file = Files.writeString(root.resolve("file"), "clean");
        for (List<Path> roots : List.of(List.<Path>of(), List.of(temp.resolve("not-present")), List.of(file)))
            assertThatThrownBy(() -> PathPolicy.requireAllowed(file, roots)).hasMessage(PathPolicy.DENIED_MESSAGE);
        assertThatThrownBy(() -> PathPolicy.requireAllowed(null, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE);
        for (String input : new String[]{null, "", " ", "private\0name"})
            assertThatThrownBy(() -> PathPolicy.normalize(input)).hasMessage(PathPolicy.DENIED_MESSAGE).hasNoCause();
        assertThat(PathPolicy.isUnderAllowedRoots(root.resolve("missing"), List.of(root))).isFalse();
    }
    @Test void pinnedCanonicalResultUsesTheSameErrorAndDoesNotResolveTheTargetAgain() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path file = Files.writeString(root.resolve("file"), "clean").toRealPath();
        Files.delete(file);
        assertThat(PathPolicy.requireCanonicalAllowed(file, List.of(root))).isEqualTo(file);
        for (Path target : List.of(temp.resolve("outside"), Path.of("relative"), root.resolve("../outside")))
            assertThatThrownBy(() -> PathPolicy.requireCanonicalAllowed(target, List.of(root))).hasMessage(PathPolicy.DENIED_MESSAGE);
    }
}
