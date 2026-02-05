package info.trizub.clamav.webclient.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Instant;

@Service
public class QuarantineService {

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

    private final SettingsService settings;

    public QuarantineService(SettingsService settings) {
        this.settings = settings;
    }

    public Path quarantine(Path file) {
        try {
            if (!settings.quarantineEnabled()) return null;
            Path qdir = settings.quarantineDir();
            Files.createDirectories(qdir);
            String base = file.getFileName().toString();
            String name = Instant.now().toString().replace(":", "-") + "-" + base;
            Path target = qdir.resolve(name);
            return Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Quarantine move failed: {}", e.getMessage());
            return null;
        }
    }
}
