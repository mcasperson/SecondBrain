package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MissingResponseTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final MissingResponse exception = new MissingResponse();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final MissingResponse exception = new MissingResponse("Resource not found");

		assertEquals("Resource not found", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final MissingResponse exception = new MissingResponse("Resource not found", cause);

		assertEquals("Resource not found", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final MissingResponse exception = new MissingResponse(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final MissingResponse exception = new MissingResponse("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
		assertFalse(ExternalException.class.isInstance(exception));
	}
}
