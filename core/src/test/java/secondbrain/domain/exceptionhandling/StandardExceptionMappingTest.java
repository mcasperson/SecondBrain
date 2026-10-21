package secondbrain.domain.exceptionhandling;

import io.vavr.control.Try;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.DeserializationFailed;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.exceptions.RateLimit;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class StandardExceptionMappingTest {

    private final StandardExceptionMapping mapping = new StandardExceptionMapping();

    @Test
    void mapLeavesSuccessUnchanged() {
        final Try<String> result = mapping.map(Try.success("value"));

        assertTrue(result.isSuccess());
        assertEquals("value", result.get());
    }

    @Test
    void mapPassesInternalFailureThroughUnwrapped() {
        final InternalFailure failure = new InternalFailure("internal");

        final Try<String> result = mapping.map(Try.failure(failure));

        assertSame(failure, result.getCause());
    }

    @Test
    void mapPassesExternalFailureThroughUnwrapped() {
        final ExternalFailure failure = new ExternalFailure("external");

        final Try<String> result = mapping.map(Try.failure(failure));

        assertSame(failure, result.getCause());
    }

    @Test
    void mapWrapsOtherExternalExceptionsInExternalFailure() {
        final RateLimit rateLimit = new RateLimit("rate limited");

        final Try<String> result = mapping.map(Try.failure(rateLimit));

        assertInstanceOf(ExternalFailure.class, result.getCause());
        assertSame(rateLimit, result.getCause().getCause());
    }

    @Test
    void mapWrapsInternalExceptionsThatAreNotInternalFailureInInternalFailure() {
        final DeserializationFailed deserializationFailed = new DeserializationFailed("bad data");

        final Try<String> result = mapping.map(Try.failure(deserializationFailed));

        assertInstanceOf(InternalFailure.class, result.getCause());
        assertSame(deserializationFailed, result.getCause().getCause());
    }

    @Test
    void mapWrapsUnknownExceptionsInInternalFailure() {
        final IllegalStateException exception = new IllegalStateException("boom");

        final Try<String> result = mapping.map(Try.failure(exception));

        assertInstanceOf(InternalFailure.class, result.getCause());
        assertSame(exception, result.getCause().getCause());
    }

    @Test
    void mapRejectsNull() {
        assertThrows(NullPointerException.class, () -> mapping.map(null));
    }
}
