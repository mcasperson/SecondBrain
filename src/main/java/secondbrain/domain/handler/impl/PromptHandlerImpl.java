package secondbrain.domain.handler.impl;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.tools.ToolBuilder;

@ApplicationScoped
public class PromptHandlerImpl implements PromptHandler {
    private ToolBuilder toolBuilder;

    public String handlePrompt(final String prompt) {
        return prompt;
    }
}
