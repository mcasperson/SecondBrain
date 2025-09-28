package secondbrain.domain.exceptions;

/**
 * Represents a failure from an internal source. This is like a 400 response code in HTTP.
 * It means that if you make the same call with the same data you'll get the same result.
 * We have a concrete class representing an internal failure (in addition to the InternalException interface)
 * to allow it to be used in methods like Try.recover() in Vavr which require a concrete class.
 * Typically, the Try.mapFailure() method is used to map exceptions implementing InternalException to InternalFailure.
 */
public class InternalFailure extends RuntimeException implements InternalException {
    public InternalFailure() {
        super();
    }

    public InternalFailure(final String message) {
        super(message);
    }

    public InternalFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InternalFailure(final Throwable cause) {
        super(cause);
    }
}