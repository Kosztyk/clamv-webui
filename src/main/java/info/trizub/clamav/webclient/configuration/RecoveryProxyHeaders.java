package info.trizub.clamav.webclient.configuration;

import org.apache.catalina.util.NetMask;
import org.springframework.core.env.Environment;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** The standalone recovery listener has no Tomcat valve: enforce the same peer allowlist here. */
final class RecoveryProxyHeaders {
    private final boolean nativeForwarding;
    private final Predicate<InetAddress> internal;
    private final Predicate<InetAddress> trusted;
    private final String protocolHeader;
    private final String httpsValue;

    RecoveryProxyHeaders(Environment environment) {
        nativeForwarding = "native".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"));
        internal = peers(environment.getProperty("server.tomcat.remoteip.internal-proxies", "(?!)"));
        trusted = peers(environment.getProperty("server.tomcat.remoteip.trusted-proxies", "(?!)"));
        protocolHeader = environment.getProperty("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto").toLowerCase(java.util.Locale.ROOT);
        httpsValue = environment.getProperty("server.tomcat.remoteip.protocol-header-https-value", "https");
    }

    boolean isSecure(InetAddress peer, Map<String, String> headers) {
        if (!nativeForwarding || !(internal.test(peer) || trusted.test(peer))) return false;
        String protocol = headers.getOrDefault(protocolHeader, "");
        // Match RemoteIpValve: every hop must report HTTPS. RFC Forwarded is not consumed in native mode.
        return !protocol.isBlank() && Arrays.stream(protocol.split(",", -1))
                .allMatch(value -> httpsValue.equalsIgnoreCase(value.trim()));
    }

    private static Predicate<InetAddress> peers(String configured) {
        if (configured == null || configured.isBlank()) return peer -> false;
        try {
            if (configured.contains("/")) {
                var networks = Arrays.stream(configured.split(",", -1)).map(String::trim).map(network -> {
                    if (!network.matches("[0-9A-Fa-f:.]+/[0-9]{1,3}")) throw new IllegalArgumentException("Use numeric CIDRs");
                    return new NetMask(network);
                }).toList();
                return peer -> networks.stream().anyMatch(network -> network.matches(peer));
            }
            var pattern = Pattern.compile(configured);
            return peer -> pattern.matcher(peer.getHostAddress()).matches();
        } catch (IllegalArgumentException invalid) {
            // A malformed trust configuration must never enable header trust in recovery.
            return peer -> false;
        }
    }
}
