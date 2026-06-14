package secondbrain.domain.processing;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class LLMRagDocSummarizer implements RagDocSummarizer {
    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private Logger logger;

    @Override
    public <T> RagDocumentContext<T> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final RagDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                toolName,
                contextLabel,
                ragDoc.document(),
                List.of()
        );

        return Try.of(() -> llmClient.callWithCache(
                        new RagMultiDocumentContext<>(
                                parsedArgs.getDocumentSummaryPrompt(),
                                "You are a helpful agent",
                                List.of(context)),
                        environmentSettings,
                        toolName
                ))
                .map(RagMultiDocumentContext::getResponse)
                .map(response -> ragDoc
                        .updateDocument(response)
                        .addIntermediateResult(new IntermediateResult(
                                "Prompt: " + parsedArgs.getDocumentSummaryPrompt() + "\n\n" + response,
                                datasource + " " + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getDocumentSummaryPrompt()) + ".txt")))
                .onFailure(ex -> logger.warning("Failed to get document summary for document " + ragDoc.id() + ": " + ex.getMessage()))
                /*
                    We have a few options when there is an exception:
                    1. Ignore this content
                    2. Return the original content
                    3. Propogate the exception
                    We have gone with option 2 here
                 */
                .getOrElse(ragDoc);
    }

    @Override
    public <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final String contextLabel,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        return ragDoc
                .stream()
                .map(doc -> getDocumentSummary(toolName, contextLabel, datasource, doc, environmentSettings, parsedArgs))
                .toList();
    }

    @Override
    public <T> List<RagDocumentContext<T>> getDocumentSummary(
            final String toolName,
            final ContextLabelCallback<T> contextLabelCallback,
            final String datasource,
            final List<RagDocumentContext<T>> ragDoc,
            final Map<String, String> environmentSettings,
            final LocalConfigSummarizer parsedArgs) {
        return ragDoc
                .stream()
                .map(doc -> getDocumentSummary(
                        toolName,
                        contextLabelCallback.getContextLabel(doc.source()),
                        datasource,
                        doc,
                        environmentSettings,
                        parsedArgs))
                .toList();
    }
}
