package secondbrain.domain.handler;

import java.util.Map;

public interface PromptHandlerOutput {
    void printOutput(final PromptHandlerResponse content);

    void writeAnnotations(final PromptHandlerResponse content);

    void writeLinks(final PromptHandlerResponse content);

    void writeDebug(final PromptHandlerResponse content);

    void writeOutput(final PromptHandlerResponse content);

    void saveMetadata(final PromptHandlerResponse content);

    void saveIntermediateResults(final PromptHandlerResponse content);

    void printOutput(final PromptHandlerResponse content, final Map<String, String> context);

    void writeAnnotations(final PromptHandlerResponse content, final Map<String, String> context);

    void writeLinks(final PromptHandlerResponse content, final Map<String, String> context);

    void writeDebug(final PromptHandlerResponse content, final Map<String, String> context);

    void writeOutput(final PromptHandlerResponse content, final Map<String, String> context);

    void saveMetadata(final PromptHandlerResponse content, final Map<String, String> context);

    void saveIntermediateResults(final PromptHandlerResponse content, final Map<String, String> context);
}
