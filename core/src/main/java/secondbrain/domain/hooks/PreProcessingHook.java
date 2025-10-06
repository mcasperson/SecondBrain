package secondbrain.domain.hooks;

import secondbrain.domain.context.RagDocumentContext;

import java.util.List;

/**
 * A hook is used to modify the context at various stages of processing before being sent to the LLM.
 */
public interface PreProcessingHook extends Hook {
    <T> List<RagDocumentContext<T>> process(String toolName, List<RagDocumentContext<T>> contexts);
}
