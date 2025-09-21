package secondbrain.domain.exceptions;

/**
 * Represents a failure from an external source. This is like a 500 response code in HTTP.
 * It means if you make the same call with the same data you might be successful.
 * We have a concrete class representing an external failure (in addition to the ExternalException interface)
 * to allow it to be used in methods like Try.recover() in Vavr which require a concrete class.
 * Typically, the Try.mapFailure() method is used to map exceptions implementing ExternalException to ExternalFailure.
 */
public class ExternalFailure extends RuntimeException implements ExternalException {
    public ExternalFailure() {
        super();
    }

    public ExternalFailure(final String message) {
        super(message);
    }

    public ExternalFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ExternalFailure(final Throwable cause) {
        super(cause);
    }
}