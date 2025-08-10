package secondbrain.infrastructure.llm;

import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.Map;

public interface LlmClient {
    String call(final String prompt);

    String call(final String prompt, final String model);

    <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final String tool);
}
