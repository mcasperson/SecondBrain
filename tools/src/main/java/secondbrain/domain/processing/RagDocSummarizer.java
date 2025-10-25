package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;

import java.util.List;
import java.util.Map;

/**
 * Defines a service used to summarize document contexts.
 */
public interface RagDocSummarizer {
    <T> RagDocumentContext<T> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final RagDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs);

    <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs);

    <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final ContextLabelCallback<T> contextLabelCallback,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs);
}
