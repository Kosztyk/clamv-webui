package info.trizub.clamav.webclient.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/** Deployment limits shared by TCP and Unix transports; health probes never use the scan budget. */
@Validated
@ConfigurationProperties("clamav.client")
public class ClamdClientProperties {
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration commandTimeout = Duration.ofSeconds(5);
    private Duration scanTimeout = Duration.ofHours(24);

    @AssertTrue(message = "ClamD connect/command timeouts must be 1 ms to 1 minute, and scan timeout 1 ms to 7 days")
    public boolean isTimeoutsBounded() {
        return bounded(connectTimeout, Duration.ofMinutes(1))
                && bounded(commandTimeout, Duration.ofMinutes(1))
                && bounded(scanTimeout, Duration.ofDays(7));
    }
    private static boolean bounded(Duration value, Duration maximum) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(maximum) <= 0;
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getCommandTimeout() { return commandTimeout; }
    public void setCommandTimeout(Duration value) { commandTimeout = value; }
    public Duration getScanTimeout() { return scanTimeout; }
    public void setScanTimeout(Duration value) { scanTimeout = value; }
}
