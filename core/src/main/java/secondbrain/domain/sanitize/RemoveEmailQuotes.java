package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Identifier("removeEmailQuotes")
public class RemoveEmailQuotes implements SanitizeDocument {
    @Override
    public String sanitize(final String document) {
        if (document == null || document.isEmpty()) {
            return document;
        }

        // Simple implementation to remove quoted text from emails
        return document.replaceAll("(?m)^>.*$", "").trim();
    }
}
