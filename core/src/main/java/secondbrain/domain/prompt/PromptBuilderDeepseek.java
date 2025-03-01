package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

/**
 * See <a href="https://ollama.com/library/deepseek-r1/blobs/369ca498f347">ollama</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderDeepseek implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^deepseek-r1.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(title)) {
            return prompt;
        }

        return title + ":\n"
                + prompt;
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return instructions
                + "\n"
                + context
                + "\n<｜User｜>\n"
                + prompt
                + "\n<｜end▁of▁sentence｜>\n"
                + "<｜Assistant｜>";
    }
}
