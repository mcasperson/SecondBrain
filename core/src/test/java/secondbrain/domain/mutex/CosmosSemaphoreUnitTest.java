package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.config.MutexTimeout;
import secondbrain.domain.test.FakeCosmosLockContainer;
import secondbrain.domain.test.TestConfigUtil;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosSemaphore} backed by a real {@link CosmosMutex} whose Cosmos container is replaced by an
 * in-memory test instance, so no emulator is needed. The test instance is supplied by the producer method in this
 * class instead of {@link CosmosLockContainerProducer}.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosSemaphore.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CosmosSemaphoreUnitTest {
    private static final String LOCK = "semaphore";

    private final FakeCosmosLockContainer container = new FakeCosmosLockContainer();

    @Inject
    private CosmosSemaphore semaphore;

    @Produces
    CosmosLockContainer produceCosmosLockContainer() {
        return container;
    }

    void disposeCosmosLockContainer(@Disposes final CosmosLockContainer cosmosLockContainer) {
        cosmosLockContainer.close();
    }

    @BeforeEach
    void setUp() {
        // A zero default timeout means the default overload fails immediately when every slot is held
        TestConfigUtil.registerConfig(Map.of("sb.mutex.defaultTimeout", "0"));
    }

    @Test
    void acquireWithSingleSlotRunsCallbackUnderSlotZeroAndReleasesIt() {
        final AtomicInteger calls = new AtomicInteger();

        final String result = semaphore.acquire(1, 1000, LOCK, () -> {
            calls.incrementAndGet();
            // The slot is held while the callback runs
            assertTrue(container.hasLock(LOCK + "_0"));
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, calls.get());
        assertEquals(List.of(LOCK + "_0"), container.getReads());
        assertEquals(List.of(LOCK + "_0"), container.getUpserts());
        assertEquals(List.of(LOCK + "_0"), container.getDeletes());
        assertFalse(container.hasLock(LOCK + "_0"));
    }

    @Test
    void acquireSkipsHeldSlotsAndUsesTheOnlyFreeSlot() {
        container.addLock(LOCK + "_0", Instant.now());
        container.addLock(LOCK + "_1", Instant.now());

        final Integer result = semaphore.acquire(3, 0, LOCK, () -> 42);

        assertEquals(42, result);
        assertEquals(List.of(LOCK + "_2"), container.getUpserts());
        assertEquals(List.of(LOCK + "_2"), container.getDeletes());
        // The other processes' locks are untouched
        assertTrue(container.hasLock(LOCK + "_0"));
        assertTrue(container.hasLock(LOCK + "_1"));
    }

    @Test
    void acquireTakesOverAStaleLock() {
        // The default lock TTL is 300 seconds, so a lock acquired 10 minutes ago is stale
        container.addLock(LOCK + "_0", Instant.now().minus(10, ChronoUnit.MINUTES));

        final String result = semaphore.acquire(1, 0, LOCK, () -> "stale lock reused");

        assertEquals("stale lock reused", result);
        assertEquals(List.of(LOCK + "_0"), container.getUpserts());
        assertFalse(container.hasLock(LOCK + "_0"));
    }

    @Test
    void acquireThrowsLockFailWithoutRunningCallbackWhenAllSlotsAreHeldAndTimeoutIsZero() {
        container.addLock(LOCK + "_0", Instant.now());
        container.addLock(LOCK + "_1", Instant.now());
        final AtomicInteger calls = new AtomicInteger();

        final LockFail ex = assertThrows(LockFail.class, () -> semaphore.acquire(2, 0, LOCK, calls::incrementAndGet));

        assertEquals("Failed to obtain semaphore slot within the specified timeout: 0ms for lock: " + LOCK, ex.getMessage());
        assertEquals(0, calls.get());
        assertEquals(List.of(), container.getUpserts());
        // Each slot was attempted exactly once before giving up
        assertEquals(List.of(LOCK + "_0", LOCK + "_1"), container.getReads().stream().sorted().toList());
    }

    @Test
    void acquireRetriesUntilAHeldSlotIsReleasedWithinTheTimeout() {
        container.addLock(LOCK + "_0", Instant.now());
        container.releaseLockAfterReads(LOCK + "_0", 1);

        final String result = semaphore.acquire(1, 5000, LOCK, () -> "acquired after retry");

        assertEquals("acquired after retry", result);
        assertEquals(List.of(LOCK + "_0", LOCK + "_0"), container.getReads());
        assertEquals(List.of(LOCK + "_0"), container.getUpserts());
    }

    @Test
    void acquirePropagatesCallbackFailureAndRunsCallbackOnlyOnce() {
        final AtomicInteger calls = new AtomicInteger();

        // A long timeout proves the failure is not retried until the timeout expires
        final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> semaphore.acquire(2, 60_000, LOCK, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("callback failed");
        }));

        assertEquals("callback failed", ex.getMessage());
        assertEquals(1, calls.get());
        // The slot that ran the callback is released even though the callback failed
        assertEquals(1, container.getDeletes().size());
        assertFalse(container.hasLock(LOCK + "_0"));
        assertFalse(container.hasLock(LOCK + "_1"));
    }

    @Test
    void acquirePropagatesContainerReadFailureWithoutRunningCallback() {
        container.failReadsWith(new IllegalArgumentException("cosmos unavailable"));
        final AtomicInteger calls = new AtomicInteger();

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> semaphore.acquire(2, 60_000, LOCK, calls::incrementAndGet));

        assertEquals("cosmos unavailable", ex.getMessage());
        assertEquals(0, calls.get());
        // The search stops at the first slot rather than trying the other one
        assertEquals(1, container.getReads().size());
        assertEquals(List.of(), container.getUpserts());
    }

    @Test
    void acquirePropagatesUnexpectedUpsertFailureWithoutRunningCallback() {
        container.failUpsertsWith(new IllegalStateException("cosmos write failed"));
        final AtomicInteger calls = new AtomicInteger();

        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> semaphore.acquire(1, 60_000, LOCK, calls::incrementAndGet));

        assertEquals("cosmos write failed", ex.getMessage());
        assertEquals(0, calls.get());
        assertEquals(List.of(), container.getDeletes());
    }

    @Test
    void acquireTreatsUpsertContentionAsAHeldSlot() {
        container.failUpsertsWith(new LockFail("lock was saved by another process"));
        final AtomicInteger calls = new AtomicInteger();

        final LockFail ex = assertThrows(LockFail.class, () -> semaphore.acquire(2, 0, LOCK, calls::incrementAndGet));

        assertEquals("Failed to obtain semaphore slot within the specified timeout: 0ms for lock: " + LOCK, ex.getMessage());
        assertEquals(0, calls.get());
        // Contention on one slot moves on to the next
        assertEquals(List.of(LOCK + "_0", LOCK + "_1"), container.getReads().stream().sorted().toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void acquireRejectsAllowedBelowOne(final int allowed) {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> semaphore.acquire(allowed, 0, LOCK, () -> "never"));

        assertEquals("Allowed concurrency must be at least 1", ex.getMessage());
        assertEquals(List.of(), container.getReads());
    }

    @Test
    void acquireRejectsNegativeTimeout() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> semaphore.acquire(1, -1, LOCK, () -> "never"));

        assertEquals("Timeout must be greater than or equal to 0", ex.getMessage());
        assertEquals(List.of(), container.getReads());
    }

    @Test
    void acquireWithZeroTimeoutStillAttemptsAFreeSlot() {
        assertEquals("ok", semaphore.acquire(1, 0, LOCK, () -> "ok"));
        assertEquals(List.of(LOCK + "_0"), container.getUpserts());
    }

    @Test
    void acquireWithoutTimeoutUsesConfiguredDefaultTimeout() {
        container.addLock(LOCK + "_0", Instant.now());

        final LockFail ex = assertThrows(LockFail.class, () -> semaphore.acquire(1, LOCK, () -> "never"));

        // sb.mutex.defaultTimeout is 0 in this test
        assertEquals("Failed to obtain semaphore slot within the specified timeout: 0ms for lock: " + LOCK, ex.getMessage());
    }

    @Test
    void acquireWithoutTimeoutRunsCallbackWhenASlotIsFree() {
        assertEquals("default", semaphore.acquire(1, LOCK, () -> "default"));
        assertEquals(List.of(LOCK + "_0"), container.getDeletes());
    }
}
