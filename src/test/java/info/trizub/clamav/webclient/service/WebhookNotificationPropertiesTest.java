package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookNotificationPropertiesTest {
    @Test void safeDefaultsAreFiniteAndPrivateDestinationsAreDisabled() {
        var properties = new WebhookNotificationProperties();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getDeliveryTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.isAllowPrivateAddresses()).isFalse();
        assertThat(properties.getWorkers()).isEqualTo(2);
        assertThat(properties.getQueueCapacity()).isEqualTo(100);
    }

    @Test void infiniteTimeoutsUnboundedPoolsAndDnsBeyondTheDeliveryDeadlineAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var properties = new WebhookNotificationProperties();
            properties.setReadTimeout(Duration.ZERO);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setReadTimeout(Duration.ofSeconds(10));
            properties.setWorkers(0);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setWorkers(2); properties.setQueueCapacity(1001);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setQueueCapacity(100); properties.setDeliveryTimeout(Duration.ofMillis(1));
            assertThat(validator.validate(properties)).isNotEmpty();
        }
    }
}
