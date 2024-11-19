package secondbrain.domain.context;

import java.util.List;

/**
 * Splits a document into individual sentences.
 */
public interface SentenceSplitter {
    List<String> splitDocument(String document, final int minWords);
}
