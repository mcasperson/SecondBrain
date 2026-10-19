package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecompressionFailedTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final DecompressionFailed exception = new DecompressionFailed();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final DecompressionFailed exception = new DecompressionFailed("failed to process");

		assertEquals("failed to process", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final DecompressionFailed exception = new DecompressionFailed("failed to process", cause);

		assertEquals("failed to process", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final DecompressionFailed exception = new DecompressionFailed(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final DecompressionFailed exception = new DecompressionFailed("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
	}
}
