package secondbrain.domain.context;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Splits a document into individual sentences based on punctuation and line breaks.
 */
@ApplicationScoped
public class SimpleSentenceSplitter implements SentenceSplitter {
    private static final String SENTENCE_SPLIT_REGEX = "\\r\\n|\\r|\\n|\\.\\s+|\\.$|;\\s+|;|!\\s+|!$|\\?\\s+|\\?$";

    @Override
    public List<String> splitDocument(final String document, final int minWords) {
        if (StringUtils.isBlank(document)) {
            return List.of();
        }

        return Stream.of(document.split(SENTENCE_SPLIT_REGEX))
                .filter(sentence -> !sentence.isBlank())
                .filter(sentence -> sentence.split("\\s+").length >= minWords)
                .map(String::trim)
                // Remove list formatting
                .map(sentence -> sentence.replaceFirst("^\\* ", ""))
                .map(sentence -> sentence.replaceFirst("^• ", ""))
                .map(sentence -> sentence.replaceFirst("^◦ ", ""))
                .map(sentence -> sentence.replaceFirst("^- ", ""))
                // We can get all sorts of markup in the sentences. This regex strips it out.
                .map(sentence -> sentence.replaceAll("[^A-Za-z0-9.\\-_?!@#$%^&*,;:\\\\/]", " "))
                .map(sentence -> String.join(" ", Arrays.stream(sentence.split("\\s+")).toList()))
                // Train again to remove any leading or trailing whitespace
                .map(String::trim)
                .toList();
    }
}
