package secondbrain.domain.processing;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import secondbrain.domain.config.LocalConfigEntity;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

@ApplicationScoped
public class SentenceVectorizerDataToRagDoc implements DataToRagDoc {
    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Override
    public <T extends TextData & IdData & UrlData> RagDocumentContext<T> getDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            final LocalConfigEntity parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(task.getText(), 10))
                .map(sentences -> new RagDocumentContext<T>(
                        toolName,
                        contextLabel,
                        task.getText(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        task.getId(),
                        task,
                        "[" + task.getLinkText() + "](" + task.getUrl() + ")"))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }
}
