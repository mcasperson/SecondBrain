package secondbrain.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DateParsingExceptionTest {
    @Test
    void messageConstructorKeepsTheMessageWithoutACause() {
        final DateParsingException exception = new DateParsingException("could not parse 2024-13-45");

        assertEquals("could not parse 2024-13-45", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void isAnUncheckedInternalException() {
        final DateParsingException exception = new DateParsingException("failed");

        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(InternalException.class, exception);
        assertFalse(ExternalException.class.isInstance(exception));
    }
}
