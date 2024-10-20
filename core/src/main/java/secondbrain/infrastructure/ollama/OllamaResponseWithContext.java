package secondbrain.infrastructure.ollama;

import java.util.List;

public record OllamaResponseWithContext(List<String> ids, OllamaResponse ollamaResponse) {
}
