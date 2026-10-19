package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerializationFailedTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final SerializationFailed exception = new SerializationFailed();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final SerializationFailed exception = new SerializationFailed("failed to process");

		assertEquals("failed to process", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final SerializationFailed exception = new SerializationFailed("failed to process", cause);

		assertEquals("failed to process", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final SerializationFailed exception = new SerializationFailed(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final SerializationFailed exception = new SerializationFailed("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
	}
}
