package info.trizub.clamav.webclient.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** Strict capacity with indexed TTL expiry. Live security budgets are never evicted for new keys. */
public final class BoundedExpiringStore<K, V> {
    public record Entry<V>(V value, Instant expiresAt) { }
    public record Lookup<V>(Entry<V> entry, boolean saturated, long retryAfterSeconds) { }
    private final Map<K, Entry<V>> entries = new HashMap<>();
    private final TreeMap<Instant, Set<K>> expiry = new TreeMap<>();
    private final int capacity;
    private final Clock clock;

    public BoundedExpiringStore(int capacity, Clock clock) {
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("Limiter capacity must be 1–100000");
        this.capacity = capacity; this.clock = clock;
    }

    public synchronized Lookup<V> lookup(K key) {
        Instant now = clock.instant();
        Entry<V> old = current(key, now);
        // Even when full, request-time expiry work is capped independently of the number of entries.
        if (old == null && entries.size() >= capacity) prune(now, 16);
        boolean saturated = old == null && entries.size() >= capacity;
        return new Lookup<>(old, saturated, saturated ? remaining(now, expiry.firstKey()) : 0);
    }

    public synchronized Lookup<V> update(K key, BiFunction<Entry<V>, Instant, Entry<V>> updater) {
        Lookup<V> found = lookup(key);
        if (found.saturated()) return found;
        Entry<V> next = updater.apply(found.entry(), clock.instant());
        if (next == null) { remove(key); return new Lookup<>(null, false, 0); }
        Entry<V> old = entries.put(key, next);
        if (old == null || !old.expiresAt().equals(next.expiresAt())) {
            if (old != null) unindex(key, old.expiresAt());
            expiry.computeIfAbsent(next.expiresAt(), ignored -> new LinkedHashSet<>()).add(key);
        }
        return new Lookup<>(next, false, 0);
    }

    public synchronized void remove(K key) {
        Entry<V> old = entries.remove(key);
        if (old != null) unindex(key, old.expiresAt());
    }
    public synchronized int pruneExpired(int maximumWork) { return prune(clock.instant(), maximumWork); }
    public synchronized int size() { return entries.size(); }
    public synchronized int expiryBucketCount() { return expiry.size(); }

    private Entry<V> current(K key, Instant now) {
        Entry<V> old = entries.get(key);
        if (old != null && !now.isBefore(old.expiresAt())) { remove(key); return null; }
        return old;
    }
    private int prune(Instant now, int maximumWork) {
        int removed = 0;
        while (removed < maximumWork && !expiry.isEmpty() && !now.isBefore(expiry.firstKey())) {
            var bucket = expiry.firstEntry();
            K key = bucket.getValue().iterator().next();
            remove(key); removed++;
        }
        return removed;
    }
    private void unindex(K key, Instant at) {
        Set<K> bucket = expiry.get(at);
        bucket.remove(key);
        if (bucket.isEmpty()) expiry.remove(at);
    }
    public static long remaining(Instant now, Instant expiresAt) {
        long millis = java.time.Duration.between(now, expiresAt).toMillis();
        return millis <= 0 ? 1 : (millis + 999) / 1000;
    }
}
