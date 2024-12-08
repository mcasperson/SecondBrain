package secondbrain.domain.sanitize;

public interface SanitizeDocument {
    /**
     * Sanitize the document.
     *
     * @param document The source document
     * @return The sanitized document
     */
    String sanitize(String document);
}
