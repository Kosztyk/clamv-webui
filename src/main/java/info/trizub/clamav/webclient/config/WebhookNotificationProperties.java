package info.trizub.clamav.webclient.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Deployment policy, deliberately separate from administrator-editable webhook URLs. */
@Validated
@ConfigurationProperties("clamav.webhook")
public class WebhookNotificationProperties {
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Duration dnsTimeout = Duration.ofSeconds(5);
    private Duration deliveryTimeout = Duration.ofSeconds(20);
    @Min(1) @Max(8) private int workers = 2;
    @Min(1) @Max(1000) private int queueCapacity = 100;
    private boolean allowPrivateAddresses = false;

    @AssertTrue(message = "Webhook timeouts must be 1 ms to 5 minutes, and DNS timeout must not exceed delivery timeout")
    public boolean isTimeoutsBounded() {
        return bounded(connectTimeout) && bounded(readTimeout) && bounded(dnsTimeout)
            && bounded(deliveryTimeout) && dnsTimeout.compareTo(deliveryTimeout) <= 0;
    }

    private static boolean bounded(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
            && value.compareTo(Duration.ofMinutes(5)) <= 0;
    }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration getDnsTimeout() { return dnsTimeout; }
    public void setDnsTimeout(Duration value) { dnsTimeout = value; }
    public Duration getDeliveryTimeout() { return deliveryTimeout; }
    public void setDeliveryTimeout(Duration value) { deliveryTimeout = value; }
    public int getWorkers() { return workers; }
    public void setWorkers(int value) { workers = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public boolean isAllowPrivateAddresses() { return allowPrivateAddresses; }
    public void setAllowPrivateAddresses(boolean value) { allowPrivateAddresses = value; }
}
