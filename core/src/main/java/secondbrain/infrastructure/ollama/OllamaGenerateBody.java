package secondbrain.infrastructure.ollama;

import org.apache.commons.lang3.StringUtils;

public record OllamaGenerateBody(String model, String prompt, Boolean stream, OllamaGenerateBodyOptions options) {

    public OllamaGenerateBody(String model, String prompt, Boolean stream) {
        this(model, prompt, stream, new OllamaGenerateBodyOptions(null));
    }

    public OllamaGenerateBody sanitizedCopy() {
        return new OllamaGenerateBody(StringUtils.trim(model), StringUtils.trim(prompt), stream, options);
    }
}
