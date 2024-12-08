package secondbrain.domain.sanitize;

/**
 * Defines a service for sanitizing an argument passed in a prompt.
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
