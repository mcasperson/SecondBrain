package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnauthorizedResponseTest {
    @Test
    void noArgumentConstructorHasNoMessageOrCause() {
        final UnauthorizedResponse exception = new UnauthorizedResponse();

        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageConstructorKeepsTheMessage() {
        final UnauthorizedResponse exception = new UnauthorizedResponse("HTTP 401");

        assertEquals("HTTP 401", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageAndCauseConstructorKeepsBoth() {
        final IllegalStateException cause = new IllegalStateException("root");

        final UnauthorizedResponse exception = new UnauthorizedResponse("HTTP 401", cause);

        assertEquals("HTTP 401", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void causeConstructorKeepsTheCauseAndDerivesTheMessage() {
        final IllegalStateException cause = new IllegalStateException("root");

        final UnauthorizedResponse exception = new UnauthorizedResponse(cause);

        assertSame(cause, exception.getCause());
        assertEquals("java.lang.IllegalStateException: root", exception.getMessage());
    }

    @Test
    void isAnUncheckedInternalException() {
        final UnauthorizedResponse exception = new UnauthorizedResponse("failed");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(InternalException.class, exception);
        assertFalse(ExternalException.class.isInstance(exception));
    }
}
