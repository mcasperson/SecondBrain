package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmptyStringTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final EmptyString exception = new EmptyString();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final EmptyString exception = new EmptyString("String is empty");

		assertEquals("String is empty", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final EmptyString exception = new EmptyString("String is empty", cause);

		assertEquals("String is empty", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final EmptyString exception = new EmptyString(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedInternalException() {
		final EmptyString exception = new EmptyString("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(InternalException.class, exception);
		assertFalse(ExternalException.class.isInstance(exception));
	}
}
