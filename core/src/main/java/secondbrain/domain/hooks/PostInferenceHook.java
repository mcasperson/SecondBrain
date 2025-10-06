package secondbrain.domain.hooks;

import secondbrain.domain.context.RagMultiDocumentContext;

/**
 * A hook that is executed after the inference step.
 * It can be used to modify the RagMultiDocumentContext before it is returned to the caller.
 */
public interface PostInferenceHook extends Hook {
    <T> RagMultiDocumentContext<T> process(RagMultiDocumentContext<T> ragMultiDocumentContext);
}
