package secondbrain.domain.context;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Stream;

/**
 * Splits a document into individual sentences based on punctuation and line breaks.
 */
@ApplicationScoped
public class SimpleSentenceSplitter implements SentenceSplitter {
    @Override
    public List<String> splitDocument(final String document, final int minWords) {
        if (document == null) {
            return List.of();
        }

        return Stream.of(document.split("\\r\\n|\\r|\\n|\\.\\s+|\\.$|;\\s+|;|!\\s+|!$|\\?\\s+|\\?$"))
                .filter(sentence -> !sentence.isBlank())
                .filter(sentence -> sentence.split("\\s+").length >= minWords)
                .map(String::trim)
                // Remove list formatting
                .map(sentence -> sentence.replaceFirst("^\\* ", ""))
                .map(sentence -> sentence.replaceFirst("^• ", ""))
                .map(sentence -> sentence.replaceFirst("^◦ ", ""))
                .map(sentence -> sentence.replaceFirst("^- ", ""))
                .toList();
    }
}
