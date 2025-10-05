package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

/**
 * Represents a service that will convert a raw data object, usually sourced from an external API,
 * into a RagDocumentContext object that can be used for retrieval-augmented generation (RAG) tasks.
 */
public interface DataToRagDoc {
    <T extends TextData & IdData & UrlData> RagDocumentContext<T> getDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            final LocalConfigKeywordsEntity parsedArgs);

    <T extends TextData> RagDocumentContext<T> getUnlinkedDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            final LocalConfigKeywordsEntity parsedArgs);
}
