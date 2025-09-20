package secondbrain.domain.exceptionhandling;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.exceptions.ExternalException;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.instanceOf;

/**
 * Standard implementation of exception mapping.
 * Maps all exceptions to either InternalFailure or ExternalFailure.
 * InternalFailure indicates a non-recoverable error.
 * ExternalFailure indicates a potentially recoverable error (e.g. by retrying).
 */
@ApplicationScoped
public class StandardExceptionMapping implements ExceptionMapping {
    @Override
    public <T> Try<T> map(final Try<T> tryObject) {
        checkNotNull(tryObject);

        return tryObject.mapFailure(
                // InternalFailure passes through.
                API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                // ExternalFailure passes through.
                API.Case(API.$(instanceOf(ExternalFailure.class)), throwable -> throwable),
                // Map any exception that implements ExternalException to ExternalFailure.
                // We are conservative here and only treat exceptions that explicitly implement ExternalException as external.
                // This means we only retry operations that we know might succeed if retried.
                API.Case(API.$(instanceOf(ExternalException.class)), throwable -> new ExternalFailure(throwable)),
                // Map everything else to InternalFailure.
                API.Case(API.$(), throwable -> new InternalFailure(throwable)));
    }
}
