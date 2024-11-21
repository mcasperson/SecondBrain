package secondbrain.infrastructure.ollama;

import secondbrain.domain.context.RagMultiDocumentContext;

public record OllamaGenerateBodyWithContext<T>(String model, RagMultiDocumentContext<T> prompt, Boolean stream) {
}
