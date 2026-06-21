package secondbrain.domain.processing;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;

import java.util.List;
import java.util.Map;

/**
 * A mock implementation of the {@link RagDocSummarizer} interface used for testing purposes.
 * When {@code mockSummary} is set, the document text in each {@link RagDocumentContext} is
 * replaced with the mock summary. When {@code mockSummary} is {@code null}, documents are
 * returned unchanged.
 * <p>
 * This implementation is not exposed by any producer. It is only intended to be used by tests.
 */
@ApplicationScoped
public class MockRagDocSummarizer implements RagDocSummarizer {

    @Nullable
    private String mockSummary;

    /**
     * Sets the text that will be used as the summarised document content.
     *
     * @param mockSummary the summary text to substitute, or {@code null} to pass documents through unchanged.
     */
    public void setMockSummary(@Nullable final String mockSummary) {
        this.mockSummary = mockSummary;
    }

    @Override
    public <T> RagDocumentContext<T> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final RagDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        return summarize(ragDoc);
    }

    @Override
    public <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        return ragDoc.stream().map(this::summarize).toList();
    }

    @Override
    public <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final ContextLabelCallback<T> contextLabelCallback,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        return ragDoc.stream().map(this::summarize).toList();
    }

    private <T> RagDocumentContext<T> summarize(final RagDocumentContext<T> ragDoc) {
        if (mockSummary == null) {
            return ragDoc;
        }
        return new RagDocumentContext<>(
                ragDoc.tool(),
                ragDoc.contextLabel(),
                mockSummary,
                ragDoc.sentences(),
                ragDoc.id(),
                ragDoc.source(),
                ragDoc.metadata(),
                ragDoc.intermediateResults(),
                ragDoc.link(),
                ragDoc.keywordMatches());
    }
}
