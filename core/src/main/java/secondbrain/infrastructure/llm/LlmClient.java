package secondbrain.infrastructure.llm;

import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.Map;

public interface LlmClient {
    String MODEL_OVERRIDE_ENV = "modelOverride";
    String CONTEXT_WINDOW_OVERRIDE_ENV = "contextWindowOverride";
    String REASONING_EFFORT_OVERRIDE_ENV = "reasoningEffortOverride";
    String URL_OVERRIDE_ENV = "urlOverride";

    String call(final String prompt, final Map<String, String> environmentSettings);

    String call(final String prompt, final String model, final Map<String, String> environmentSettings);

    <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final String tool);
}
