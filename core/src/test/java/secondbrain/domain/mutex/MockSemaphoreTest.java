package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MockSemaphore.class)
class MockSemaphoreTest {
    @Inject
    private MockSemaphore semaphore;

    @Test
    void acquireWithTimeoutRunsTheCallbackOnceAndReturnsItsResult() {
        final AtomicInteger calls = new AtomicInteger();

        final String result = semaphore.acquire(1, 10L, "lock", () -> "result " + calls.incrementAndGet());

        assertEquals("result 1", result);
        assertEquals(1, calls.get());
    }

    @Test
    void acquireWithoutTimeoutRunsTheCallbackOnceAndReturnsItsResult() {
        final AtomicInteger calls = new AtomicInteger();

        final Integer result = semaphore.acquire(3, "lock", calls::incrementAndGet);

        assertEquals(1, result);
        assertEquals(1, calls.get());
    }

    @Test
    void permitsAndTimeoutAreIgnored() {
        // Even zero permits and a zero timeout do not block or fail the callback
        assertEquals("ran", semaphore.acquire(0, 0L, "", () -> "ran"));
        assertEquals("ran", semaphore.acquire(0, "", () -> "ran"));
    }

    @Test
    void callbackExceptionsArePropagated() {
        final IllegalStateException failure = new IllegalStateException("callback failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> semaphore.acquire(1, 10L, "lock", () -> {
            throw failure;
        })));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> semaphore.acquire(1, "lock", () -> {
            throw failure;
        })));
    }
}
