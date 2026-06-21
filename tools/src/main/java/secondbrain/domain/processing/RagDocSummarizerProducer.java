package secondbrain.domain.processing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a RagDocSummarizer instance.
 */
@ApplicationScoped
public class RagDocSummarizerProducer {

    @Produces
    @Preferred
    @ApplicationScoped
    public RagDocSummarizer produceRagDocSummarizer(final LLMRagDocSummarizer llmRagDocSummarizer) {
        return llmRagDocSummarizer;
    }
}
