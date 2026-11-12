package info.trizub.clamav.webclient.service;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** DNS is included in the connect budget, with bounded workers/queue if the OS resolver ignores interruption. */
final class ClamdAddressResolver implements AutoCloseable {
    interface Lookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Lookup lookup;
    private final ThreadPoolExecutor workers;
    ClamdAddressResolver() { this(InetAddress::getAllByName); }
    ClamdAddressResolver(Lookup lookup) {
        this.lookup = lookup;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            var thread = new Thread(task, "clamav-dns-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    InetSocketAddress resolve(String host, int port, long deadline) throws IOException {
        SocketClamdClient.checkDeadline(deadline);
        if (port < 1 || port > 65535) throw new IOException("Invalid ClamD TCP port.");
        // Java parses address literals locally; they must remain usable even if hostname DNS is saturated.
        if (host.contains(":") || host.matches("[0-9.]+")) return new InetSocketAddress(InetAddress.getByName(host), port);
        Future<InetAddress[]> pending;
        try { pending = workers.submit(() -> lookup.resolve(host)); }
        catch (RejectedExecutionException ex) { throw new IOException("ClamD DNS capacity unavailable.", ex); }
        try {
            InetAddress[] addresses = pending.get(SocketClamdClient.remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (addresses == null || addresses.length == 0 || addresses[0] == null) throw new UnknownHostException("ClamD host has no addresses.");
            return new InetSocketAddress(addresses[0], port);
        } catch (TimeoutException ex) {
            throw new SocketTimeoutException("ClamD DNS timed out.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("ClamD DNS interrupted.");
        } catch (ExecutionException ex) {
            throw new IOException("ClamD DNS failed.", ex.getCause());
        } finally { pending.cancel(true); workers.purge(); }
    }
    @Override public void close() { workers.shutdownNow(); }
}
