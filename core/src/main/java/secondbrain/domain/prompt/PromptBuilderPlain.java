package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.Null;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

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
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

        if (StringUtils.isBlank(title)) {
            return prompt + "\n";
        }

        return title + ":\n"
                + prompt + "\n";
    }

    @Override
    public String buildFinalPrompt(@Nullable final String instructions, final String context, final String prompt) {
        if (StringUtils.isBlank(instructions)) {
            return context
                    + "\n"
                    + prompt;
        }

        return instructions
                + "\n"
                + context
                + "\n"
                + prompt;
    }
}
