package secondbrain.domain.handler;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public interface PromptHandler {
    String handlePrompt(@NotNull Map<String, String> context, @NotNull String prompt);
}
