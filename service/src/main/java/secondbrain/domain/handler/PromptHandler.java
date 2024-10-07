package secondbrain.domain.handler;

import java.util.Map;

public interface PromptHandler {
    String handlePrompt(Map<String, String> context, String prompt);
}
