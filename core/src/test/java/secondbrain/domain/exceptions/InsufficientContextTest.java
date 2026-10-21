package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InsufficientContextTest {
    @Test
    void noArgumentConstructorHasNoMessageOrCause() {
        final InsufficientContext exception = new InsufficientContext();

        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageConstructorKeepsTheMessage() {
        final InsufficientContext exception = new InsufficientContext("The context is insufficient");

        assertEquals("The context is insufficient", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageAndCauseConstructorKeepsBoth() {
        final IllegalStateException cause = new IllegalStateException("root");

        final InsufficientContext exception = new InsufficientContext("The context is insufficient", cause);

        assertEquals("The context is insufficient", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
        final IllegalStateException cause = new IllegalStateException("root");

        final InsufficientContext exception = new InsufficientContext(cause);

        assertSame(cause, exception.getCause());
        assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
    }

    @Test
    void isAnUncheckedInternalException() {
        final InsufficientContext exception = new InsufficientContext("failed");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(InternalException.class, exception);
        assertFalse(ExternalException.class.isInstance(exception));
    }
}
