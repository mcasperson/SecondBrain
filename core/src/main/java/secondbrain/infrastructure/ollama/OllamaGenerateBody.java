package secondbrain.infrastructure.ollama;

public record OllamaGenerateBody(String model, String prompt, Boolean stream) {
    public OllamaGenerateBody getSanitizedBody() {
        return new OllamaGenerateBody(model.trim(), prompt.trim(), stream);
    }
}
