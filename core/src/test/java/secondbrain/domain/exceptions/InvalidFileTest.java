package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvalidFileTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final InvalidFile exception = new InvalidFile();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final InvalidFile exception = new InvalidFile("File is invalid");

		assertEquals("File is invalid", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final InvalidFile exception = new InvalidFile("File is invalid", cause);

		assertEquals("File is invalid", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final InvalidFile exception = new InvalidFile(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final InvalidFile exception = new InvalidFile("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
		assertFalse(ExternalException.class.isInstance(exception));
	}
}
