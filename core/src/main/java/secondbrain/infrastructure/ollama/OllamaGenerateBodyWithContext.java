package secondbrain.infrastructure.ollama;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.RagMultiDocumentContext;

public record OllamaGenerateBodyWithContext<T>(String model, @Nullable Integer contextWindow, RagMultiDocumentContext<T> prompt, Boolean stream) {
}
