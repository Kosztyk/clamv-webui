package info.trizub.clamav.webclient.security;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class BoundedExpiringStoreTest {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T12:00:00Z"));
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    @Test void admissionDoesNotEvictLiveBudgetsAndBothIndexesRemainBounded() {
        var store = new BoundedExpiringStore<String, Integer>(8, clock);
        for (int i = 0; i < 1_000_000; i++) {
            store.update("subject-" + i, (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(60)));
        }
        assertThat(store.size()).isEqualTo(8); assertThat(store.expiryBucketCount()).isEqualTo(1);
        assertThat(store.lookup("subject-0").entry().value()).isEqualTo(1);
        assertThat(store.lookup("new-subject").saturated()).isTrue();
    }
    @Test void updatingExpiryRemovesOldIndexAndExpiryCleanupHasABoundedWorkBudget() {
        var store = new BoundedExpiringStore<Integer, Integer>(32, clock);
        for (int i = 0; i < 32; i++) store.update(i, (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(10)));
        for (int i = 0; i < 32; i++) store.update(i, (old, at) -> new BoundedExpiringStore.Entry<>(2, at.plusSeconds(20)));
        assertThat(store.expiryBucketCount()).isEqualTo(1);
        now.set(now.get().plusSeconds(20));
        assertThat(store.pruneExpired(3)).isEqualTo(3); assertThat(store.size()).isEqualTo(29);
        assertThat(store.pruneExpired(40)).isEqualTo(29); assertThat(store.expiryBucketCount()).isZero();
    }
    @Test void saturatedLookupPrunesAtMostSixteenExpiredEntriesAndMakesRoom() {
        var store = new BoundedExpiringStore<Integer, Integer>(64, clock);
        for (int i = 0; i < 64; i++) store.update(i, (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(1)));
        now.set(now.get().plusSeconds(1));
        assertThat(store.lookup(100).saturated()).isFalse(); assertThat(store.size()).isEqualTo(48);
    }
    @Test void concurrentUpdatesCannotOvershootCapacityOrLoseACounterIncrement() throws Exception {
        var store = new BoundedExpiringStore<Integer, Integer>(4, clock);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int i = 0; i < 100; i++) {
                int key = i;
                tasks.add(() -> { store.update(0, (old, at) -> new BoundedExpiringStore.Entry<>(old == null ? 1 : old.value() + 1, at.plusSeconds(60)));
                    store.update(key + 1, (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(60))); return null; });
            }
            for (var future : pool.invokeAll(tasks)) future.get();
            assertThat(store.size()).isEqualTo(4); assertThat(store.lookup(0).entry().value()).isEqualTo(100);
        } finally { pool.shutdownNow(); }
    }
    @Test void targetExpiryAndSuccessRemovalClearBothIndexes() {
        var store = new BoundedExpiringStore<String, Integer>(2, clock);
        store.update("one", (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(1)));
        store.update("two", (old, at) -> new BoundedExpiringStore.Entry<>(1, at.plusSeconds(2)));
        store.remove("one"); assertThat(store.size()).isEqualTo(1); assertThat(store.expiryBucketCount()).isEqualTo(1);
        now.set(now.get().plusSeconds(2)); assertThat(store.lookup("two").entry()).isNull();
        assertThat(store.size()).isZero(); assertThat(store.expiryBucketCount()).isZero();
    }
    @Test void invalidCapacityIsRejected() {
        for (int capacity : new int[]{0, -1, 100_001})
            assertThatThrownBy(() -> new BoundedExpiringStore<>(capacity, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
