package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

/**
 * See <a href="https://ollama.com/library/mistral">mistral</a> for
 * the prompt format expected by the Mistral model.
 * <a href="https://docs.mistral.ai/guides/rag/#combine-context-and-question-in-a-prompt-and-generate-response">documentation</a>
 * <a href="https://community.aws/content/2dFNOnLVQRhyrOrMsloofnW0ckZ/how-to-prompt-mistral-ai-models-and-why?lang=en">instruction prompt example</a>
 */
@ApplicationScoped
public class PromptBuilderMistral implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^mi(s|x)tral.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(title)) {
            return "---------------------\n"
                    + prompt + "\n"
                    + "---------------------";
        }

        return "---------------------\n"
                + title + ":\n"
                + prompt + "\n"
                + "---------------------";
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
