package secondbrain.domain.processing;

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

@ApplicationScoped
public class LLMRagDocSummarizer implements RagDocSummarizer {
    @Inject
    @Preferred
    private LlmClient llmClient;

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

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getDocumentSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                toolName
        ).getResponse();

        return ragDoc.updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getDocumentSummaryPrompt() + "\n\n" + response,
                        datasource + " " + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getDocumentSummaryPrompt()) + ".txt"));
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
}
