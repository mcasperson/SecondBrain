package secondbrain.infrastructure.ollama;

import secondbrain.domain.context.RagMultiDocumentContext;

public record OllamaGenerateBodyWithContext(String model, RagMultiDocumentContext prompt, Boolean stream) {
}
