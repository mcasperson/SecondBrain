package secondbrain.infrastructure.ollama.api;

import java.util.List;

public record OllamaResponseWithContext(List<String> ids, OllamaResponse ollamaResponse) {
}
