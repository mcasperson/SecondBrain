package secondbrain.infrastructure.ollama;

public record OllamaGenerateBody(String model, String prompt, Boolean stream) {
}
