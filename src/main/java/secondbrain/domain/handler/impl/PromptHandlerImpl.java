package secondbrain.domain.handler.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.toolbuilder.ToolBuilder;

@ApplicationScoped
public class PromptHandlerImpl implements PromptHandler {
    @Inject
    private ToolBuilder toolBuilder;

    @Inject @Any
    private Instance<Tool> tools;

    public String handlePrompt(@NotNull final String prompt) {
        final String llmPrompt = toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);
        return llmPrompt;
    }
}
