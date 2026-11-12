package info.trizub.clamav.webclient.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetAddress;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryProxyHeadersTest {
    private final Map<String, String> spoofed = Map.of("x-forwarded-proto", "https", "forwarded", "for=1.2.3.4;proto=https");

    @Test void defaultAndLegacyModesCannotTreatDirectRecoveryHttpAsSecure() throws Exception {
        for (String mode : new String[]{"none", "framework", ""}) {
            var env = new MockEnvironment().withProperty("server.forward-headers-strategy", mode)
                    .withProperty("server.tomcat.remoteip.internal-proxies", "127.0.0.1/32");
            assertThat(new RecoveryProxyHeaders(env).isSecure(InetAddress.getLoopbackAddress(), spoofed)).isFalse();
        }
        assertThat(new RecoveryProxyHeaders(new MockEnvironment()).isSecure(InetAddress.getLoopbackAddress(), spoofed)).isFalse();
    }

    @Test void nativeRecoveryRequiresAnExplicitMatchingPeerAllowlist() throws Exception {
        var env = new MockEnvironment().withProperty("server.forward-headers-strategy", "native");
        var peer = InetAddress.getByName("192.0.2.10");
        assertThat(new RecoveryProxyHeaders(env).isSecure(peer, spoofed)).isFalse();
        env.withProperty("server.tomcat.remoteip.internal-proxies", "192.0.2.10/32,2001:db8::10/128");
        var headers = new RecoveryProxyHeaders(env);
        assertThat(headers.isSecure(peer, spoofed)).isTrue();
        assertThat(headers.isSecure(InetAddress.getByName("2001:db8::10"), spoofed)).isTrue();
        assertThat(headers.isSecure(InetAddress.getByName("192.0.2.11"), spoofed)).isFalse();
    }

    @Test void nativeRecoveryHandlesRegexAndAdditionalTrustedPeers() throws Exception {
        var env = new MockEnvironment().withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.trusted-proxies", "192\\.0\\.2\\.10");
        assertThat(new RecoveryProxyHeaders(env).isSecure(InetAddress.getByName("192.0.2.10"), spoofed)).isTrue();
    }

    @Test void malformedOrEmptyAllowlistsFailClosed() throws Exception {
        for (String value : new String[]{"", "[", "invalid/", "192.0.2.10/32,"}) {
            var env = new MockEnvironment().withProperty("server.forward-headers-strategy", "native")
                    .withProperty("server.tomcat.remoteip.internal-proxies", value);
            assertThat(new RecoveryProxyHeaders(env).isSecure(InetAddress.getLoopbackAddress(), spoofed)).isFalse();
            assertThatThrownBy(() -> ConfigurationCatalog.validate(ConfigurationCatalog.get("server.tomcat.remoteip.internal-proxies"), value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void mixedSchemesAndRfcForwardedCannotSatisfyNativeRecoveryHttps() throws Exception {
        var env = new MockEnvironment().withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "192.0.2.10/32");
        var headers = new RecoveryProxyHeaders(env);
        var peer = InetAddress.getByName("192.0.2.10");
        assertThat(headers.isSecure(peer, Map.of("forwarded", "proto=https"))).isFalse();
        assertThat(headers.isSecure(peer, Map.of("x-forwarded-proto", "https,http"))).isFalse();
        assertThat(headers.isSecure(peer, Map.of("x-forwarded-proto", "https,"))).isFalse();
        assertThat(headers.isSecure(peer, Map.of("x-forwarded-proto", "https, https"))).isTrue();
    }
}
