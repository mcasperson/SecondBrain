package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final Timeout exception = new Timeout();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final Timeout exception = new Timeout("call took too long");

		assertEquals("call took too long", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final Timeout exception = new Timeout("call took too long", cause);

		assertEquals("call took too long", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final Timeout exception = new Timeout(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedExternalException() {
		final Timeout exception = new Timeout("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(ExternalException.class, exception);
		assertFalse(InternalException.class.isInstance(exception));
	}
}
