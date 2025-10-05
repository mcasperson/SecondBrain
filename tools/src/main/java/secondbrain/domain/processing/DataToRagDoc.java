package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigEntity;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

public interface DataToRagDoc {
    <T extends TextData & IdData & UrlData> RagDocumentContext<T> getDocumentContext(
            final T task,
            final String toolName,
            final String contextLabel,
            final LocalConfigEntity parsedArgs);
}
