package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailedAzureTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final FailedAzure exception = new FailedAzure();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final FailedAzure exception = new FailedAzure("Azure call failed");

		assertEquals("Azure call failed", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final FailedAzure exception = new FailedAzure("Azure call failed", cause);

		assertEquals("Azure call failed", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final FailedAzure exception = new FailedAzure(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedExternalException() {
		final FailedAzure exception = new FailedAzure("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(ExternalException.class, exception);
		assertFalse(InternalException.class.isInstance(exception));
	}
}
