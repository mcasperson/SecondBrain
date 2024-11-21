package secondbrain.domain.tools.slack;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagStringContext;

/**
 * Removes anything that looks like a URL from the context of a document. This ensures the context is clean and
 * will be parsed correctly when displayed in the Slack message.
 */
@ApplicationScoped
public class RagDocumentContextSanitizer {
    public <T> RagDocumentContext<T> sanitize(final RagDocumentContext<T> ragDocumentContext) {
        return new RagDocumentContext<>(
                ragDocumentContext.document(),
                ragDocumentContext.sentences().stream()
                        .map(sentence -> new RagStringContext(
                                sentence.context()
                                        .replaceAll("\\|", " ")
                                        .replaceAll(">", " ")
                                        .replaceAll("<", " "),
                                sentence.vector()
                        ))
                        .toList(),
                ragDocumentContext.id(),
                ragDocumentContext.meta());
    }
}
