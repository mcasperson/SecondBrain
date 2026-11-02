package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmCallFailureTest {
	@Test
	void noArgumentConstructorHasNoMessageOrCause() {
		final LlmCallFailure exception = new LlmCallFailure();

		assertNull(exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageConstructorKeepsTheMessage() {
		final LlmCallFailure exception = new LlmCallFailure("LLM call failed");

		assertEquals("LLM call failed", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	void messageAndCauseConstructorKeepsBoth() {
		final IllegalStateException cause = new IllegalStateException("root");

		final LlmCallFailure exception = new LlmCallFailure("LLM call failed", cause);

		assertEquals("LLM call failed", exception.getMessage());
		assertSame(cause, exception.getCause());
	}

	@Test
	void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
		final IllegalStateException cause = new IllegalStateException("root");

		final LlmCallFailure exception = new LlmCallFailure(cause);

		assertSame(cause, exception.getCause());
		assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
	}

	@Test
	void isAnUncheckedExternalException() {
		final LlmCallFailure exception = new LlmCallFailure("failed");

		assertInstanceOf(RuntimeException.class, exception);
		assertInstanceOf(ExternalException.class, exception);
		assertFalse(InternalException.class.isInstance(exception));
	}
}
