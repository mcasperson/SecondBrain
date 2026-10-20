package secondbrain.domain.timeout;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import secondbrain.domain.test.TestConfigUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timeouts are expressed in whole seconds, so a timeout of 0 seconds is used to force a timeout immediately while the
 * callback is blocked on a latch. Successful calls use a generous timeout but return straight away.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CompletableFutureTimeoutService.class)
@Timeout(10)
// Null callbacks are passed deliberately to cover the precondition checks
@SuppressWarnings("NullAway")
class CompletableFutureTimeoutServiceTest {
    private static final long GENEROUS_TIMEOUT = 30;
    private static final long IMMEDIATE_TIMEOUT = 0;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger callbackCalls = new AtomicInteger();
    private final AtomicInteger timeoutCalls = new AtomicInteger();

    @Inject
    private CompletableFutureTimeoutService service;

    @Produces
    Logger produceLogger() {
        final Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    @AfterEach
    void releaseBlockedCallbacks() {
        release.countDown();
    }

    private String blockingCallback() {
        callbackCalls.incrementAndGet();
        try {
            release.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "late";
    }

    private String immediateCallback() {
        callbackCalls.incrementAndGet();
        return "result";
    }

    private String onTimeout() {
        timeoutCalls.incrementAndGet();
        return "timed out";
    }

    @Test
    void executeWithTimeoutReturnsTheCallbackResult() {
        assertEquals("result", service.executeWithTimeout(this::immediateCallback, this::onTimeout, GENEROUS_TIMEOUT));
        assertEquals(1, callbackCalls.get());
        assertEquals(0, timeoutCalls.get());
        assertEquals(List.of(), warnings);
    }

    @Test
    void executeWithTimeoutCallsOnTimeoutWhenTheCallbackTimesOut() {
        assertEquals("timed out", service.executeWithTimeout(this::blockingCallback, this::onTimeout, IMMEDIATE_TIMEOUT));
        assertEquals(1, timeoutCalls.get());
        assertEquals(List.of("Operation timed out after 0 seconds"), warnings);
    }

    @Test
    void executeWithTimeoutPropagatesOnTimeoutFailures() {
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.executeWithTimeout(this::blockingCallback, () -> {
                    throw new IllegalStateException("gave up");
                }, IMMEDIATE_TIMEOUT));

        assertEquals("gave up", ex.getMessage());
    }

    @Test
    void executeWithTimeoutPropagatesCallbackFailures() {
        final ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.executeWithTimeout(() -> {
                    throw new IllegalStateException("callback failed");
                }, this::onTimeout, GENEROUS_TIMEOUT));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("callback failed", ex.getCause().getMessage());
        assertEquals(0, timeoutCalls.get());
    }

    @Test
    void executeWithTimeoutRejectsNullCallbacks() {
        final NullPointerException callback = assertThrows(NullPointerException.class,
                () -> service.executeWithTimeout((TimeoutFunctionCallback<String>) null, this::onTimeout, GENEROUS_TIMEOUT));
        final NullPointerException timeout = assertThrows(NullPointerException.class,
                () -> service.executeWithTimeout(this::immediateCallback, null, GENEROUS_TIMEOUT));

        assertEquals("callback must not be null", callback.getMessage());
        assertEquals("onTimeout must not be null", timeout.getMessage());
        assertEquals(0, callbackCalls.get());
    }

    @Test
    void executeWithTimeoutReturnsTheResultOfACompletedFuture() {
        assertEquals("done", service.executeWithTimeout(CompletableFuture.completedFuture("done"), this::onTimeout, GENEROUS_TIMEOUT));
        assertEquals(0, timeoutCalls.get());
    }

    @Test
    void executeWithTimeoutWaitsForAFutureToComplete() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final CompletableFuture<Void> completer = CompletableFuture.runAsync(() -> future.complete("eventually"));

        assertEquals("eventually", service.executeWithTimeout(future, this::onTimeout, GENEROUS_TIMEOUT));
        completer.join();
    }

    @Test
    void executeWithTimeoutCallsOnTimeoutWhenAFutureTimesOut() {
        final CompletableFuture<String> future = new CompletableFuture<>();

        assertEquals("timed out", service.executeWithTimeout(future, this::onTimeout, IMMEDIATE_TIMEOUT));
        assertEquals(1, timeoutCalls.get());
        assertEquals(List.of("Operation timed out after 0 seconds"), warnings);
        // The supplied future is completed exceptionally by the timeout
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void executeWithTimeoutPropagatesFutureFailures() {
        final ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.executeWithTimeout(CompletableFuture.<String>failedFuture(new IllegalArgumentException("bad")), this::onTimeout, GENEROUS_TIMEOUT));

        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void executeWithTimeoutRejectsNullFutures() {
        assertThrows(NullPointerException.class,
                () -> service.executeWithTimeout((CompletableFuture<String>) null, this::onTimeout, GENEROUS_TIMEOUT));
        assertThrows(NullPointerException.class,
                () -> service.executeWithTimeout(CompletableFuture.completedFuture("done"), null, GENEROUS_TIMEOUT));
    }

    @Test
    void executeWithTimeoutAndRetryReturnsTheFirstSuccessfulResult() {
        assertEquals("result", service.executeWithTimeoutAndRetry(this::immediateCallback, this::onTimeout, GENEROUS_TIMEOUT, 3));
        assertEquals(1, callbackCalls.get());
        assertEquals(0, timeoutCalls.get());
    }

    @Test
    void executeWithTimeoutAndRetryWithDelayReturnsTheFirstSuccessfulResult() {
        assertEquals("result", service.executeWithTimeoutAndRetry(this::immediateCallback, this::onTimeout, GENEROUS_TIMEOUT, 1, 0));
        assertEquals(1, callbackCalls.get());
    }

    @Test
    void executeWithTimeoutAndRetryCallsOnTimeoutWithoutRunningTheCallbackWhenNoAttemptsAreAllowed() {
        assertEquals("timed out", service.executeWithTimeoutAndRetry(this::immediateCallback, this::onTimeout, GENEROUS_TIMEOUT, 0));
        assertEquals(0, callbackCalls.get());
        assertEquals(1, timeoutCalls.get());
        assertEquals(List.of("Max retry attempts reached: 0"), warnings);
    }

    @Test
    void executeWithTimeoutAndRetryTreatsANegativeRetryCountAsNoAttempts() {
        assertEquals("timed out", service.executeWithTimeoutAndRetry(this::immediateCallback, this::onTimeout, GENEROUS_TIMEOUT, -1, 0));
        assertEquals(0, callbackCalls.get());
    }

    @Test
    void executeWithTimeoutAndRetryRetriesTimeoutsThenCallsOnTimeout() {
        assertEquals("timed out", service.executeWithTimeoutAndRetry(this::blockingCallback, this::onTimeout, IMMEDIATE_TIMEOUT, 3, 0));
        assertEquals(1, timeoutCalls.get());
        assertEquals(List.of(
                "Operation timed out after 0 seconds",
                "Operation timed out after 0 seconds",
                "Operation timed out after 0 seconds",
                "Max retry attempts reached: 3"), warnings);
    }

    @Test
    void executeWithTimeoutAndRetryReturnsTheResultOfARetryAfterATimeout() {
        // The first attempt blocks until the test ends, so it times out, and the second attempt returns straight away
        final TimeoutFunctionCallback<String> callback = () -> callbackCalls.get() == 0 ? blockingCallback() : immediateCallback();

        assertEquals("result", service.executeWithTimeoutAndRetry(callback, this::onTimeout, 1, 3, 0));
        assertEquals(2, callbackCalls.get());
        assertEquals(0, timeoutCalls.get());
        assertEquals(List.of("Operation timed out after 1 seconds"), warnings);
    }

    @Test
    void executeWithTimeoutAndRetryDoesNotRetryFailures() {
        final ExecutionException ex = assertThrows(ExecutionException.class,
                () -> service.executeWithTimeoutAndRetry(() -> {
                    callbackCalls.incrementAndGet();
                    throw new IllegalStateException("callback failed");
                }, this::onTimeout, GENEROUS_TIMEOUT, 3));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(1, callbackCalls.get());
        assertEquals(0, timeoutCalls.get());
    }

    @Test
    void executeWithTimeoutAndRetryRejectsNullCallbacks() {
        assertThrows(NullPointerException.class, () -> service.executeWithTimeoutAndRetry(null, this::onTimeout, GENEROUS_TIMEOUT, 1));
        assertThrows(NullPointerException.class, () -> service.executeWithTimeoutAndRetry(this::immediateCallback, null, GENEROUS_TIMEOUT, 1));
        assertThrows(NullPointerException.class, () -> service.executeWithTimeoutAndRetry(null, this::onTimeout, GENEROUS_TIMEOUT, 1, 0));
        assertThrows(NullPointerException.class, () -> service.executeWithTimeoutAndRetry(this::immediateCallback, null, GENEROUS_TIMEOUT, 1, 0));
        assertEquals(0, callbackCalls.get());
    }
}
