package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * See <a href="https://ollama.com/library/mistral">mistral</a> for
 * the prompt format expected by the Mistral model.
 * <a href="https://docs.mistral.ai/guides/rag/#combine-context-and-question-in-a-prompt-and-generate-response">documentation</a>
 */
@ApplicationScoped
public class PromptBuilderMistral implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^mistral.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        return "---------------------\n"
            + title + ":\n"
                + prompt
                + "\n---------------------\n";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "[INST]"
                + instructions.trim()
                + "\n"
                + context.trim()
                + "\n"
                + prompt.trim()
                + "[/INST]";
    }
}
