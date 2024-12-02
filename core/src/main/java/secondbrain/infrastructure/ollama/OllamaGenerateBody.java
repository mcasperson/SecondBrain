package secondbrain.infrastructure.ollama;

public record OllamaGenerateBody(String model, String prompt, Boolean stream) {
    public OllamaGenerateBody sanitizedCopy() {
        return new OllamaGenerateBody(model.trim(), prompt.trim(), stream);
    }
}
