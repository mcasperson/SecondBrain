package secondbrain.domain.keyword;

import io.github.crew102.rapidrake.RakeAlgorithm;
import io.github.crew102.rapidrake.data.SmartWords;
import io.github.crew102.rapidrake.model.RakeParams;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * https://github.com/crew102/rapidrake-java
 */
@ApplicationScoped
public class RakeKeywordExtractor implements KeywordExtractor {
    private static final boolean SHOULD_STEM = true;
    private static final int MIN_WORD_CHAR = 1;
    private static final String PHRASE_DELIMS = "[-,.?():;\"!/]";
    private static final String[] STOP_POS = {"VB", "VBD", "VBG", "VBN", "VBP", "VBZ"};
    private static final String POStaggerURL = "en-pos-maxent.bin";
    private static final String SentDetectURL = "en-sent.bin";

    @Override
    public List<String> getKeywords(final String text) {

        final RakeParams params = new RakeParams(new SmartWords().getSmartWords(), STOP_POS, MIN_WORD_CHAR, SHOULD_STEM, PHRASE_DELIMS);

        return Try.withResources(
                        () -> RakeKeywordExtractor.class.getClassLoader().getResourceAsStream(POStaggerURL),
                        () -> RakeKeywordExtractor.class.getClassLoader().getResourceAsStream(SentDetectURL))
                .of((posTaggerURLEmbedded, sentDetectURLEmbedded) ->
                        Try.of(() -> new RakeAlgorithm(params, posTaggerURLEmbedded, sentDetectURLEmbedded))
                                .map(rakeAlg -> rakeAlg.rake(text).distinct().getFullKeywords())
                                .map(List::of)
                                .get()
                )
                .getOrElseThrow((Throwable e) -> new RuntimeException("Error while getting keywords", e));
    }
}
