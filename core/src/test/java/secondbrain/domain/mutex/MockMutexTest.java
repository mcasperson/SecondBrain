package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MockMutex.class)
class MockMutexTest {
	@Inject
	private MockMutex mockMutex;

	private final AtomicInteger calls = new AtomicInteger();

	private MutexCallback<String> countingCallback(final String result) {
		return () -> {
			calls.incrementAndGet();
			return result;
		};
	}

	private static MutexCallback<String> failingCallback(final RuntimeException exception) {
		return () -> {
			throw exception;
		};
	}

	@Test
	void acquireWithTimeoutRunsTheCallbackOnceAndReturnsItsResult() {
		assertEquals("timed", mockMutex.acquire(0L, "lock", countingCallback("timed")));
		assertEquals(1, calls.get());
	}

	@Test
	void acquireWithoutTimeoutRunsTheCallbackOnceAndReturnsItsResult() {
		assertEquals("untimed", mockMutex.acquire("lock", countingCallback("untimed")));
		assertEquals(1, calls.get());
	}

	@Test
	void acquireDoesNotHoldALockSoNestedAcquiresOfTheSameNameSucceed() {
		final String result = mockMutex.acquire("same", () -> mockMutex.acquire(1L, "same", countingCallback("nested")));

		assertEquals("nested", result);
		assertEquals(1, calls.get());
	}

	@Test
	void acquireWithTimeoutPropagatesCallbackExceptions() {
		final IllegalStateException failure = new IllegalStateException("boom");

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> mockMutex.acquire(10L, "lock", failingCallback(failure))));
	}

	@Test
	void acquireWithoutTimeoutPropagatesCallbackExceptions() {
		final IllegalArgumentException failure = new IllegalArgumentException("bad");

		assertSame(failure, assertThrows(IllegalArgumentException.class,
				() -> mockMutex.acquire("lock", failingCallback(failure))));
	}

	@Test
	void tryAcquireReturnsASuccessWithTheCallbackResult() {
		final Try<String> result = mockMutex.tryAcquire("lock", countingCallback("tried"));

		assertTrue(result.isSuccess());
		assertEquals("tried", result.get());
		assertEquals(1, calls.get());
	}

	@Test
	void tryAcquireWrapsCallbackExceptionsInAFailure() {
		final IllegalStateException failure = new IllegalStateException("boom");

		final Try<String> result = mockMutex.tryAcquire("lock", failingCallback(failure));

		assertTrue(result.isFailure());
		assertSame(failure, result.getCause());
	}
}
