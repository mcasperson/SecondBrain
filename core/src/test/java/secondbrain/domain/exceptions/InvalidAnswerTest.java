package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvalidAnswerTest {
    @Test
    void noArgumentConstructorHasNoMessageOrCause() {
        final InvalidAnswer exception = new InvalidAnswer();

        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageConstructorKeepsTheMessage() {
        final InvalidAnswer exception = new InvalidAnswer("The answer was invalid");

        assertEquals("The answer was invalid", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageAndCauseConstructorKeepsBoth() {
        final IllegalStateException cause = new IllegalStateException("root");

        final InvalidAnswer exception = new InvalidAnswer("The answer was invalid", cause);

        assertEquals("The answer was invalid", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
        final IllegalStateException cause = new IllegalStateException("root");

        final InvalidAnswer exception = new InvalidAnswer(cause);

        assertSame(cause, exception.getCause());
        assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
    }

    @Test
    void isAnUncheckedInternalException() {
        final InvalidAnswer exception = new InvalidAnswer("failed");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(InternalException.class, exception);
        assertFalse(ExternalException.class.isInstance(exception));
    }
}
