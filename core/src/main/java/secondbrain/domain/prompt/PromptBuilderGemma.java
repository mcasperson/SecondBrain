package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

/**
 * See <a href="https://ai.google.dev/gemma/docs/formatting">Formatting</a> for
 * the prompt format expected by the Gemma model.
 */
@ApplicationScoped
public class PromptBuilderGemma implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^gemma\\d+.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(title)) {
            return prompt + "\n";
        }

        return title + ":\n"
                + prompt + "\n";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "<start_of_turn>user\n"
                + instructions
                + "\n"
                + context
                + "\n"
                + prompt
                + "<end_of_turn>\n"
                + "<start_of_turn>model";
    }
}
