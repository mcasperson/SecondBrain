package secondbrain.infrastructure;

public record OllamaGenerateBody(String model, String prompt, Boolean stream) {
}
