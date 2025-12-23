package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.constants.ModelRegex;

/**
 * See <a href="https://ollama.com/library/deepseek-r1/blobs/369ca498f347">ollama</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderDeepseek implements PromptBuilder {

    @Override
    public String modelRegex() {
        return ModelRegex.DEEPSEEK_REGEX;
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

        if (StringUtils.isBlank(title)) {
            return prompt;
        }

        return title + ":\n"
                + prompt;
    }

    @Override
    public String buildFinalPrompt(@Nullable final String instructions, final String context, final String prompt) {
        if (StringUtils.isBlank(instructions)) {
            return context
                    + "\n<｜User｜>\n"
                    + prompt
                    + "\n<｜end▁of▁sentence｜>\n"
                    + "<｜Assistant｜>";
        }

        return instructions
                + "\n"
                + context
                + "\n<｜User｜>\n"
                + prompt
                + "\n<｜end▁of▁sentence｜>\n"
                + "<｜Assistant｜>";
    }
}
