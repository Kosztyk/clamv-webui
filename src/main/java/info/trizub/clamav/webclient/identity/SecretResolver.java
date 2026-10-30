package info.trizub.clamav.webclient.identity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** Secret-file support for Docker/Kubernetes mounted secrets. Never logs contents or paths. */
final class SecretResolver {
    private SecretResolver() {}
    static String resolve(String inline, String file) {
        if (file == null || file.isBlank()) return inline == null ? "" : inline;
        if (inline != null && !inline.isBlank())
            throw new IllegalArgumentException("Configure either secret value or secret file, not both");
        try {
            String content=Files.readString(Path.of(file)).stripTrailing();
            if (content.isBlank()) throw new IllegalArgumentException("Identity secret file is empty");
            return content;
        } catch (IOException ex) {
            throw new IllegalStateException("Identity secret file is not readable", ex);
        }
    }
}
