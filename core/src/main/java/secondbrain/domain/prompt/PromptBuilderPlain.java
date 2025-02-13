package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

/**
 * A plain prompt builder that is not specific to any model
 */
@ApplicationScoped
public class PromptBuilderPlain implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^plain$";
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
        return instructions
                + "\n"
                + context
                + "\n"
                + prompt;
    }
}
