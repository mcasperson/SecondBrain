package secondbrain.domain.limit;

import java.util.List;

/**
 * Defines a service that can trim a document based on keywords.
 */
public interface DocumentTrimmer {
    String trimDocumentToKeywords(String document, List<String> keywords, int sectionLength);
}
