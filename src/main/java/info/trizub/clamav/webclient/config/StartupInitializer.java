package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.UserService;
import info.trizub.clamav.webclient.service.WatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs one-time initialization to ensure the app is usable on a fresh database:
 * - create a randomly protected admin user if no users exist
 * - create default clamd endpoint (from conf/clamav-web-client.properties legacy keys) if no endpoints exist
 * - start directory watcher if enabled
 */
@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final UserService userService;
    private final EndpointService endpointService;
    private final WatcherService watcherService;

    public StartupInitializer(UserService userService, EndpointService endpointService, WatcherService watcherService) {
        this.userService = userService;
        this.endpointService = endpointService;
        this.watcherService = watcherService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            userService.ensureDefaultAdmin();
            log.info("Bootstrap administrator check completed.");
        } catch (Exception e) {
            // Never start an unadministrable installation without surfacing the cause.
            throw new IllegalStateException("Unable to bootstrap administrator account", e);
        }

        try {
            endpointService.ensureDefaultEndpoint();
            log.info("Default endpoint ensured.");
        } catch (Exception e) {
            log.warn("Default endpoint init skipped/failed: {}", e.getMessage());
        }

        try {
            watcherService.startIfEnabled();
        } catch (Exception e) {
            log.warn("Watcher init skipped/failed: {}", e.getMessage());
        }
    }
}
