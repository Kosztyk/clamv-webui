package info.trizub.clamav.webclient.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Atomic and owner-private persistence for configuration containing webhook secrets. */
public final class AtomicPropertiesFile {
    private AtomicPropertiesFile() { }

    public static Properties loadOrCreate(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.notExists(path)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(path);
            } catch (FileAlreadyExistsException ignored) {
                // Another startup process created this file.
            }
        }
        ownerOnly(path);
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) { props.load(in); }
        return props;
    }

    public static void storeAtomic(Path path, Properties props) throws IOException {
        Files.createDirectories(path.getParent());
        // createTempFile uses an unpredictable name and 0600 permissions on POSIX systems.
        Path tmp = Files.createTempFile(path.getParent(), ".clamav-settings-", ".tmp");
        try {
            ownerOnly(tmp);
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, null);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            ownerOnly(path);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void ownerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX deployments must restrict access at the mount level.
        }
    }
}
