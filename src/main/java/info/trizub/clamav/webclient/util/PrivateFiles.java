package info.trizub.clamav.webclient.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/** Owner-only permissions on POSIX filesystems; other platforms retain their native ACL policy. */
public final class PrivateFiles {
    private PrivateFiles() { }
    public static Path directory(Path path) throws IOException {
        Files.createDirectories(path, attributes(path, "rwx------"));
        if (Files.isSymbolicLink(path)) throw new IOException("Private storage directory must not be a symbolic link");
        restrict(path, "rwx------");return path;
    }
    public static void create(Path path) throws IOException {
        Files.createFile(path, attributes(path.getParent(), "rw-------"));
        try {restrict(path, "rw-------");}
        catch(IOException ex) {Files.deleteIfExists(path);throw ex;}
    }
    public static void restrict(Path path, String permissions) throws IOException {
        var view=Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) view.setPermissions(PosixFilePermissions.fromString(permissions));
    }
    private static java.nio.file.attribute.FileAttribute<?>[] attributes(Path path, String permissions) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) == null
            ? new java.nio.file.attribute.FileAttribute<?>[0]
            : new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))};
    }
}
