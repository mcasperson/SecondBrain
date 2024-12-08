package secondbrain.domain.sanitize;

/**
 * Defines a service for sanitizing a document.
 */
public interface SanitizeDocument {
    /**
     * Sanitize the document.
     *
     * @param document The source document
     * @return The sanitized document
     */
    String sanitize(String document);
}
