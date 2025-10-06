package secondbrain.domain.hooks;

import secondbrain.domain.context.RagDocumentContext;

import java.util.List;

/**
 * A hook is used to modify the context at various stages of processing before being sent to the LLM.
 */
public interface PreprocessingHook {
    String getName();

    <T> List<RagDocumentContext<T>> process(List<RagDocumentContext<T>> contexts);
}
