package secondbrain.infrastructure.ollama;

import org.jspecify.annotations.Nullable;

public record OllamaGenerateBodyOptions(@Nullable Integer num_ctx) {
}
