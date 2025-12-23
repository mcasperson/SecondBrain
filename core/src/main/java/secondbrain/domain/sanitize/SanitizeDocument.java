package secondbrain.domain.sanitize;

import org.jspecify.annotations.Nullable;

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
    @Nullable String sanitize(String document);
}
