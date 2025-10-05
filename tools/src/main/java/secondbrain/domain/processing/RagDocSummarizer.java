package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;

import java.util.Map;

public interface RagDocSummarizer {
    <T> RagDocumentContext<T> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final RagDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs);
}
