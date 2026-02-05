package info.trizub.clamav.webclient.util;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class AtomicPropertiesFile {

    public static Properties loadOrCreate(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                new Properties().store(out, null);
            }
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        return p;
    }

    public static void storeAtomic(Path path, Properties props) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(out, null);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
