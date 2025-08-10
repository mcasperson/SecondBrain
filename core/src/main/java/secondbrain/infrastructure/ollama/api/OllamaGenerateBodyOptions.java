package secondbrain.infrastructure.ollama.api;

import org.jspecify.annotations.Nullable;

public record OllamaGenerateBodyOptions(@Nullable Integer num_ctx) {
}
