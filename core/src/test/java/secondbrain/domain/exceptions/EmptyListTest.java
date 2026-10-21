package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmptyListTest {
    @Test
    void noArgumentConstructorHasNoMessageOrCause() {
        final EmptyList exception = new EmptyList();

        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageConstructorKeepsTheMessage() {
        final EmptyList exception = new EmptyList("List is empty");

        assertEquals("List is empty", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageAndCauseConstructorKeepsBoth() {
        final IllegalStateException cause = new IllegalStateException("root");

        final EmptyList exception = new EmptyList("List is empty", cause);

        assertEquals("List is empty", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
        final IllegalStateException cause = new IllegalStateException("root");

        final EmptyList exception = new EmptyList(cause);

        assertSame(cause, exception.getCause());
        assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
    }

    @Test
    void isAnUncheckedInternalException() {
        final EmptyList exception = new EmptyList("failed");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(InternalException.class, exception);
        assertFalse(ExternalException.class.isInstance(exception));
    }
}
