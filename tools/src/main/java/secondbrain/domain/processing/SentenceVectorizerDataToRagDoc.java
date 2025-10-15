package secondbrain.domain.processing;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

@ApplicationScoped
public class SentenceVectorizerDataToRagDoc implements DataToRagDoc {
    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Override
    public <T extends TextData & IdData & UrlData> RagDocumentContext<T> getDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            final LocalConfigKeywordsEntity parsedArgs) {
        return getDocumentContext(task, toolName, contextLabel, null, parsedArgs);
    }

    @Override
    public <T extends TextData & IdData & UrlData> RagDocumentContext<T> getDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            @Nullable final MetaObjectResults meta,
            final LocalConfigKeywordsEntity parsedArgs) {
        final TrimResult trimmedConversationResult = documentTrimmer.trimDocumentToKeywords(task.getText(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.document(), 10))
                .map(sentences -> new RagDocumentContext<T>(
                        toolName,
                        contextLabel,
                        trimmedConversationResult.document(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        task.getId(),
                        task,
                        meta,
                        null,
                        "[" + task.getLinkText() + "](" + task.getUrl() + ")",
                        trimmedConversationResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // Proceed without vectors if vectorization fails
                // This will always happen on older macs as they are no loner supported
                .recover(InternalFailure.class, e -> new RagDocumentContext<>(
                        toolName,
                        contextLabel,
                        trimmedConversationResult.document(),
                        null,
                        task.getId(),
                        task,
                        meta,
                        null,
                        "[" + task.getLinkText() + "](" + task.getUrl() + ")",
                        trimmedConversationResult.keywordMatches()))
                .get();
    }

    @Override
    public <T extends TextData> RagDocumentContext<T> getUnlinkedDocumentContext(final T task, final String toolName, final String contextLabel, final LocalConfigKeywordsEntity parsedArgs) {
        final TrimResult trimmedConversationResult = documentTrimmer.trimDocumentToKeywords(task.getText(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.document(), 10))
                .map(sentences -> new RagDocumentContext<T>(
                        toolName,
                        contextLabel,
                        task.getText(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        null,
                        null,
                        null,
                        trimmedConversationResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }
}
