package secondbrain.infrastructure.ollama;

public record OllamaGenerateBody(String model, String prompt, Boolean stream, OllamaGenerateBodyOptions options) {

    public OllamaGenerateBody(String model, String prompt, Boolean stream) {
        this(model, prompt, stream, new OllamaGenerateBodyOptions(null));
    }

    public OllamaGenerateBody sanitizedCopy() {
        return new OllamaGenerateBody(model.trim(), prompt.trim(), stream, options);
    }
}
