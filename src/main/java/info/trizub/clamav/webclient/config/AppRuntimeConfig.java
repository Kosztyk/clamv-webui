package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.UserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

@Configuration
@EnableScheduling
public class AppRuntimeConfig {

    private final UserService userService;
    private final EndpointService endpointService;

    public AppRuntimeConfig(UserService userService, EndpointService endpointService) {
        this.userService = userService;
        this.endpointService = endpointService;
    }

    @PostConstruct
    public void seed() {
        userService.ensureDefaultAdmin();
        endpointService.ensureDefaultEndpoint();
    }
}
