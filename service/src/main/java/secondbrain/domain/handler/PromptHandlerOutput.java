package secondbrain.domain.handler;

public interface PromptHandlerOutput {
    void printOutput(final PromptHandlerResponse content);
    void writeAnnotations(final PromptHandlerResponse content);
    void writeLinks(final PromptHandlerResponse content);
    void writeDebug(final PromptHandlerResponse content);
    void writeOutput(final PromptHandlerResponse content);
    void saveMetadata(final PromptHandlerResponse content);
    void saveIntermediateResults(final PromptHandlerResponse content);
}
