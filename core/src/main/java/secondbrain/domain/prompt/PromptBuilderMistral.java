package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * See <a href="https://ollama.com/library/mistral">mistral</a> for
 * the prompt format expected by the Mistral model.
 */
@ApplicationScoped
public class PromptBuilderMistral implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^mistral.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        return "[INST]title" + ":\n"
                + prompt + "[/INST]";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "[INST]"
                + instructions
                + "[/INST]"
                + context
                + "[INST]"
                + prompt
                + "[/INST]";
    }
}
