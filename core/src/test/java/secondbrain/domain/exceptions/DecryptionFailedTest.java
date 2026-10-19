package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecryptionFailedTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final DecryptionFailed exception = new DecryptionFailed();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final DecryptionFailed exception = new DecryptionFailed("failed to process");

		assertEquals("failed to process", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final DecryptionFailed exception = new DecryptionFailed("failed to process", cause);

		assertEquals("failed to process", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final DecryptionFailed exception = new DecryptionFailed(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final DecryptionFailed exception = new DecryptionFailed("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
	}
}
