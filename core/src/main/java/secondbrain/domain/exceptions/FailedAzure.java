package secondbrain.domain.exceptions;

/**
 * Represents a failed call to Azure
 */
public class FailedAzure extends RuntimeException implements ExternalException {
    public FailedAzure() {
        super();
    }

    public FailedAzure(final String message) {
        super(message);
    }

    public FailedAzure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FailedAzure(final Throwable cause) {
        super(cause);
    }
}