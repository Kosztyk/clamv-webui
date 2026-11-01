package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WebhookAddressPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1", "2130706433", "10.1.2.3", "172.16.1.1",
        "172.31.255.254", "192.168.10.20", "100.64.0.1", "100.127.255.254", "0.0.0.0", "0.1.2.3",
        "169.254.169.254", "169.254.170.2", "168.63.129.16", "100.100.100.200", "224.0.0.1", "255.255.255.255",
        "192.0.2.1", "198.18.0.1", "203.0.113.1", "[::]", "[::1]", "[::ffff:127.0.0.1]",
        "[fd12:3456::1]", "[fd00:ec2::254]", "[fe80::1]", "[ff02::1]", "[64:ff9b::a00:1]",
        "[2002:a00:1::1]", "[2001:db8::1]", "[3fff::1]"})
    void nonPublicLiteralsAreBlockedByDefault(String host) {
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties())) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy.validateUrl("http://" + host + "/hook"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.org/hook", "https://discord.com/api/webhooks/123/token",
        "https://8.8.8.8/hook", "https://[2606:4700:4700::1111]/hook"})
    void publicUrlsCanBeSavedWithoutDoingDns(String url) {
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties(), host -> {
            throw new AssertionError("Settings validation must not perform DNS");
        })) {
            assertThat(policy.validateUrl(url).getHost()).isNotBlank();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.org/hook", "file:///etc/passwd", "https://user:secret@example.org/hook",
        "https://example.org/hook#fragment", "http://discord.com/api/webhooks/123/token",
        "http://discord.com./api/webhooks/123/token", "https://example.org:0/hook", "http://example.org:65536/hook",
        "http://[fe80::1%25eth0]/hook", "not a URL", ""})
    void unsafeUrlSyntaxAndPlainHttpDiscordAreRejected(String url) {
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties())) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy.validateUrl(url));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.1.1", "192.168.10.20", "100.64.0.1", "[::1]", "[fd12:3456::1]"})
    void internalReceiversRequireExplicitServerOptIn(String host) {
        var properties = new WebhookNotificationProperties();
        properties.setAllowPrivateAddresses(true);
        try (var policy = new WebhookAddressPolicy(properties)) {
            assertThat(policy.validateUrl("http://" + host + "/hook")).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "169.254.170.2", "168.63.129.16", "100.100.100.200",
        "0.0.0.0", "224.0.0.1", "[fd00:ec2::254]", "[fe80::1]", "[ff02::1]"})
    void metadataAndSpecialDestinationsStayBlockedAfterOptIn(String host) {
        var properties = new WebhookNotificationProperties();
        properties.setAllowPrivateAddresses(true);
        try (var policy = new WebhookAddressPolicy(properties)) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy.validateUrl("http://" + host + "/hook"));
        }
    }

    @Test void mixedPublicAndPrivateDnsAnswersRejectTheEntireHostname() throws Exception {
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties(), host -> new InetAddress[]{
            InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")})) {
            assertThatExceptionOfType(UnknownHostException.class).isThrownBy(() -> policy.resolve("hook.example.test"));
        }
    }

    @Test void everyNewResolutionRechecksAddressesAndReturnsResolvedSocketAddresses() throws Exception {
        var lookups = new AtomicInteger();
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties(), host ->
            new InetAddress[]{InetAddress.getByName(lookups.incrementAndGet() == 1 ? "8.8.8.8" : "127.0.0.1")})) {
            var addresses = policy.resolve("hook.example.test", 443);
            assertThat(addresses.get(0).isUnresolved()).isFalse();
            assertThat(addresses.get(0).getAddress().getHostAddress()).isEqualTo("8.8.8.8");
            assertThat(lookups.get()).isEqualTo(1);
            assertThatExceptionOfType(UnknownHostException.class).isThrownBy(() -> policy.resolve("hook.example.test", 443));
        }
    }

    @Test void dnsLookupHasAnIndependentDeadline() throws Exception {
        var properties = new WebhookNotificationProperties();
        properties.setDnsTimeout(Duration.ofMillis(80));
        var release = new CountDownLatch(1);
        try (var policy = new WebhookAddressPolicy(properties, host -> {
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        })) {
            long start = System.nanoTime();
            assertThatExceptionOfType(UnknownHostException.class).isThrownBy(() -> policy.resolve("hook.example.test"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        } finally { release.countDown(); }
    }

    @Test void dnsFailureDoesNotExposeResolverMessages() {
        try (var policy = new WebhookAddressPolicy(new WebhookNotificationProperties(), host -> {
            throw new UnknownHostException("secret-bearing resolver message");
        })) {
            assertThatExceptionOfType(UnknownHostException.class).isThrownBy(() -> policy.resolve("hook.example.test"))
                .withMessage("Webhook DNS failed or timed out").withNoCause();
        }
    }
}
