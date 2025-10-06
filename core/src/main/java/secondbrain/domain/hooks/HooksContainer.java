package secondbrain.domain.hooks;

import java.util.List;

/**
 * A container for hooks that can be retrieved by name.
 */
public interface HooksContainer {
    List<PreProcessingHook> getMatchingPreProcessorHooks(String name);

    List<PostInferenceHook> getMatchingPostInferenceHooks(String name);
}
