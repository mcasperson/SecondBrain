package secondbrain.domain.vector;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Stream;

/**
 * Splits a document into individual sentences based on punctuation and line breaks.
 */
@ApplicationScoped
public class SimpleSentenceSplitter implements SentenceSplitter {
    @Override
    public List<String> splitDocument(final String document) {
        if (document == null) {
            return List.of();
        }

        return Stream.of(document.split("\\r\\n|\\r|\\n|\\.|;|!|\\?"))
                .filter(sentence -> !sentence.isBlank())
                .toList();
    }
}
