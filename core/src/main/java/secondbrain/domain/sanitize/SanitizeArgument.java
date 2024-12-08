package secondbrain.domain.sanitize;

/**
 * Defines a service for sanitizing a document.
 */
@FunctionalInterface
public interface SanitizeArgument {
    /**
     * Sanitize the argument.
     *
     * @param argument The argument
     * @param document The source document
     * @return The sanitized argument
     */
    String sanitize(String argument, String document);
}
