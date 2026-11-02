package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCauseAndDefaultBodyAndCode() {
		final RateLimit exception = new RateLimit();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
		assertEquals("", exception.getBody());
		assertEquals(-1, exception.getCode());
	}

	@Test
	void messageConstructorKeepsTheMessageAndDefaultBodyAndCode() {
		final RateLimit exception = new RateLimit("Too many requests");

		assertEquals("Too many requests", exception.getMessage());
		assertNull(exception.getCause());
		assertEquals("", exception.getBody());
		assertEquals(-1, exception.getCode());
	}

	@Test
	void messageAndCauseConstructorKeepsBothAndDefaultBodyAndCode() {
		final IllegalStateException cause = new IllegalStateException("root");

		final RateLimit exception = new RateLimit("Too many requests", cause);

		assertEquals("Too many requests", exception.getMessage());
		assertSame(cause, exception.getCause());
		assertEquals("", exception.getBody());
		assertEquals(-1, exception.getCode());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessageAndDefaultBodyAndCode() {
		final IllegalStateException cause = new IllegalStateException("root");

		final RateLimit exception = new RateLimit(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
		assertEquals("", exception.getBody());
		assertEquals(-1, exception.getCode());
	}

	@Test
	void isAnUncheckedExternalException() {
		final RateLimit exception = new RateLimit("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(ExternalException.class, exception);
		assertFalse(InternalException.class.isInstance(exception));
	}
}
