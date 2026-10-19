package secondbrain.domain.tryext;

import io.vavr.control.Try;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TryExtensionsTest {
	private final List<String> closed = new CopyOnWriteArrayList<>();

	private Resource resource(final String name) {
		return new Resource(name, closed, false);
	}

	@Test
	void callbackResultIsReturnedAndBothResourcesAreClosedInReverseOrder() {
		final Try<String> result = TryExtensions.withResources(
				() -> resource("first"),
				first -> resource(first.name() + "-second"),
				second -> "value from " + second.name());

		assertEquals(Try.success("value from first-second"), result);
		assertEquals(List.of("first-second", "first"), closed);
	}

	@Test
	void failureToCreateTheFirstResourceIsAFailedTry() {
		final IllegalStateException failure = new IllegalStateException("no first");

		final Try<String> result = assertDoesNotThrow(() -> TryExtensions.<Resource, Resource, String>withResources(
				() -> {
					throw failure;
				},
				first -> resource("second"),
				second -> "never"));

		assertSame(failure, result.getCause());
		assertTrue(closed.isEmpty());
	}

	@Test
	void failureToCloseTheFirstResourceIsAFailedTry() {
		final Try<String> result = assertDoesNotThrow(() -> TryExtensions.withResources(
				() -> new Resource("first", closed, true),
				first -> resource("second"),
				second -> "value"));

		assertEquals("Failed to close first", result.getCause().getMessage());
		assertEquals(List.of("second", "first"), closed);
	}

	@Test
	void failureToCreateTheSecondResourceIsAFailedTryAndClosesTheFirst() {
		final IllegalStateException failure = new IllegalStateException("no second");

		final Try<String> result = TryExtensions.<Resource, Resource, String>withResources(
				() -> resource("first"),
				first -> {
					throw failure;
				},
				second -> "never");

		assertSame(failure, result.getCause());
		assertEquals(List.of("first"), closed);
	}

	@Test
	void callbackFailureIsAFailedTryAndClosesBothResources() {
		final IllegalStateException failure = new IllegalStateException("callback failed");

		final Try<String> result = TryExtensions.<Resource, Resource, String>withResources(
				() -> resource("first"),
				first -> resource("second"),
				second -> {
					throw failure;
				});

		assertSame(failure, result.getCause());
		assertEquals(List.of("second", "first"), closed);
	}

	/**
	 * Records when it is closed, and optionally fails to close.
	 */
	private record Resource(String name, List<String> closed, boolean failOnClose) implements AutoCloseable {
		@Override
		public void close() {
			closed.add(name);
			if (failOnClose) {
				throw new IllegalStateException("Failed to close " + name);
			}
		}
	}
}
