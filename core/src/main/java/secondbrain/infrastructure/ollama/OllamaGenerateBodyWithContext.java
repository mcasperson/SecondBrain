package secondbrain.infrastructure.ollama;

import secondbrain.domain.context.MergedContext;

public record OllamaGenerateBodyWithContext(String model, MergedContext prompt, Boolean stream) {
}
