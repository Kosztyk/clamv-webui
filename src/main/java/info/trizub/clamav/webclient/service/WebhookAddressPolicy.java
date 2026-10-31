package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.WebhookNotificationConfiguration;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Validates the addresses handed directly to the connection manager, avoiding a second DNS lookup. */
@Component
public class WebhookAddressPolicy implements DnsResolver, AutoCloseable {
    @FunctionalInterface interface HostLookup { InetAddress[] lookup(String host) throws UnknownHostException; }
    private final WebhookNotificationProperties properties;
    private final HostLookup lookup;
    private final ThreadPoolExecutor dns;

    @Autowired
    public WebhookAddressPolicy(WebhookNotificationProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    WebhookAddressPolicy(WebhookNotificationProperties properties, HostLookup lookup) {
        this.properties = properties;
        this.lookup = lookup;
        // Native resolver calls may ignore interruption; bound both their threads and queue.
        dns = new ThreadPoolExecutor(properties.getWorkers(), properties.getWorkers(), 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getWorkers()),
            WebhookNotificationConfiguration.daemonThreads("webhook-dns-"), new ThreadPoolExecutor.AbortPolicy());
    }

    /** No hostname DNS here: safe to call from a scan worker or settings form. */
    public URI validateUrl(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 8192) throw invalidUrl();
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) throw invalidUrl();
            host = normalizeHost(host);
            if (host.isBlank() || host.contains("%")) throw invalidUrl();
            String domain = host.toLowerCase(Locale.ROOT);
            if ((domain.equals("discord.com") || domain.endsWith(".discord.com")
                || domain.equals("discordapp.com") || domain.endsWith(".discordapp.com"))
                && !scheme.equalsIgnoreCase("https")) throw invalidUrl();
            // Java also accepts abbreviated/dword IPv4 literals. Check them without doing hostname DNS.
            if (host.contains(":") || host.matches("[0-9.]+")) {
                if (!isAllowed(InetAddress.getByName(host))) throw invalidUrl();
            }
            return uri;
        } catch (IllegalArgumentException | UnknownHostException ex) {
            throw invalidUrl(); // Never propagate a token-bearing URL in an exception message.
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Future<InetAddress[]> result;
        try {
            result = dns.submit(() -> lookup.lookup(normalizeHost(host)));
        } catch (RejectedExecutionException ex) {
            throw denied("Webhook DNS capacity unavailable");
        }
        try {
            InetAddress[] addresses = result.get(properties.getDnsTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (addresses == null || addresses.length == 0) throw denied("Webhook DNS returned no addresses");
            for (InetAddress address : addresses) {
                if (address == null || !isAllowed(address)) throw denied("Webhook destination blocked by address policy");
            }
            return addresses.clone();
        } catch (InterruptedException ex) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw denied("Webhook DNS interrupted");
        } catch (TimeoutException | ExecutionException ex) {
            result.cancel(true);
            throw denied("Webhook DNS failed or timed out");
        } finally {
            dns.purge();
        }
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override public String resolveCanonicalHostname(String host) { return normalizeHost(host); }

    boolean isAllowed(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        if (raw.length == 4) {
            int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255, d = raw[3] & 255;
            // Metadata/platform endpoints stay blocked even when internal webhooks are enabled.
            if ((a == 168 && b == 63 && c == 129 && d == 16)
                || (a == 100 && b == 100 && c == 100 && d == 200)
                || a == 0 || a >= 224 || (a == 169 && b == 254)) return false;
            boolean internal = address.isLoopbackAddress() || address.isSiteLocalAddress()
                || (a == 100 && b >= 64 && b <= 127);
            if (internal) return properties.isAllowPrivateAddresses();
            // Non-routable documentation, benchmarking and obsolete relay ranges.
            return !((a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == 192 && b == 88 && c == 99) || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113));
        }
        if (raw.length != 16) return false;
        if (Arrays.equals(raw, new byte[]{(byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 2, 0x54})) return false; // AWS IPv6 IMDS fd00:ec2::254.
        boolean internal = address.isLoopbackAddress() || address.isSiteLocalAddress() || (raw[0] & 0xfe) == 0xfc;
        if (internal) return properties.isAllowPrivateAddresses();
        // Permit global unicast only; deny translation/tunnel and documentation ranges.
        if ((raw[0] & 0xe0) != 0x20) return false;
        int b = raw[1] & 255, c = raw[2] & 255, d = raw[3] & 255;
        return !((raw[0] == 0x20 && b == 1 && c < 2)
            || (raw[0] == 0x20 && b == 1 && c == 0x0d && d == 0xb8)
            || (raw[0] == 0x20 && b == 2)
            || (raw[0] == 0x3f && b == 0xff && (c & 0xf0) == 0));
    }

    public boolean allowsPrivateAddresses() { return properties.isAllowPrivateAddresses(); }

    private static String normalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("Invalid or disallowed webhook destination");
    }
    private static UnknownHostException denied(String reason) { return new UnknownHostException(reason); }
    @Override @PreDestroy public void close() { dns.shutdownNow(); }
}
