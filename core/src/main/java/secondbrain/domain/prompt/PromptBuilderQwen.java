package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * See <a href="https://www.ibm.com/granite/docs/use-cases/prompt-engineering">prompt engineering</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderQwen implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^granite4.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

        if (StringUtils.isBlank(title)) {
            return "<|start_of_role|>system<|end_of_role|>\n"
                    + prompt
                    + "\n<|end_of_text|>";
        }

        return "<|start_of_role|>system<|end_of_role|>\n"
                + title + ":\n"
                + prompt
                + "\n<|end_of_text|>";
    }

    @Override
    public String buildFinalPrompt(@Nullable final String instructions, final String context, final String prompt) {
        if (StringUtils.isBlank(instructions)) {
            return context
                    + "\n<|start_of_role|>user<|end_of_role|>\n"
                    + prompt
                    + "\n<|end_of_text|>\n"
                    + "<|start_of_role|>assistant<|end_of_role|>\n";
        }

        return context
                + "<|start_of_role|>system<|end_of_role|>\n"
                + instructions
                + "\n<|end_of_text|>\n"
                + "\n<|start_of_role|>user<|end_of_role|>\n"
                + prompt
                + "\n<|end_of_text|>\n"
                + "<|start_of_role|>assistant<|end_of_role|>\n";
    }
}
